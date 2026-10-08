package com.interviewmind.dto;

/**
 * POST /api/interviews 响应：sessionId + 第一题。
 */
public record InterviewStartResponse(
        Long sessionId,
        String status,
        Integer totalQuestions,
        InterviewCurrentQuestionResponse firstQuestion) {
}
