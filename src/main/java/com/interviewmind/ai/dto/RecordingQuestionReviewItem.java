package com.interviewmind.ai.dto;

import java.util.List;

/** 从录音中识别出的单个面试问题及回答复盘。 */
public record RecordingQuestionReviewItem(
        Integer questionNo,
        String question,
        String questionType,
        List<String> knowledgePoints,
        String interviewerFollowUp,
        String candidateAnswer,
        String answerSummary,
        List<String> answerStrengths,
        List<String> missingPoints,
        String referenceAnswer,
        String improvedAnswer,
        String evidence,
        String confidence) {
}
