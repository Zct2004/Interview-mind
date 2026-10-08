package com.interviewmind.dto;

/**
 * POST /api/interviews/{sessionId}/answers 请求。
 */
public record InterviewAnswerRequest(
        String answer) {
}
