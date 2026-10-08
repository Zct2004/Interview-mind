package com.interviewmind.ai.dto;

/**
 * LLM 输出模型：单道面试题。只用于 AI 调用与结果传递，不是数据库 Entity。
 *
 * @param type             题型，约定值：TECHNICAL / PRINCIPLE / PROJECT / SCENARIO
 * @param difficulty       难度，约定值：EASY / MEDIUM / HARD
 * @param knowledgePoint   考察的知识点
 * @param reason           出题依据（引用简历中的事实）
 * @param referenceAnswer  参考答案要点
 */
public record QuestionItem(
        String question,
        String type,
        String difficulty,
        String knowledgePoint,
        String reason,
        String referenceAnswer) {
}
