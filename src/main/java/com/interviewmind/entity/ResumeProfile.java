package com.interviewmind.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.interviewmind.common.JsonbTypeHandler;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 简历解析结果，一个 resume 最多对应一条（resume_id 唯一）。
 * raw_text 为 PDFBox 提取的原始文本；结构化字段由 Spring AI 抽取；
 * JSONB 列以 JSON 字符串形式保存，由 {@link com.interviewmind.common.JsonbTypeHandler} 写入。
 */
@Getter
@Setter
@TableName("resume_profile")
public class ResumeProfile {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("resume_id")
    private Long resumeId;

    // AI 可能把上一次的非 null 字段解析为 null，updateStrategy=ALWAYS 保证整体覆盖不残留旧值
    @TableField(value = "name", updateStrategy = FieldStrategy.ALWAYS)
    private String name;

    @TableField(value = "phone", updateStrategy = FieldStrategy.ALWAYS)
    private String phone;

    @TableField(value = "email", updateStrategy = FieldStrategy.ALWAYS)
    private String email;

    @TableField(value = "location", updateStrategy = FieldStrategy.ALWAYS)
    private String location;

    @TableField(value = "summary", updateStrategy = FieldStrategy.ALWAYS)
    private String summary;

    @TableField(value = "education", typeHandler = JsonbTypeHandler.class)
    private String education;

    @TableField(value = "work_experience", typeHandler = JsonbTypeHandler.class)
    private String workExperience;

    @TableField(value = "project_experience", typeHandler = JsonbTypeHandler.class)
    private String projectExperience;

    @TableField(value = "skills", typeHandler = JsonbTypeHandler.class)
    private String skills;

    @TableField("raw_text")
    private String rawText;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;

}
