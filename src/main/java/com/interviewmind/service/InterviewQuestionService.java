package com.interviewmind.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.interviewmind.ai.InterviewQuestionAiParser;
import com.interviewmind.ai.dto.InterviewQuestionAiResult;
import com.interviewmind.ai.dto.QuestionItem;
import com.interviewmind.dto.InterviewQuestionResponse;
import com.interviewmind.entity.Resume;
import com.interviewmind.entity.ResumeProfile;
import com.interviewmind.entity.InterviewQuestion;
import com.interviewmind.entity.InterviewSession;
import com.interviewmind.mapper.InterviewQuestionMapper;
import com.interviewmind.mapper.InterviewSessionMapper;
import com.interviewmind.exception.BusinessException;
import com.interviewmind.mapper.ResumeMapper;
import com.interviewmind.mapper.ResumeProfileMapper;
import com.interviewmind.memory.dto.InterviewMemory;
import com.interviewmind.rag.InterviewMemoryRetriever;
import com.interviewmind.rag.InterviewQuestionWeight;
import com.interviewmind.rag.InterviewRagContext;
import com.interviewmind.rag.KnowledgePointAnalyzer;
import com.interviewmind.rag.InterviewQuestionDeduplicator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 面试题生成编排：ResumeProfile.rawText + 历史 Interview Memory（RAG 读取）
 * → Spring AI → 20 道面试题。
 * <p>
 * RAG 链路：InterviewMemoryRetriever（一次 PGVector similaritySearch）
 * → KnowledgePointAnalyzer（Java 内存聚合）→ 动态权重 → InterviewRagContext
 * → 出题 AI。RAG 是增强能力：无历史照常出题，检索/分析失败仅记 ERROR 日志并
 * 回退纯简历模式，绝不阻塞“开始面试”。权重由后端计算，LLM 只负责定性采纳。
 * <p>
 * 本阶段结果不落库（题目快照由 InterviewSessionService 短事务写入），因此全程无写事务；
 * LLM 与 PGVector 查询都不在任何数据库事务中。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewQuestionService {

    private final ResumeMapper resumeMapper;
    private final ResumeProfileMapper resumeProfileMapper;
    private final InterviewQuestionAiParser interviewQuestionAiParser;
    private final InterviewMemoryRetriever interviewMemoryRetriever;
    private final KnowledgePointAnalyzer knowledgePointAnalyzer;
    private final InterviewSessionMapper interviewSessionMapper;
    private final InterviewQuestionMapper interviewQuestionMapper;

    /**
     * 为已解析的简历生成一轮 20 道面试题（有历史记忆时按动态权重调整分布）。
     *
     * @throws BusinessException 404 简历不存在；409 简历尚未解析；502 AI 调用失败
     */
    public InterviewQuestionResponse generate(Long resumeId) {
        Resume resume = resumeMapper.selectById(resumeId);
        if (resume == null) {
            throw new BusinessException(404, "简历不存在: " + resumeId);
        }
        if (!Resume.STATUS_PARSED.equals(resume.getStatus())) {
            throw new BusinessException(409, "简历尚未解析，请先执行解析: " + resumeId
                    + "（当前状态 " + resume.getStatus() + "）");
        }

        ResumeProfile profile = resumeProfileMapper.selectOne(
                new LambdaQueryWrapper<ResumeProfile>().eq(ResumeProfile::getResumeId, resumeId));
        if (profile == null || profile.getRawText() == null || profile.getRawText().isBlank()) {
            throw new BusinessException(409, "简历解析结果不存在，请重新解析: " + resumeId);
        }

        // RAG 读取：历史 Memory → 知识点聚合 → 动态权重（失败隔离，不阻塞出题）
        String retrievalContext = (profile.getSkills() == null ? "" : profile.getSkills())
                + "\n" + (profile.getProjectExperience() == null ? "" : profile.getProjectExperience());
        InterviewRagContext ragContext = buildRagContext(resumeId, retrievalContext);
        List<String> knowledgePoints = ragContext.weights().stream()
                .map(InterviewQuestionWeight::knowledgePoint)
                .toList();
        log.info("Dynamic interview question generation: resumeId={}, knowledgePoints={}, ragEnabled={}",
                resumeId, String.join(",", knowledgePoints), ragContext.hasHistory());

        List<String> previousQuestions = loadPreviousQuestions(resumeId);
        String enrichedText = profile.getRawText() + buildQuestionConstraints(previousQuestions);

        long startMillis = System.currentTimeMillis();
        InterviewQuestionAiResult result = interviewQuestionAiParser.parse(enrichedText, ragContext);
        List<QuestionItem> unique = uniqueQuestions(result.questions(), previousQuestions, 20);
        if (unique.size() < 20) {
            String repairText = enrichedText + "\n\n【必须排除的已使用或重复题目】\n" + String.join("\n", previousQuestions)
                    + "\n请重新生成完整 20 道题，每道题必须与上述题目及本轮其他题目不同。";
            result = interviewQuestionAiParser.parse(repairText, ragContext);
            unique = uniqueQuestions(result.questions(), previousQuestions, 20);
        }
        if (unique.size() < 20) {
            throw new BusinessException(502, "去重后仅生成 " + unique.size() + " 道有效题目，请重试；系统不会返回重复题目");
        }
        long costMillis = System.currentTimeMillis() - startMillis;
        log.info("面试题生成完成: resumeId={}, count={}, historicalCount={}, cost={}ms",
                resumeId, unique.size(), previousQuestions.size(), costMillis);
        return new InterviewQuestionResponse(resumeId, List.copyOf(unique));
    }

    private List<String> loadPreviousQuestions(Long resumeId) {
        List<Long> sessionIds = interviewSessionMapper.selectList(
                new LambdaQueryWrapper<InterviewSession>().eq(InterviewSession::getResumeId, resumeId))
                .stream().map(InterviewSession::getId).toList();
        if (sessionIds.isEmpty()) return List.of();
        return interviewQuestionMapper.selectList(
                new LambdaQueryWrapper<InterviewQuestion>().in(InterviewQuestion::getSessionId, sessionIds))
                .stream().map(InterviewQuestion::getQuestion).filter(q -> q != null && !q.isBlank()).toList();
    }

    private String buildQuestionConstraints(List<String> previous) {
        StringBuilder text = new StringBuilder("\n\n【跨轮去重硬约束】\n");
        text.append("以下是该简历此前实际使用过的面试题。新一轮不得复用相同或实质相同的问题；允许考察同一知识点，但必须换一个不同的考察角度。\n");
        previous.stream().limit(200).forEach(q -> text.append("- ").append(q).append('\n'));
        text.append("\n自动检索到的个人 RAG 经验包含所有已建立索引的真实录音复盘和模拟面试历史。请综合历史薄弱点、优秀回答、参考答案和改进建议生成个性化题目，不需要指定单个录音来源。\n");
        return text.toString();
    }

    private List<QuestionItem> uniqueQuestions(List<QuestionItem> candidates, List<String> previous, int limit) {
        List<QuestionItem> unique = new java.util.ArrayList<>();
        List<String> seen = new java.util.ArrayList<>(previous);
        for (QuestionItem item : candidates) {
            if (item == null || item.question() == null || item.question().isBlank()) continue;
            if (!InterviewQuestionDeduplicator.isDuplicate(item.question(), seen)) {
                unique.add(item);
                seen.add(item.question());
            }
            if (unique.size() >= limit) break;
        }
        return unique;
    }

    /**
     * 构建 RAG 上下文；任何异常（PGVector 查询失败、向量服务不可用等）
     * 都降级为空上下文 + ERROR 日志——第一轮无历史与检索失败对外表现一致。
     */
    private InterviewRagContext buildRagContext(Long resumeId, String retrievalContext) {
        try {
            List<InterviewMemory> memories = interviewMemoryRetriever.retrieve(resumeId, retrievalContext);
            List<InterviewQuestionWeight> weights = knowledgePointAnalyzer.analyze(memories);
            return new InterviewRagContext(weights, memories);
        } catch (Exception e) {
            log.error("RAG 历史记忆检索失败，回退为普通简历出题（不影响开始面试）: resumeId={}",
                    resumeId, e);
            return InterviewRagContext.empty();
        }
    }

}
