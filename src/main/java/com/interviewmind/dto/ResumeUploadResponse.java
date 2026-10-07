package com.interviewmind.dto;

import java.time.LocalDateTime;

public record ResumeUploadResponse(
        Long resumeId,
        Long fileId,
        String originalName,
        Long fileSize,
        String status,
        LocalDateTime createdAt) {
}
