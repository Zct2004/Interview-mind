package com.interviewmind.dto;

import com.interviewmind.entity.RecordingReview;

import java.time.LocalDateTime;

/** 持久化音频清单项；review 为空表示文件已上传但尚未生成报告。 */
public record RecordingLibraryItem(
        Long fileId,
        String objectKey,
        String originalName,
        Long fileSize,
        String contentType,
        LocalDateTime uploadedAt,
        RecordingReview review) {
}
