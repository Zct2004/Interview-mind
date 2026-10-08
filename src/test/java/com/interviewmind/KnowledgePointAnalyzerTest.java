package com.interviewmind;

import com.interviewmind.memory.dto.InterviewMemory;
import com.interviewmind.memory.dto.InterviewMemory.MemoryType;
import com.interviewmind.rag.InterviewQuestionWeight;
import com.interviewmind.rag.KnowledgePointAnalyzer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * knowledgePoint 聚合单元测试：正常聚合、多条同知识点、空知识点、
 * 分数边界、weakPointCount 统计与权重只算一次。
 */
class KnowledgePointAnalyzerTest {

    private final KnowledgePointAnalyzer analyzer = new KnowledgePointAnalyzer();

    @Test
    void aggregatesByKnowledgePoint() {
        // 规格示例：Redis 4/5/7 → avg 5.33、weak 2、3 条；JVM 6/8 → avg 7、weak 1、2 条
        List<InterviewMemory> memories = List.of(
                memory("Redis分布式锁", MemoryType.WEAK_POINT, 4),
                memory("Redis分布式锁", MemoryType.WEAK_POINT, 5),
                memory("Redis分布式锁", MemoryType.ANSWER_PATTERN, 7),
                memory("JVM垃圾回收", MemoryType.WEAK_POINT, 6),
                memory("JVM垃圾回收", MemoryType.ANSWER_PATTERN, 8));

        List<InterviewQuestionWeight> weights = analyzer.analyze(memories);

        assertThat(weights).hasSize(2);
        // weight 降序：Redis(1.9) 先于 JVM(1.2)
        InterviewQuestionWeight redis = weights.get(0);
        assertThat(redis.knowledgePoint()).isEqualTo("Redis分布式锁");
        assertThat(redis.averageScore()).isCloseTo(16.0 / 3, within(0.001));
        assertThat(redis.weakPointCount()).isEqualTo(2);
        assertThat(redis.memoryCount()).isEqualTo(3);
        assertThat(redis.weight()).isCloseTo(1.9, within(0.001)); // 1.5 + 2×0.2

        InterviewQuestionWeight jvm = weights.get(1);
        assertThat(jvm.knowledgePoint()).isEqualTo("JVM垃圾回收");
        assertThat(jvm.averageScore()).isCloseTo(7.0, within(0.001));
        assertThat(jvm.weakPointCount()).isEqualTo(1);
        assertThat(jvm.memoryCount()).isEqualTo(2);
        assertThat(jvm.weight()).isCloseTo(1.2, within(0.001)); // 1.0 + 1×0.2
    }

    @Test
    void multipleMemoriesOfSameKnowledgePointProduceSingleWeight() {
        // 同一知识点 5 条记忆 → 恰好 1 条权重记录，weight 只算一次（不重复放大）
        List<InterviewMemory> memories = List.of(
                memory("Redis分布式锁", MemoryType.WEAK_POINT, 4),
                memory("Redis分布式锁", MemoryType.WEAK_POINT, 4),
                memory("Redis分布式锁", MemoryType.WEAK_POINT, 4),
                memory("Redis分布式锁", MemoryType.WEAK_POINT, 4),
                memory("Redis分布式锁", MemoryType.WEAK_POINT, 4));

        List<InterviewQuestionWeight> weights = analyzer.analyze(memories);

        assertThat(weights).hasSize(1);
        InterviewQuestionWeight only = weights.get(0);
        assertThat(only.memoryCount()).isEqualTo(5);
        assertThat(only.weakPointCount()).isEqualTo(5);
        assertThat(only.averageScore()).isEqualTo(4);
        // 1.5 + 5×0.2 = 2.5（只算一次；若重复叠加会远超 3.0 上限）
        assertThat(only.weight()).isCloseTo(2.5, within(0.001));
    }

    @Test
    void blankKnowledgePointsAreSkippedWithoutError() {
        List<InterviewMemory> memories = List.of(
                memory(null, MemoryType.INTERVIEW_SUMMARY, 80),
                memory("   ", MemoryType.WEAK_POINT, 3),
                memory("Spring循环依赖", MemoryType.WEAK_POINT, 6));

        List<InterviewQuestionWeight> weights = analyzer.analyze(memories);

        assertThat(weights).hasSize(1);
        assertThat(weights.get(0).knowledgePoint()).isEqualTo("Spring循环依赖");
        assertThat(weights.get(0).memoryCount()).isEqualTo(1);
    }

    @Test
    void scoreBoundariesClampedAndWeakPointCountedByTypeOnly() {
        // 0 分与 10 分均钳制在合法区间；ANSWER_PATTERN 不计入 weakPointCount
        List<InterviewMemory> memories = List.of(
                memory("知识点甲", MemoryType.WEAK_POINT, 0),
                memory("知识点甲", MemoryType.ANSWER_PATTERN, 10),
                memory("知识点乙", MemoryType.ANSWER_PATTERN, 10));

        List<InterviewQuestionWeight> weights = analyzer.analyze(memories);

        assertThat(weights).hasSize(2);
        InterviewQuestionWeight jia = weights.stream()
                .filter(w -> w.knowledgePoint().equals("知识点甲")).findFirst().orElseThrow();
        assertThat(jia.averageScore()).isCloseTo(5.0, within(0.001));
        assertThat(jia.weakPointCount()).isEqualTo(1);
        assertThat(jia.memoryCount()).isEqualTo(2);

        InterviewQuestionWeight yi = weights.stream()
                .filter(w -> w.knowledgePoint().equals("知识点乙")).findFirst().orElseThrow();
        assertThat(yi.weakPointCount()).isEqualTo(0);
        assertThat(yi.weight()).isEqualTo(0.6, within(0.001)); // avg 10 → base 0.6
    }

    @Test
    void nullScoreExcludedFromAverageButCountedInMemoryCount() {
        List<InterviewMemory> memories = List.of(
                new InterviewMemory(1L, 1L, 1L, MemoryType.EXPERIENCE, "Docker容器", null,
                        "无分数的历史记忆"),
                memory("Docker容器", MemoryType.ANSWER_PATTERN, 8));

        List<InterviewQuestionWeight> weights = analyzer.analyze(memories);

        assertThat(weights).hasSize(1);
        assertThat(weights.get(0).memoryCount()).isEqualTo(2);
        assertThat(weights.get(0).averageScore()).isEqualTo(8); // null 分不进均值
        assertThat(weights.get(0).weakPointCount()).isEqualTo(0);
    }

    @Test
    void emptyOrNullInputReturnsEmptyList() {
        assertThat(analyzer.analyze(null)).isEmpty();
        assertThat(analyzer.analyze(List.of())).isEmpty();
    }

    private static InterviewMemory memory(String knowledgePoint, MemoryType type, Integer score) {
        return new InterviewMemory(1L, 1L, 1L, type, knowledgePoint, score,
                "历史记忆内容：" + knowledgePoint + "，得分 " + score + "。");
    }

}
