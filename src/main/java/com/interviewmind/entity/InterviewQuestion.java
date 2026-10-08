package com.interviewmind.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 本次面试使用的题目快照。面试开始时由 AI 生成 20 道题一次性写入，
 * 会话期间不重新生成，保证题目固定。
 */
@Getter
@Setter
@TableName("interview_question")
public class InterviewQuestion {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("session_id")
    private Long sessionId;

    @TableField("question_no")
    private Integer questionNo;

    @TableField("question")
    private String question;

    /** TECHNICAL / PRINCIPLE / PROJECT / SCENARIO */
    @TableField("type")
    private String type;

    /** EASY / MEDIUM / HARD */
    @TableField("difficulty")
    private String difficulty;

    @TableField("knowledge_point")
    private String knowledgePoint;

    /** 出题依据（引用简历事实），面试过程中不返回给前端 */
    @TableField("reason")
    private String reason;

    /** 参考答案，仅 AI 整体评价阶段使用，面试过程中不返回给前端 */
    @TableField("reference_answer")
    private String referenceAnswer;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;

}
