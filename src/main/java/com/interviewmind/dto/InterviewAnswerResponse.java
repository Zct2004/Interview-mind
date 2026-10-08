package com.interviewmind.dto;

/**
 * POST /api/interviews/{sessionId}/answers 响应。
 * 最后一题提交后 interviewFinished=true 且 nextQuestion=null，
 * 前端再调用 POST /api/interviews/{sessionId}/finish 结束面试。
 */
public record InterviewAnswerResponse(
        Long sessionId,
        boolean interviewFinished,
        InterviewCurrentQuestionResponse nextQuestion) {
}
