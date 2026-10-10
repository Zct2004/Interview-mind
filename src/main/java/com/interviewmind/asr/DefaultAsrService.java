package com.interviewmind.asr;

import com.interviewmind.asr.dto.AsrResult;
import com.interviewmind.asr.dto.AsrTaskStatus;
import com.interviewmind.config.AsrProperties;
import com.interviewmind.storage.minio.ObjectStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * ASR 编排实现：MinIO 下载本地副本 → DashScope 临时上传 → 提交 → 轮询 → 下载解析。
 * <p>
 * 红线：MinIO/ASR 全部外部 IO 不进任何数据库事务（本类不接触数据库）；
 * MinIO 原始对象只读不删；本地临时文件 try/finally 保证成功/失败/超时都清理；
 * 轮询受 poll-interval-ms / poll-timeout-ms 约束，绝不无限轮询。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultAsrService implements AsrService {

    private static final String STATUS_SUCCEEDED = "SUCCEEDED";
    private static final String STATUS_FAILED = "FAILED";
    private static final int MAX_ERROR_MESSAGE_LENGTH = 300;

    private final ObjectStorageService objectStorage;
    private final DashScopeAsrClient asrClient;
    private final AsrProperties properties;

    @Override
    public AsrResult transcribe(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException("objectKey 不能为空");
        }
        Path tempFile = null;
        try {
            try {
                tempFile = Files.createTempFile("asr-", tempSuffix(objectKey));
            } catch (java.io.IOException e) {
                throw new AsrException(AsrException.Code.ASR_STORAGE_FAILED,
                        "创建本地临时音频文件失败", e);
            }
            copyFromStorage(objectKey, tempFile);
            String ossUrl = asrClient.uploadTempFile(tempFile, objectKey);
            String taskId = asrClient.submitTask(ossUrl);
            String transcriptionUrl = awaitTranscriptionUrl(taskId);
            String transcriptionJson = asrClient.downloadTranscription(transcriptionUrl);
            AsrResult result = asrClient.parseTranscription(transcriptionJson, taskId);
            log.info("ASR 转写完成 objectKey={} taskId={} transcriptChars={}",
                    objectKey, taskId, result.text() == null ? 0 : result.text().length());
            return result;
        } finally {
            cleanupQuietly(tempFile);
        }
    }

    private void copyFromStorage(String objectKey, Path target) {
        try (InputStream in = objectStorage.download(objectKey)) {
            if (in == null) {
                throw new AsrException(AsrException.Code.ASR_STORAGE_FAILED,
                        "对象不存在或不可读: " + objectKey);
            }
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (AsrException e) {
            throw e;
        } catch (Exception e) {
            log.error("ASR 音频下载失败 objectKey={}", objectKey, e);
            throw new AsrException(AsrException.Code.ASR_STORAGE_FAILED,
                    "从对象存储获取音频失败: " + objectKey + "（"
                            + errorMessage(e) + "）", e);
        }
    }

    /**
     * 轮询直到 SUCCEEDED（返回 transcription_url）/ FAILED（抛出，保留 task_id+code+message）/
     * 超时（抛 ASR_TIMEOUT）。
     */
    private String awaitTranscriptionUrl(String taskId) {
        long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(properties.getPollTimeoutMs(), 1L));
        long deadline = System.nanoTime() + timeoutNanos;
        String lastStatus = "UNKNOWN";
        while (true) {
            AsrTaskStatus status = asrClient.queryTask(taskId);
            lastStatus = status.status() == null ? "UNKNOWN" : status.status();
            log.info("ASR 任务状态 taskId={} status={}", taskId, lastStatus);

            if (STATUS_SUCCEEDED.equals(lastStatus)) {
                if (status.transcriptionUrl() == null || status.transcriptionUrl().isBlank()) {
                    throw new AsrException(AsrException.Code.ASR_TASK_QUERY_FAILED,
                            taskId, status.code(), status.message(),
                            "任务 SUCCEEDED 但响应缺少 transcription_url");
                }
                return status.transcriptionUrl();
            }
            if (STATUS_FAILED.equals(lastStatus)) {
                log.error("ASR 任务失败 taskId={} code={} message={}",
                        taskId, status.code(), status.message());
                throw new AsrException(AsrException.Code.ASR_TASK_FAILED,
                        taskId, status.code(), status.message(), "任务执行失败");
            }

            if (System.nanoTime() - deadline >= 0) {
                log.error("ASR 轮询超时 taskId={} timeoutMs={} lastStatus={}",
                        taskId, properties.getPollTimeoutMs(), lastStatus);
                throw new AsrException(AsrException.Code.ASR_TIMEOUT,
                        taskId, null, null,
                        "任务轮询超时 timeoutMs=" + properties.getPollTimeoutMs()
                                + ", lastStatus=" + lastStatus);
            }
            sleepInterval(taskId);
        }
    }

    private void sleepInterval(String taskId) {
        try {
            Thread.sleep(Math.max(properties.getPollIntervalMs(), 1L));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AsrException(AsrException.Code.ASR_TIMEOUT,
                    taskId, null, null, "轮询等待被中断（线程中断标志已恢复）", e);
        }
    }

    /** finally 中执行：清理失败只记 warn，绝不抛出以掩盖原始异常 */
    private void cleanupQuietly(Path tempFile) {
        if (tempFile == null) {
            return;
        }
        try {
            Files.deleteIfExists(tempFile);
        } catch (Exception e) {
            log.warn("ASR 本地临时文件清理失败 path={}", tempFile, e);
        }
    }

    private String tempSuffix(String objectKey) {
        int dot = objectKey.lastIndexOf('.');
        if (dot < 0 || dot == objectKey.length() - 1) {
            return ".bin";
        }
        String ext = objectKey.substring(dot + 1).toLowerCase(Locale.ROOT);
        return ext.matches("[a-z0-9]{1,10}") ? "." + ext : ".bin";
    }

    private String errorMessage(Throwable e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        return message.length() > MAX_ERROR_MESSAGE_LENGTH
                ? message.substring(0, MAX_ERROR_MESSAGE_LENGTH) + "..."
                : message;
    }

}
