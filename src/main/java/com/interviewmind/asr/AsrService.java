package com.interviewmind.asr;

import com.interviewmind.asr.dto.AsrResult;

/**
 * ASR 业务接口：MinIO 对象 → DashScope FileTrans 异步转写 → 文本。
 * <p>
 * 只做编排：下载副本、临时上传、提交、轮询、下载解析；不触碰数据库，
 * 不删除 MinIO 原始对象，本地临时文件无论成败都在 finally 清理。
 */
public interface AsrService {

    /**
     * 转写 MinIO 中的音频对象。
     *
     * @param objectKey MinIO 对象键（bucket 内路径）
     * @return 转写结果（taskId + 全文）
     * @throws AsrException 任一环节失败，code 区分失败类型；FAILED 任务保留 task_id/code/message
     */
    AsrResult transcribe(String objectKey);

}
