package com.interviewmind.dto;

import java.time.LocalDateTime;

public record ResumeDetailResponse(
        Long resumeId,
        Long fileId,
        String originalName,
        String objectKey,
        String bucketName,
        Long fileSize,
        String contentType,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
