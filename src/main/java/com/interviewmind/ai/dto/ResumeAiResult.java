package com.interviewmind.ai.dto;

import java.util.List;

/**
 * LLM 输出模型：简历结构化结果。只用于 AI 调用与结果传递，不是数据库 Entity。
 *
 * @param education          教育经历，没有则空数组
 * @param workExperience     工作经历，没有则空数组
 * @param projectExperience  项目经历，没有则空数组
 * @param skills             技能列表，没有则空数组
 */
public record ResumeAiResult(
        String name,
        String phone,
        String email,
        String location,
        String summary,
        List<EducationItem> education,
        List<WorkExperienceItem> workExperience,
        List<ProjectExperienceItem> projectExperience,
        List<String> skills) {
}
