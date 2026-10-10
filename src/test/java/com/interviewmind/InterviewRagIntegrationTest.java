package com.interviewmind;

import com.interviewmind.memory.InterviewMemoryStore;
import com.interviewmind.memory.dto.InterviewMemory;
import com.interviewmind.memory.dto.InterviewMemory.MemoryType;
import com.interviewmind.rag.InterviewMemoryRetriever;
import com.interviewmind.rag.InterviewQuestionWeight;
import com.interviewmind.rag.InterviewRagContext;
import com.interviewmind.rag.KnowledgePointAnalyzer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * RAG 读取链路集成测试（mvn test，零真实 LLM / Embedding 调用）：
 * fake/stub 向量出口 → InterviewMemoryRetriever（单次检索，不按 resumeId 过滤）
 * → KnowledgePointAnalyzer（知识点聚合 + 动态权重）。
 * <p>
 * 与 InterviewSessionIntegrationTest / InterviewMemoryIntegrationTest 共享同一
 * Spring 上下文（import 集相同），通过 clear() 做测试间隔离。
 * 真实 PGVector 检索链路由 {@link InterviewQuestionRagAiIntegrationTest}（@Tag("ai")）覆盖。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({
        InterviewQuestionIntegrationTest.FakeQuestionAiConfig.class,
        InterviewSessionIntegrationTest.FakeEvaluationAiConfig.class,
        FakeInterviewMemoryStoreConfig.class
})
class InterviewRagIntegrationTest {

    @Autowired
    private InterviewMemoryStore memoryStore;

    @Autowired
    private InterviewMemoryRetriever retriever;

    @Autowired
    private KnowledgePointAnalyzer analyzer;

    @BeforeEach
    void reset() {
        store().clear();
    }

    @Test
    void retrievesHistoryOnceAndAggregatesDynamicWeights() {
        long resumeId = 77L;
        store().seedSearchable(List.of(
                memory(resumeId, "Redis分布式锁", MemoryType.WEAK_POINT, 4),
                memory(resumeId, "Redis分布式锁", MemoryType.WEAK_POINT, 5),
                memory(resumeId, "Redis分布式锁", MemoryType.ANSWER_PATTERN, 7),
                memory(resumeId, "JVM垃圾回收", MemoryType.WEAK_POINT, 6),
                memory(resumeId, "JVM垃圾回收", MemoryType.ANSWER_PATTERN, 8),
                // 其他简历的历史记忆：统一 RAG 必须一并检索
                memory(resumeId + 1, "MySQL索引优化", MemoryType.WEAK_POINT, 1)));

        List<InterviewMemory> retrieved = retriever.retrieve(resumeId);

        // 单次检索（禁止 N+1），参数正确
        assertThat(store().searchCalls()).hasSize(1);
        FakeInterviewMemoryStoreConfig.SearchCall call = store().searchCalls().get(0);
        assertThat(call.resumeId()).isNull();
        assertThat(call.topK()).isEqualTo(30);
        assertThat(call.query()).isNotBlank();
        // 统一 RAG 返回全部简历来源的 6 条记忆
        assertThat(retrieved).hasSize(6);

        List<InterviewQuestionWeight> weights = analyzer.analyze(retrieved);
        assertThat(weights).hasSize(3);

        // 所有简历来源都参与分析，按权重排序可能让其他简历的薄弱点排在前面。
        assertThat(weights).extracting(InterviewQuestionWeight::knowledgePoint)
                .containsExactlyInAnyOrder("Redis分布式锁", "JVM垃圾回收", "MySQL索引优化");

        // Redis：(4+5+7)/3 = 5.33、weak 2、3 条 → 1.5 + 0.4 = 1.9
        InterviewQuestionWeight redis = weights.stream()
                .filter(item -> item.knowledgePoint().equals("Redis分布式锁"))
                .findFirst().orElseThrow();
        assertThat(redis.knowledgePoint()).isEqualTo("Redis分布式锁");
        assertThat(redis.averageScore()).isCloseTo(16.0 / 3, within(0.001));
        assertThat(redis.weakPointCount()).isEqualTo(2);
        assertThat(redis.memoryCount()).isEqualTo(3);
        assertThat(redis.weight()).isCloseTo(1.9, within(0.001));

        // JVM：(6+8)/2 = 7、weak 1、2 条 → 1.0 + 0.2 = 1.2
        InterviewQuestionWeight jvm = weights.stream()
                .filter(item -> item.knowledgePoint().equals("JVM垃圾回收"))
                .findFirst().orElseThrow();
        assertThat(jvm.knowledgePoint()).isEqualTo("JVM垃圾回收");
        assertThat(jvm.averageScore()).isCloseTo(7.0, within(0.001));
        assertThat(jvm.weakPointCount()).isEqualTo(1);
        assertThat(jvm.memoryCount()).isEqualTo(2);
        assertThat(jvm.weight()).isCloseTo(1.2, within(0.001));
    }

    @Test
    void noHistoryYieldsEmptyRagContextWithoutError() {
        List<InterviewMemory> retrieved = retriever.retrieve(88L);

        assertThat(retrieved).isEmpty();
        List<InterviewQuestionWeight> weights = analyzer.analyze(retrieved);
        assertThat(weights).isEmpty();

        InterviewRagContext context = new InterviewRagContext(weights, retrieved);
        assertThat(context.hasHistory()).isFalse();
        assertThat(context.weights()).isEmpty();
        assertThat(context.relevantMemories()).isEmpty();
    }

    private FakeInterviewMemoryStoreConfig.CapturingMemoryStore store() {
        return (FakeInterviewMemoryStoreConfig.CapturingMemoryStore) memoryStore;
    }

    private static InterviewMemory memory(long resumeId, String knowledgePoint,
                                           MemoryType type, int score) {
        return new InterviewMemory(resumeId, 1L, 1L, type, knowledgePoint, score,
                "历史记忆：" + knowledgePoint + "，得分" + score + "。");
    }

}
