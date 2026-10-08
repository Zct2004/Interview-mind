package com.interviewmind.dto;

import java.util.List;

/**
 * 单道题的 AI 评价（finish 响应中返回）。
 */
public record InterviewQuestionEvaluationResponse(
        Integer questionNo,
        Integer score,
        String analysis,
        List<String> weakPoints,
        List<String> suggestions) {
}
