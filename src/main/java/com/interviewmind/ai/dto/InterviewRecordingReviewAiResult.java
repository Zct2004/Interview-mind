package com.interviewmind.ai.dto;

import java.util.List;

/** LLM 对历史面试录音的结构化复盘结果；仅用于 AI 调用与结果传递。 */
public record InterviewRecordingReviewAiResult(
        String summary,
        List<String> strengths,
        List<String> weakPoints,
        List<String> suggestions,
        List<String> keyTopics,
        List<RecordingQuestionReviewItem> questions) {
}
