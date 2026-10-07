package com.interviewmind.ai.dto;

/**
 * LLM 输出模型：工作经历条目。
 */
public record WorkExperienceItem(
        String company,
        String position,
        String startDate,
        String endDate,
        String description) {
}
