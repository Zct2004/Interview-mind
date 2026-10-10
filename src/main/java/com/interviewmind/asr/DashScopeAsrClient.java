package com.interviewmind.asr;

import com.interviewmind.asr.dto.AsrResult;
import com.interviewmind.asr.dto.AsrTaskStatus;

import java.nio.file.Path;

/**
 * DashScope 异步 FileTrans 的 HTTP 客户端：只负责单次 HTTP 交互与响应归一化，
 * 不做轮询（轮询节奏由 {@link AsrService} 按 AsrProperties 控制）。
 * <p>
 * API Key 仅注入本实现，且只出现在 DashScope 域请求的 Authorization 头；
 * 不发送到 OSS 上传域与 transcription_url 下载域，绝不写日志。
 */
public interface DashScopeAsrClient {

    /**
     * 官方临时上传：getPolicy → OSS 表单上传（文件字段最后）→ oss:// 临时地址（48h）。
     *
     * @param audioFile 本地音频文件（由调用方负责生命周期）
     * @param objectKey 原始对象键，仅用于推导内容类型
     */
    String uploadTempFile(Path audioFile, String objectKey);

    /**
     * 提交 FileTrans 异步任务（X-DashScope-Async + X-DashScope-OssResourceResolve）。
     *
     * @return task_id
     */
    String submitTask(String ossUrl);

    /**
     * 单次查询任务状态，归一化 output.results[] 与 output.result 两种变体。
     */
    AsrTaskStatus queryTask(String taskId);

    /**
     * 下载 transcription_url 指向的转写结果 JSON 原文（不带 Authorization）。
     */
    String downloadTranscription(String transcriptionUrl);

    /**
     * 解析转写结果 JSON → {@link AsrResult}；结构缺失按 ASR_RESULT_PARSE_FAILED 失败。
     */
    AsrResult parseTranscription(String transcriptionJson, String taskId);

}
