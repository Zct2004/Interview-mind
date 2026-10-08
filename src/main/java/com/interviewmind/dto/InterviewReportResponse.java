package com.interviewmind.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 整场面试的 AI 报告（含逐题评价）。
 * sessionId/finishedAt 来自会话，其余字段与 interview_report 表对应，
 * 逐题评价存于 interview_answer 表、此处一并返回。
 * 后续 RAG 阶段将基于 sessionId/summary/strengths/weakPoints/suggestions
 * 构造 Interview Memory 文档。
 */
public record InterviewReportResponse(
        Long sessionId,
        Integer totalScore,
        String summary,
        List<String> strengths,
        List<String> weakPoints,
        List<String> suggestions,
        LocalDateTime finishedAt,
        List<InterviewQuestionEvaluationResponse> questionEvaluations) {
}
