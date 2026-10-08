package com.interviewmind.dto;

/**
 * POST /api/interviews/{sessionId}/finish 响应：会话状态 + 完整报告。
 */
public record InterviewFinishResponse(
        Long sessionId,
        String status,
        InterviewReportResponse report) {
}
