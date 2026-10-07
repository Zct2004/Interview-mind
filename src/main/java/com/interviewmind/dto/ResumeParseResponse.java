package com.interviewmind.dto;

/**
 * POST /api/resumes/{id}/parse 响应。
 */
public record ResumeParseResponse(
        Long resumeId,
        Long profileId,
        String status) {
}
