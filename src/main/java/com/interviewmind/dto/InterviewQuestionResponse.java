package com.interviewmind.dto;

import com.interviewmind.ai.dto.QuestionItem;

import java.util.List;

/**
 * POST /api/resumes/{id}/interview-questions 响应。
 * 本阶段 AI 生成结果不落库，直接返回；题目条目复用 AI 输出结构。
 *
 * @param questions 恰好 20 道面试题
 */
public record InterviewQuestionResponse(
        Long resumeId,
        List<QuestionItem> questions) {
}
