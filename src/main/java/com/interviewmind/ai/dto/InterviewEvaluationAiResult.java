package com.interviewmind.ai.dto;

import java.util.List;

/**
 * LLM 输出模型：整场面试评价结果。
 * 只用于 AI 调用与结果传递，不是数据库 Entity。
 *
 * @param totalScore         0-100 总分（不合理时以后端计算为准）
 * @param summary            整场面试总评
 * @param strengths          整体优势字符串数组
 * @param weakPoints         整体薄弱点字符串数组
 * @param suggestions        整体建议字符串数组
 * @param questionEvaluations 20 条逐题评价，questionNo 1-20
 */
public record InterviewEvaluationAiResult(
        Integer totalScore,
        String summary,
        List<String> strengths,
        List<String> weakPoints,
        List<String> suggestions,
        List<QuestionEvaluationItem> questionEvaluations) {
}
