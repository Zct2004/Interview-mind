package com.interviewmind.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 历史录音经 LLM 结构化复盘后的 PostgreSQL 元数据。JSON 字段以 JSON 文本保存。 */
@Getter
@Setter
@TableName("recording_review")
public class RecordingReview {
    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("object_key")
    private String objectKey;
    @TableField("resume_id")
    private Long resumeId;
    private String summary;
    private String strengths;
    @TableField("weak_points")
    private String weakPoints;
    private String suggestions;
    @TableField("key_topics")
    private String keyTopics;
    @TableField("processing_status")
    private String processingStatus;
    @TableField("vector_status")
    private String vectorStatus;
    @TableField("last_vector_error")
    private String lastVectorError;
    @TableField("created_at")
    private LocalDateTime createdAt;
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
