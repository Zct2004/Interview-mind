package com.interviewmind.dto;

import java.time.LocalDateTime;

public record ResumeListItemResponse(
        Long resumeId,
        Long fileId,
        String originalName,
        Long fileSize,
        String contentType,
        String status,
        LocalDateTime createdAt) {
}
