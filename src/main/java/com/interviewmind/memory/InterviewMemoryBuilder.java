package com.interviewmind.memory;

import com.interviewmind.ai.dto.InterviewEvaluationAiResult;
import com.interviewmind.ai.dto.QuestionEvaluationItem;
import com.interviewmind.entity.InterviewQuestion;
import com.interviewmind.memory.dto.InterviewMemory;
import com.interviewmind.memory.dto.InterviewMemory.MemoryType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * InterviewEvaluationAiResult → List&lt;InterviewMemory&gt; 的纯规则构建器。
 * <p>
 * 不调用新 LLM、不访问数据库：直接复用已完成的 AI 评价结果提炼长期价值信息，
 * 而不是把 answer_text 原文全量向量化。生成规则（第一版）：
 * <ol>
 *   <li>逐题 weakPoints 非空 → WEAK_POINT（知识点 + 用户表现 + 薄弱点 + 评分）</li>
 *   <li>高价值回答（score ≥ 8 且点评非空）→ ANSWER_PATTERN</li>
 *   <li>整场 summary/strengths/weakPoints/suggestions → INTERVIEW_SUMMARY（恰好一条）</li>
 *   <li>空内容不生成</li>
 *   <li>按（类型 + 知识点 + 内容）过滤重复</li>
 *   <li>内容长度封顶，避免无意义超长文本</li>
 * </ol>
 * sessionId/questionNo/createdAt 等纯结构化字段不进入 content，只由存储层放入 metadata。
 */
@Component
public class InterviewMemoryBuilder {

    /** 单条 Memory 语义文本长度上限 */
    static final int MAX_CONTENT_LENGTH = 600;

    /** ANSWER_PATTERN 的高价值分数阈值 */
    static final int HIGH_VALUE_SCORE = 8;

    private static final int MAX_LINE_LENGTH = 150;
    private static final int MAX_QUESTION_LABEL_LENGTH = 60;
    private static final String ITEM_SEPARATOR = "；";

    /**
     * @param resumeId  归属简历
     * @param sessionId 产生记忆的面试会话
     * @param questions 本场面试的题目（提供 questionId / 知识点 / 题面）
     * @param result    已完成的 AI 整场评价（questionEvaluations 应已按题号归一化）
     */
    public List<InterviewMemory> build(Long resumeId, Long sessionId,
                                       List<InterviewQuestion> questions,
                                       InterviewEvaluationAiResult result) {
        List<InterviewMemory> memories = new ArrayList<>();
        if (result == null) {
            return memories;
        }

        Map<Integer, InterviewQuestion> questionByNo = new HashMap<>();
        if (questions != null) {
            for (InterviewQuestion question : questions) {
                if (question != null && question.getQuestionNo() != null) {
                    questionByNo.put(question.getQuestionNo(), question);
                }
            }
        }

        Set<String> seen = new HashSet<>();
        buildQuestionMemories(resumeId, sessionId, questionByNo, result, memories, seen);
        buildSummaryMemory(resumeId, sessionId, result, memories, seen);
        return memories;
    }

    // ---------- 规则 1/2：逐题记忆 ----------

    private void buildQuestionMemories(Long resumeId, Long sessionId,
                                       Map<Integer, InterviewQuestion> questionByNo,
                                       InterviewEvaluationAiResult result,
                                       List<InterviewMemory> memories,
                                       Set<String> seen) {
        if (result.questionEvaluations() == null) {
            return;
        }
        for (QuestionEvaluationItem evaluation : result.questionEvaluations()) {
            if (evaluation == null || evaluation.questionNo() == null) {
                continue;
            }
            InterviewQuestion question = questionByNo.get(evaluation.questionNo());
            if (question == null) {
                continue;
            }
            String label = labelOf(question);
            String knowledgePoint = knowledgePointOf(question);

            // 规则 1：weakPoints 非空 → WEAK_POINT
            if (hasItems(evaluation.weakPoints())) {
                addIfAbsent(seen, memories, new InterviewMemory(
                        resumeId, sessionId, question.getId(), MemoryType.WEAK_POINT,
                        knowledgePoint, evaluation.score(),
                        contentOf(label,
                                line("用户表现", evaluation.analysis()),
                                line("薄弱点", join(evaluation.weakPoints())),
                                scoreLine(evaluation.score()))));
            }

            // 规则 2：高价值回答 → ANSWER_PATTERN
            if (evaluation.score() != null && evaluation.score() >= HIGH_VALUE_SCORE
                    && notBlank(evaluation.analysis())) {
                addIfAbsent(seen, memories, new InterviewMemory(
                        resumeId, sessionId, question.getId(), MemoryType.ANSWER_PATTERN,
                        knowledgePoint, evaluation.score(),
                        contentOf(label,
                                line("优秀回答", evaluation.analysis()),
                                scoreLine(evaluation.score()))));
            }
        }
    }

    // ---------- 规则 3：整场总结 ----------

    private void buildSummaryMemory(Long resumeId, Long sessionId,
                                    InterviewEvaluationAiResult result,
                                    List<InterviewMemory> memories,
                                    Set<String> seen) {
        String header = result.totalScore() != null
                ? "整场面试总结（总评分 " + result.totalScore() + "/100）"
                : "整场面试总结";
        List<String> parts = new ArrayList<>();
        addIfContent(parts, line("总结", result.summary()));
        addIfContent(parts, line("优势", join(result.strengths())));
        addIfContent(parts, line("薄弱点", join(result.weakPoints())));
        addIfContent(parts, line("建议", join(result.suggestions())));
        if (parts.isEmpty()) {
            return;
        }
        StringBuilder content = new StringBuilder(header);
        for (String part : parts) {
            content.append('\n').append(part);
        }
        addIfAbsent(seen, memories, new InterviewMemory(
                resumeId, sessionId, null, MemoryType.INTERVIEW_SUMMARY,
                null, result.totalScore(),
                truncate(content.toString(), MAX_CONTENT_LENGTH)));
    }

    // ---------- 组装与去重（规则 4/5/6） ----------

    /** 规则 4：空内容不生成；规则 5：（类型 + 知识点 + 内容）重复丢弃。 */
    private static void addIfAbsent(Set<String> seen, List<InterviewMemory> memories,
                                    InterviewMemory memory) {
        if (!notBlank(memory.content())) {
            return;
        }
        String key = memory.memoryType() + "|"
                + (memory.knowledgePoint() == null ? "" : memory.knowledgePoint()) + "|"
                + memory.content();
        if (seen.add(key)) {
            memories.add(memory);
        }
    }

    /** header + 非空行拼装，整体按 MAX_CONTENT_LENGTH 封顶（规则 6）。 */
    private static String contentOf(String header, String... lines) {
        StringBuilder content = new StringBuilder(header);
        for (String line : lines) {
            if (notBlank(line)) {
                content.append('\n').append(line);
            }
        }
        return truncate(content.toString(), MAX_CONTENT_LENGTH);
    }

    // ---------- 文本工具 ----------

    private static String labelOf(InterviewQuestion question) {
        if (notBlank(question.getKnowledgePoint())) {
            return "知识点：" + question.getKnowledgePoint().trim();
        }
        return "问题：" + truncate(nullToEmpty(question.getQuestion()),
                MAX_QUESTION_LABEL_LENGTH);
    }

    private static String knowledgePointOf(InterviewQuestion question) {
        return notBlank(question.getKnowledgePoint()) ? question.getKnowledgePoint().trim() : null;
    }

    private static String line(String name, String value) {
        return notBlank(value) ? name + "：" + truncate(value.trim(), MAX_LINE_LENGTH) : null;
    }

    private static String scoreLine(Integer score) {
        return score == null ? null : "评分：" + score + "/10";
    }

    private static String join(List<String> items) {
        if (!hasItems(items)) {
            return null;
        }
        StringBuilder joined = new StringBuilder();
        for (String item : items) {
            if (notBlank(item)) {
                if (!joined.isEmpty()) {
                    joined.append(ITEM_SEPARATOR);
                }
                joined.append(item.trim());
            }
        }
        return joined.toString();
    }

    private static boolean hasItems(List<String> items) {
        if (items == null) {
            return false;
        }
        return items.stream().anyMatch(InterviewMemoryBuilder::notBlank);
    }

    private static void addIfContent(List<String> parts, String line) {
        if (notBlank(line)) {
            parts.add(line);
        }
    }

    private static String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() > maxLength ? text.substring(0, maxLength - 1) + "…" : text;
    }

    private static boolean notBlank(String text) {
        return text != null && !text.isBlank();
    }

    private static String nullToEmpty(String text) {
        return text == null ? "" : text;
    }

}
