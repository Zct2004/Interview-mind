package com.interviewmind.ai.dto;

import java.util.List;

/**
 * LLM 输出模型：单道题的评价。只用于 AI 调用与结果传递，不是数据库 Entity。
 *
 * @param questionNo 题号 1-20
 * @param score      0-10 整数
 * @param analysis   对该回答的点评
 * @param weakPoints 薄弱点字符串数组
 * @param suggestions 改进建议字符串数组
 */
public record QuestionEvaluationItem(
        Integer questionNo,
        Integer score,
        String analysis,
        List<String> weakPoints,
        List<String> suggestions) {
}
