package com.interviewmind.asr;

import lombok.Getter;

/**
 * ASR 模块专用异常。每个失败环节一个明确的 {@link Code}，不吞异常、不返回 null。
 * <p>
 * message 前缀为 code 名，便于日志检索；FAILED 任务通过 taskId/remoteCode/remoteMessage
 * 完整保留 DashScope 侧失败信息（例如 DECODE_ERROR）。
 */
@Getter
public class AsrException extends RuntimeException {

    public enum Code {
        /** 从 MinIO 获取原始音频失败（对象不存在/读取异常） */
        ASR_STORAGE_FAILED,
        /** DashScope 临时文件上传失败（getPolicy 或 OSS 表单上传） */
        ASR_UPLOAD_FAILED,
        /** FileTrans 任务提交失败（HTTP 错误或响应缺少 task_id） */
        ASR_TASK_SUBMIT_FAILED,
        /** 任务执行 FAILED（保留 task_id + code + message） */
        ASR_TASK_FAILED,
        /** 任务查询失败（HTTP 错误或响应结构异常） */
        ASR_TASK_QUERY_FAILED,
        /** 轮询超过 poll-timeout-ms */
        ASR_TIMEOUT,
        /** transcription_url 下载失败 */
        ASR_RESULT_DOWNLOAD_FAILED,
        /** 转写结果 JSON 解析失败或结构缺失 */
        ASR_RESULT_PARSE_FAILED
    }

    private final Code code;
    private final String taskId;
    private final String remoteCode;
    private final String remoteMessage;

    public AsrException(Code code, String message) {
        this(code, null, null, null, message, null);
    }

    public AsrException(Code code, String message, Throwable cause) {
        this(code, null, null, null, message, cause);
    }

    public AsrException(Code code, String taskId, String remoteCode, String remoteMessage, String message) {
        this(code, taskId, remoteCode, remoteMessage, message, null);
    }

    public AsrException(Code code, String taskId, String remoteCode, String remoteMessage,
                        String message, Throwable cause) {
        super(composeMessage(code, taskId, remoteCode, remoteMessage, message), cause);
        this.code = code;
        this.taskId = taskId;
        this.remoteCode = remoteCode;
        this.remoteMessage = remoteMessage;
    }

    private static String composeMessage(Code code, String taskId, String remoteCode,
                                         String remoteMessage, String message) {
        StringBuilder sb = new StringBuilder(code.name()).append(": ").append(message == null ? "" : message);
        if (taskId != null && !taskId.isBlank()) {
            sb.append(" (task_id=").append(taskId).append(')');
        }
        if (remoteCode != null && !remoteCode.isBlank()) {
            sb.append(" (code=").append(remoteCode).append(')');
        }
        if (remoteMessage != null && !remoteMessage.isBlank()) {
            sb.append(" (message=").append(remoteMessage).append(')');
        }
        return sb.toString();
    }

}
