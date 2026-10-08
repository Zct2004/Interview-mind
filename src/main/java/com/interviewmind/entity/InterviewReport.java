package com.interviewmind.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.interviewmind.common.JsonbTypeHandler;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 整场面试的 AI 总结报告（session_id 唯一）。
 * 后续 RAG 阶段基于本表构造 Interview Memory 文档。
 */
@Getter
@Setter
@TableName("interview_report")
public class InterviewReport {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("session_id")
    private Long sessionId;

    /** 0-100 */
    @TableField("total_score")
    private Integer totalScore;

    @TableField("summary")
    private String summary;

    /** JSONB 字符串数组 */
    @TableField(value = "strengths", typeHandler = JsonbTypeHandler.class)
    private String strengths;

    /** JSONB 字符串数组 */
    @TableField(value = "weak_points", typeHandler = JsonbTypeHandler.class)
    private String weakPoints;

    /** JSONB 字符串数组 */
    @TableField(value = "suggestions", typeHandler = JsonbTypeHandler.class)
    private String suggestions;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;

}
