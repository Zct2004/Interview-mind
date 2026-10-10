package com.interviewmind.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 历史录音中识别出的逐题复盘元数据。JSON 数组字段以 JSON 文本保存。 */
@Getter
@Setter
@TableName("recording_review_question")
public class RecordingReviewQuestion {
    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("review_id")
    private Long reviewId;
    @TableField("question_no")
    private Integer questionNo;
    private String question;
    @TableField("question_type")
    private String questionType;
    @TableField("knowledge_points")
    private String knowledgePoints;
    @TableField("interviewer_follow_up")
    private String interviewerFollowUp;
    @TableField("candidate_answer")
    private String candidateAnswer;
    @TableField("answer_summary")
    private String answerSummary;
    @TableField("answer_strengths")
    private String answerStrengths;
    @TableField("missing_points")
    private String missingPoints;
    @TableField("reference_answer")
    private String referenceAnswer;
    @TableField("improved_answer")
    private String improvedAnswer;
    private String evidence;
    private String confidence;
    @TableField("created_at")
    private LocalDateTime createdAt;
}
