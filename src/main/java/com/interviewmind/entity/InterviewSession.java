package com.interviewmind.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 一次模拟面试会话。current_question 落库，刷新页面后可由
 * GET /api/interviews/{sessionId}/current-question 恢复进度。
 */
@Getter
@Setter
@TableName("interview_session")
public class InterviewSession {

    /** 已创建未开始（本阶段直接以 IN_PROGRESS 落库，保留该状态以备扩展） */
    public static final String STATUS_CREATED = "CREATED";
    /** 进行中 */
    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    /** 已完成（AI 总结已生成） */
    public static final String STATUS_COMPLETED = "COMPLETED";
    /** 失败（保留状态，本阶段未使用） */
    public static final String STATUS_FAILED = "FAILED";

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("resume_id")
    private Long resumeId;

    @TableField("status")
    private String status;

    @TableField("total_questions")
    private Integer totalQuestions;

    /** 当前待作答题号；全部作答后为 totalQuestions + 1 */
    @TableField("current_question")
    private Integer currentQuestion;

    @TableField("started_at")
    private LocalDateTime startedAt;

    @TableField("finished_at")
    private LocalDateTime finishedAt;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;

}
