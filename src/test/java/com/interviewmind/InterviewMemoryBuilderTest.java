package com.interviewmind.memory;

import com.interviewmind.ai.dto.InterviewEvaluationAiResult;
import com.interviewmind.ai.dto.QuestionEvaluationItem;
import com.interviewmind.entity.InterviewQuestion;
import com.interviewmind.memory.dto.InterviewMemory;
import com.interviewmind.memory.dto.InterviewMemory.MemoryType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * InterviewMemoryBuilder 纯单元测试：不启动 Spring、不触达任何外部资源。
 */
class InterviewMemoryBuilderTest {

    private final InterviewMemoryBuilder builder = new InterviewMemoryBuilder();

    // ---------- 规则 1：WEAK_POINT ----------

    @Test
    void buildsWeakPointWhenWeakPointsPresent() {
        InterviewQuestion question = question(101L, 1, "Redis分布式锁");
        InterviewEvaluationAiResult result = result(85, null, null, null, null, List.of(
                evaluation(1, 5, "了解 Redisson 基本使用", List.of("Watch Dog 自动续期"), null)));

        List<InterviewMemory> memories = builder.build(11L, 22L, List.of(question), result);

        assertThat(memories).hasSize(1);
        InterviewMemory memory = memories.get(0);
        assertThat(memory.memoryType()).isEqualTo(MemoryType.WEAK_POINT);
        assertThat(memory.resumeId()).isEqualTo(11L);
        assertThat(memory.sessionId()).isEqualTo(22L);
        assertThat(memory.questionId()).isEqualTo(101L);
        assertThat(memory.knowledgePoint()).isEqualTo("Redis分布式锁");
        assertThat(memory.score()).isEqualTo(5);
        assertThat(memory.content())
                .contains("知识点：Redis分布式锁")
                .contains("用户表现：了解 Redisson 基本使用")
                .contains("薄弱点：Watch Dog 自动续期")
                .contains("评分：5/10");
    }

    @Test
    void skipsWeakPointWhenWeakPointsEmpty() {
        InterviewQuestion question = question(101L, 1, "JVM垃圾回收");
        InterviewEvaluationAiResult result = result(null, null, null, null, null, List.of(
                evaluation(1, 5, "有点印象", List.of(), null)));

        assertThat(builder.build(1L, 2L, List.of(question), result)).isEmpty();
    }

    // ---------- 规则 2：ANSWER_PATTERN ----------

    @Test
    void buildsAnswerPatternOnlyForHighValueEvaluation() {
        InterviewQuestion strong = question(101L, 1, "MySQL索引与B+树");
        InterviewQuestion medium = question(102L, 2, "Spring循环依赖");
        InterviewEvaluationAiResult result = result(null, null, null, null, null, List.of(
                evaluation(1, 9, "准确说明 B+ 树叶子链表与范围查询优势", null, null),
                evaluation(2, 7, "说出三级缓存但漏了循环引用场景", null, null)));

        List<InterviewMemory> memories = builder.build(1L, 2L, List.of(strong, medium), result);

        assertThat(memories).hasSize(1);
        InterviewMemory memory = memories.get(0);
        assertThat(memory.memoryType()).isEqualTo(MemoryType.ANSWER_PATTERN);
        assertThat(memory.questionId()).isEqualTo(101L);
        assertThat(memory.score()).isEqualTo(9);
        assertThat(memory.content())
                .contains("知识点：MySQL索引与B+树")
                .contains("优秀回答：")
                .contains("评分：9/10");
    }

    // ---------- 规则 3：INTERVIEW_SUMMARY ----------

    @Test
    void buildsExactlyOneSummaryMemory() {
        InterviewEvaluationAiResult result = result(85,
                "候选人基础扎实，表达清晰。",
                List.of("Redis 实战熟悉"), List.of("分布式事务理解浅"), List.of("补齐 Seata 原理"),
                List.of());

        List<InterviewMemory> memories = builder.build(1L, 2L, List.of(), result);

        assertThat(memories).hasSize(1);
        InterviewMemory memory = memories.get(0);
        assertThat(memory.memoryType()).isEqualTo(MemoryType.INTERVIEW_SUMMARY);
        assertThat(memory.questionId()).isNull();
        assertThat(memory.knowledgePoint()).isNull();
        assertThat(memory.score()).isEqualTo(85);
        assertThat(memory.content())
                .contains("整场面试总结（总评分 85/100）")
                .contains("总结：候选人基础扎实，表达清晰。")
                .contains("优势：Redis 实战熟悉")
                .contains("薄弱点：分布式事务理解浅")
                .contains("建议：补齐 Seata 原理");
    }

    @Test
    void skipsSummaryWhenAllSummaryFieldsBlank() {
        InterviewEvaluationAiResult result = result(85, "  ", List.of(), List.of(), List.of(), List.of());

        assertThat(builder.build(1L, 2L, List.of(), result)).isEmpty();
    }

    // ---------- 规则 5：去重 ----------

    @Test
    void filtersDuplicateMemories() {
        InterviewQuestion first = question(101L, 1, "Redis分布式锁");
        InterviewQuestion second = question(102L, 2, "Redis分布式锁");
        InterviewEvaluationAiResult result = result(null, null, null, null, null, List.of(
                evaluation(1, 5, "同一段点评", List.of("同一薄弱点"), null),
                evaluation(2, 5, "同一段点评", List.of("同一薄弱点"), null)));

        List<InterviewMemory> memories = builder.build(1L, 2L, List.of(first, second), result);

        assertThat(memories).hasSize(1);
    }

    // ---------- 规则 6：长度封顶 ----------

    @Test
    void capsContentLength() {
        InterviewQuestion question = question(101L, 1, "高并发系统设计");
        String longAnalysis = "非常长的点评".repeat(200);
        InterviewEvaluationAiResult result = result(null, null, null, null, null, List.of(
                evaluation(1, 4, longAnalysis, List.of("薄弱点", longAnalysis), null)));

        List<InterviewMemory> memories = builder.build(1L, 2L, List.of(question), result);

        assertThat(memories).hasSize(1);
        assertThat(memories.get(0).content().length())
                .isLessThanOrEqualTo(InterviewMemoryBuilder.MAX_CONTENT_LENGTH);
    }

    // ---------- 结构化 ID 不进入语义文本 ----------

    @Test
    void doesNotEmbedStructuralIdsIntoContent() {
        InterviewQuestion question = question(987654L, 7, "RocketMQ削峰填谷");
        InterviewEvaluationAiResult result = result(77, "整体尚可",
                List.of("表达流畅"), List.of("细节不足"), List.of("多复习"),
                List.of(evaluation(7, 6, "回答偏泛", List.of("未提重复消费"), null)));

        List<InterviewMemory> memories = builder.build(1L, 2L, List.of(question), result);

        assertThat(memories).isNotEmpty();
        for (InterviewMemory memory : memories) {
            assertThat(memory.content())
                    .doesNotContain("sessionId")
                    .doesNotContain("questionId")
                    .doesNotContain("questionNo")
                    .doesNotContain("createdAt")
                    .doesNotContain("987654");
        }
    }

    // ---------- 空值安全 ----------

    @Test
    void handlesNullFieldsSafely() {
        InterviewQuestion question = question(101L, 1, null);
        InterviewEvaluationAiResult nullResult = null;
        assertThat(builder.build(1L, 2L, null, nullResult)).isEmpty();

        InterviewEvaluationAiResult result = new InterviewEvaluationAiResult(
                null, null, null, null, null, null);
        assertThat(builder.build(1L, 2L, List.of(question), result)).isEmpty();

        List<QuestionEvaluationItem> withNullEntries = new ArrayList<>();
        withNullEntries.add(null);
        withNullEntries.add(new QuestionEvaluationItem(null, null, null, null, null));
        InterviewEvaluationAiResult noisy = new InterviewEvaluationAiResult(
                null, null, null, null, null, withNullEntries);
        assertThat(builder.build(1L, 2L, List.of(question), noisy)).isEmpty();
    }

    // ---------- 工具 ----------

    private static InterviewQuestion question(Long id, int no, String knowledgePoint) {
        InterviewQuestion question = new InterviewQuestion();
        question.setId(id);
        question.setSessionId(22L);
        question.setQuestionNo(no);
        question.setQuestion("请说明第 " + no + " 题的原理与实践要点。");
        question.setType("TECHNICAL");
        question.setDifficulty("MEDIUM");
        question.setKnowledgePoint(knowledgePoint);
        return question;
    }

    private static QuestionEvaluationItem evaluation(int questionNo, Integer score, String analysis,
                                                     List<String> weakPoints, List<String> suggestions) {
        return new QuestionEvaluationItem(questionNo, score, analysis, weakPoints, suggestions);
    }

    private static InterviewEvaluationAiResult result(Integer totalScore, String summary,
                                                      List<String> strengths, List<String> weakPoints,
                                                      List<String> suggestions,
                                                      List<QuestionEvaluationItem> evaluations) {
        return new InterviewEvaluationAiResult(totalScore, summary, strengths, weakPoints,
                suggestions, evaluations);
    }

}
