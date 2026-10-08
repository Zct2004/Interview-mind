package com.interviewmind.dto;

/**
 * 面试过程中的“当前题目”视图。
 * 故意不包含 referenceAnswer 与 reason，模拟面试过程中前端拿不到参考答案。
 */
public record InterviewCurrentQuestionResponse(
        Long sessionId,
        Integer questionNo,
        Integer totalQuestions,
        String question,
        String type,
        String difficulty,
        String knowledgePoint) {
}
