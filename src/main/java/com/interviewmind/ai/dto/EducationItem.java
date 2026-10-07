package com.interviewmind.ai.dto;

/**
 * LLM 输出模型：教育经历条目。
 */
public record EducationItem(
        String school,
        String degree,
        String major,
        String startDate,
        String endDate) {
}
