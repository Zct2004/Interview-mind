package com.interviewmind.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.interviewmind.ai.InterviewQuestionAiParser;
import com.interviewmind.ai.dto.InterviewQuestionAiResult;
import com.interviewmind.ai.dto.QuestionItem;
import com.interviewmind.dto.InterviewQuestionResponse;
import com.interviewmind.entity.Resume;
import com.interviewmind.entity.ResumeProfile;
import com.interviewmind.exception.BusinessException;
import com.interviewmind.mapper.ResumeMapper;
import com.interviewmind.mapper.ResumeProfileMapper;
import com.interviewmind.memory.dto.InterviewMemory;
import com.interviewmind.rag.InterviewMemoryRetriever;
import com.interviewmind.rag.InterviewQuestionWeight;
import com.interviewmind.rag.InterviewRagContext;
import com.interviewmind.rag.KnowledgePointAnalyzer;
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
        InterviewRagContext ragContext = buildRagContext(resumeId);
        List<String> knowledgePoints = ragContext.weights().stream()
                .map(InterviewQuestionWeight::knowledgePoint)
                .toList();
        log.info("Dynamic interview question generation: resumeId={}, knowledgePoints={}, ragEnabled={}",
                resumeId, String.join(",", knowledgePoints), ragContext.hasHistory());

        long startMillis = System.currentTimeMillis();
        InterviewQuestionAiResult result =
                interviewQuestionAiParser.parse(profile.getRawText(), ragContext);
        long costMillis = System.currentTimeMillis() - startMillis;

        List<QuestionItem> questions = result.questions();
        log.info("面试题生成完成: resumeId={}, count={}, cost={}ms", resumeId, questions.size(), costMillis);
        return new InterviewQuestionResponse(resumeId, questions);
    }

    /**
     * 构建 RAG 上下文；任何异常（PGVector 查询失败、向量服务不可用等）
     * 都降级为空上下文 + ERROR 日志——第一轮无历史与检索失败对外表现一致。
     */
    private InterviewRagContext buildRagContext(Long resumeId) {
        try {
            List<InterviewMemory> memories = interviewMemoryRetriever.retrieve(resumeId);
            List<InterviewQuestionWeight> weights = knowledgePointAnalyzer.analyze(memories);
            return new InterviewRagContext(weights, memories);
        } catch (Exception e) {
            log.error("RAG 历史记忆检索失败，回退为普通简历出题（不影响开始面试）: resumeId={}",
                    resumeId, e);
            return InterviewRagContext.empty();
        }
    }

}
