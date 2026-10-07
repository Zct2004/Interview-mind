package com.interviewmind.ai.dto;

/**
 * LLM 输出模型：项目经历条目。
 */
public record ProjectExperienceItem(
        String name,
        String role,
        String description) {
}
