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
 * 用户对某道题的回答（question_id 唯一）。
 * 刚提交时 score/analysis/weak_points/suggestions 为 NULL，
 * 整场面试结束后由 AI 整体评价一次性补充。
 */
@Getter
@Setter
@TableName("interview_answer")
public class InterviewAnswer {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("question_id")
    private Long questionId;

    @TableField("answer_text")
    private String answerText;

    /** 0-10，AI 整体评价后填充 */
    @TableField("score")
    private Integer score;

    /** AI 对本题回答的点评，整体评价后填充 */
    @TableField("analysis")
    private String analysis;

    /** JSONB 字符串数组，整体评价后填充 */
    @TableField(value = "weak_points", typeHandler = JsonbTypeHandler.class)
    private String weakPoints;

    /** JSONB 字符串数组，整体评价后填充 */
    @TableField(value = "suggestions", typeHandler = JsonbTypeHandler.class)
    private String suggestions;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;

}
