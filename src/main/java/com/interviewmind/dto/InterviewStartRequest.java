package com.interviewmind.dto;

/**
 * POST /api/interviews 请求。
 */
public record InterviewStartRequest(
        Long resumeId) {
}
