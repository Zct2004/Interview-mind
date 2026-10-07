package com.interviewmind.dto;

import tools.jackson.databind.JsonNode;

import java.time.LocalDateTime;

/**
 * GET /api/resumes/{id}/profile 响应，不直接暴露 Entity。
 * JSONB 字段以 JsonNode 返回，保证响应中是原生 JSON 数组而非转义字符串。
 */
public record ResumeProfileResponse(
        Long resumeId,
        Long profileId,
        String name,
        String phone,
        String email,
        String location,
        String summary,
        JsonNode education,
        JsonNode workExperience,
        JsonNode projectExperience,
        JsonNode skills,
        String rawText,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
