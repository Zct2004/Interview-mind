package com.interviewmind.asr.dto;

/**
 * 归一化后的任务查询结果（DashScope 查询响应的两种变体在客户端内消化）。
 *
 * @param taskId           任务 ID
 * @param status           PENDING / RUNNING / SUCCEEDED / FAILED（子任务失败也归一为 FAILED）
 * @param code             失败错误码（如 DECODE_ERROR），可空
 * @param message          失败描述，可空
 * @param transcriptionUrl SUCCEEDED 时的转写结果 JSON 地址（24h 有效），其余状态为 null
 */
public record AsrTaskStatus(String taskId, String status, String code, String message, String transcriptionUrl) {
}
