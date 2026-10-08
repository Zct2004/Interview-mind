package com.interviewmind.rag;

import com.interviewmind.memory.dto.InterviewMemory;
import com.interviewmind.memory.dto.InterviewMemory.MemoryType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把检索到的 Interview Memory 按 knowledgePoint 聚合，产出动态出题权重。
 * <p>
 * 规则：
 * <ul>
 *   <li>只统计 knowledgePoint 非空的记忆；空 knowledgePoint 直接跳过，不抛异常</li>
 *   <li>averageScore = 该知识点所有非空 score 的算术平均（score 钳制 0-10）</li>
 *   <li>weakPointCount = 其中 WEAK_POINT 类型的条数</li>
 *   <li>每个 knowledgePoint 恰好一条权重记录，weight 只计算一次（不做重复放大）</li>
 *   <li>全部在 Java 内存中临时计算，不落库、不建汇总表</li>
 * </ul>
 * 输出按 weight 降序（同分按知识点名升序），保证日志与 Prompt 拼装顺序稳定。
 */
@Slf4j
@Component
public class KnowledgePointAnalyzer {

    /**
     * @param memories 检索命中的历史记忆（可为 null / 空）
     * @return 每个知识点一条 {@link InterviewQuestionWeight}，按 weight 降序
     */
    public List<InterviewQuestionWeight> analyze(List<InterviewMemory> memories) {
        if (memories == null || memories.isEmpty()) {
            return List.of();
        }

        Map<String, List<InterviewMemory>> byKnowledgePoint = new LinkedHashMap<>();
        for (InterviewMemory memory : memories) {
            if (memory == null) {
                continue;
            }
            String knowledgePoint = memory.knowledgePoint();
            if (knowledgePoint == null || knowledgePoint.isBlank()) {
                continue; // 空知识点不参与聚合，不抛异常
            }
            byKnowledgePoint.computeIfAbsent(knowledgePoint, key -> new ArrayList<>()).add(memory);
        }

        List<InterviewQuestionWeight> weights = new ArrayList<>(byKnowledgePoint.size());
        for (Map.Entry<String, List<InterviewMemory>> entry : byKnowledgePoint.entrySet()) {
            List<InterviewMemory> group = entry.getValue();
            long weakPointCount = group.stream()
                    .filter(memory -> memory.memoryType() == MemoryType.WEAK_POINT)
                    .count();
            InterviewQuestionWeight weight = InterviewQuestionWeight.of(
                    entry.getKey(),
                    averageScore(group),
                    (int) weakPointCount,
                    group.size());
            weights.add(weight);
            log.info("Knowledge point analysis: knowledgePoint={}, averageScore={}, "
                            + "weakPointCount={}, memoryCount={}, weight={}",
                    weight.knowledgePoint(),
                    String.format("%.2f", weight.averageScore()),
                    weight.weakPointCount(),
                    weight.memoryCount(),
                    String.format("%.2f", weight.weight()));
        }

        weights.sort(Comparator
                .comparingDouble(InterviewQuestionWeight::weight).reversed()
                .thenComparing(InterviewQuestionWeight::knowledgePoint));
        return List.copyOf(weights);
    }

    /** 非空 score 的算术平均；该知识点没有有效分数时返回中性分。 */
    private static double averageScore(List<InterviewMemory> group) {
        double sum = 0;
        int scored = 0;
        for (InterviewMemory memory : group) {
            Integer score = memory.score();
            if (score != null) {
                sum += Math.max(0, Math.min(10, score));
                scored++;
            }
        }
        return scored == 0 ? InterviewQuestionWeight.NEUTRAL_SCORE : sum / scored;
    }

}
