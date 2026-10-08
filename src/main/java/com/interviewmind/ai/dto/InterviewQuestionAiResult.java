package com.interviewmind.ai.dto;

import java.util.List;

/**
 * LLM 输出模型：一轮面试题生成结果，严格 20 道。
 * 只用于 AI 调用与结果传递，不是数据库 Entity。
 *
 * @param questions 20 道面试题，按难度分布 EASY 5 / MEDIUM 10 / HARD 5
 */
public record InterviewQuestionAiResult(
        List<QuestionItem> questions) {
}
