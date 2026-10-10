package com.interviewmind.asr.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * 提交任务与查询任务共用的响应信封（真实 FAILED 响应结构已核实：
 * code/message 位于 output 级；成功路径官方文档为 output.result.transcription_url、
 * 探测代码用过 output.results[0]，两种变体均保留并由客户端归一化）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TaskResponse(
        @JsonProperty("request_id") String requestId,
        @JsonProperty("output") Output output) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Output(
            @JsonProperty("task_id") String taskId,
            @JsonProperty("task_status") String taskStatus,
            @JsonProperty("code") String code,
            @JsonProperty("message") String message,
            @JsonProperty("results") List<TaskResult> results,
            /** 官方文档变体：对象 {transcription_url}，也兼容直接给字符串，故用 Object 承接 */
            @JsonProperty("result") Object result) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TaskResult(
            @JsonProperty("subtask_status") String subtaskStatus,
            @JsonProperty("code") String code,
            @JsonProperty("message") String message,
            @JsonProperty("transcription_url") String transcriptionUrl) {
    }

    /** 提取 output.result 变体中的 transcription_url：对象或字符串两种形态 */
    public static String extractResultUrl(Object result) {
        if (result == null) {
            return null;
        }
        if (result instanceof String text) {
            return text.isBlank() ? null : text;
        }
        if (result instanceof Map<?, ?> map) {
            Object value = map.get("transcription_url");
            if (value instanceof String text && !text.isBlank()) {
                return text;
            }
        }
        return null;
    }

}
