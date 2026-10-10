package com.interviewmind.dto;

import java.time.LocalDateTime;

/** 历史模拟面试列表项。 */
public record InterviewHistoryItemResponse(Long sessionId, Long resumeId, String resumeName,
        String status, Integer totalQuestions, Integer totalScore, String summary,
        LocalDateTime startedAt, LocalDateTime finishedAt) {
}
