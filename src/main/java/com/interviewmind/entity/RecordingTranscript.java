package com.interviewmind.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("recording_transcription")
public class RecordingTranscript {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("object_key")
    private String objectKey;

    @TableField("task_id")
    private String taskId;

    @TableField("transcript_text")
    private String transcriptText;

    @TableField("character_count")
    private Integer characterCount;

    /** ASR_SUCCESS 或 MANUAL_IMPORT */
    private String status;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
