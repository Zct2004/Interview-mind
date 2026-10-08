package com.interviewmind.rag;

import com.interviewmind.memory.dto.InterviewMemory;

import java.util.List;

/**
 * RAG 读取结果：历史记忆 + 按知识点聚合的动态出题权重，
 * 用于从 InterviewQuestionService 传递给出题 AI（拼入用户消息）。
 * <p>
 * 只读 DTO；无历史数据或 RAG 检索失败时为 {@link #empty()}，
 * 出题流程回退为纯简历模式，不报错。
 *
 * @param weights           每个知识点恰好一条权重记录（weight 降序）
 * @param relevantMemories  本次检索命中的历史记忆原文
 */
public record InterviewRagContext(
        List<InterviewQuestionWeight> weights,
        List<InterviewMemory> relevantMemories) {

    public InterviewRagContext {
        weights = weights == null ? List.of() : List.copyOf(weights);
        relevantMemories = relevantMemories == null ? List.of() : List.copyOf(relevantMemories);
    }

    /** 无历史 / RAG 失败的空上下文（第一轮面试即此形态）。 */
    public static InterviewRagContext empty() {
        return new InterviewRagContext(List.of(), List.of());
    }

    /** 是否拿到了可用的历史权重（false = 按纯简历正常出题）。 */
    public boolean hasHistory() {
        return !weights.isEmpty();
    }

}
