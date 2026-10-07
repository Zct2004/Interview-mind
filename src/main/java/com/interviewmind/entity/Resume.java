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
@TableName("resume")
public class Resume {

    /** 已上传，未解析 */
    public static final String STATUS_UPLOADED = "UPLOADED";
    /** 解析中 */
    public static final String STATUS_PARSING = "PARSING";
    /** 解析成功 */
    public static final String STATUS_PARSED = "PARSED";
    /** 解析失败 */
    public static final String STATUS_PARSE_FAILED = "PARSE_FAILED";

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("file_id")
    private Long fileId;

    @TableField("status")
    private String status;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;

}
