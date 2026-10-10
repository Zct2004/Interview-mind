package com.interviewmind.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RecordingTranscriptImportRequest(
        @NotBlank String objectKey,
        String taskId,
        @NotBlank @Size(max = 200000) String transcriptText
) {
}
