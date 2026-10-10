package com.interviewmind.asr.dto;

/**
 * 项目内部 ASR 结果：taskId 便于全链路追踪，text 为转写全文。
 * <p>
 * 完整转写文本不写日志（日志只记长度），由调用方决定后续用途。
 */
public record AsrResult(String taskId, String text) {
}
