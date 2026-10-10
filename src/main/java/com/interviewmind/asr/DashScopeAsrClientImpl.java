package com.interviewmind.asr;

import com.interviewmind.asr.dto.AsrResult;
import com.interviewmind.asr.dto.AsrTaskStatus;
import com.interviewmind.asr.dto.TaskResponse;
import com.interviewmind.asr.dto.TranscriptionResult;
import com.interviewmind.asr.dto.UploadPolicyResponse;
import com.interviewmind.asr.dto.UploadPolicyResponse.UploadPolicyData;
import com.interviewmind.config.AsrProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * DashScope 异步 FileTrans 客户端（Spring 自带 RestClient，无额外 SDK）。
 * <p>
 * 流程与上一轮验证通过的探测链完全一致：
 * getPolicy → OSS 表单上传（key = upload_dir/临时文件名，文件字段最后）→ oss://dashscope-instant/...；
 * 提交 POST /api/v1/services/audio/asr/transcription（file_urls + 空 parameters）；
 * 查询 GET /api/v1/tasks/{task_id}。API Key 复用 spring.ai.openai.api-key。
 * <p>
 * 日志只记 objectKey/taskId/status，绝无 API Key、Authorization、完整转写文本与完整 Header。
 */
@Slf4j
@Service
public class DashScopeAsrClientImpl implements DashScopeAsrClient {

    private static final String GET_POLICY_PATH = "/api/v1/uploads";
    private static final String TRANSCRIPTION_PATH = "/api/v1/services/audio/asr/transcription";
    private static final String TASK_PATH = "/api/v1/tasks/";
    private static final String STATUS_SUCCEEDED = "SUCCEEDED";
    private static final int MAX_REMOTE_DETAIL_LENGTH = 500;

    private final RestClient restClient;
    private final AsrProperties properties;
    private final String apiKey;
    private final ObjectMapper objectMapper;

    public DashScopeAsrClientImpl(@Qualifier("asrRestClient") RestClient restClient,
                                  AsrProperties properties,
                                  @Value("${spring.ai.openai.api-key}") String apiKey,
                                  ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.properties = properties;
        this.apiKey = apiKey;
        this.objectMapper = objectMapper;
    }

    // ---------------------------------------------------------------- 上传

    @Override
    public String uploadTempFile(Path audioFile, String objectKey) {
        UploadPolicyData policy = fetchUploadPolicy();
        String filename = tempObjectName(objectKey);
        String ossKey = policy.uploadDir() + "/" + filename;
        postOssForm(policy, ossKey, filename, audioFile, objectKey);
        String ossUrl = "oss://" + ossKey;
        log.info("ASR 临时文件上传完成 objectKey={} ossKey={} sizeBytes={}",
                objectKey, ossKey, fileSize(audioFile));
        return ossUrl;
    }

    private UploadPolicyData fetchUploadPolicy() {
        String uri = UriComponentsBuilder.fromUriString(properties.getBaseUrl())
                .path(GET_POLICY_PATH)
                .queryParam("action", "getPolicy")
                .queryParam("model", properties.getModel())
                .toUriString();
        UploadPolicyResponse response;
        try {
            response = restClient.get()
                    .uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, bearer())
                    .retrieve()
                    .body(UploadPolicyResponse.class);
        } catch (RestClientResponseException e) {
            throw new AsrException(AsrException.Code.ASR_UPLOAD_FAILED,
                    "获取上传凭证失败 status=" + e.getStatusCode().value()
                            + " body=" + sanitize(truncate(e.getResponseBodyAsString())));
        } catch (RuntimeException e) {
            throw new AsrException(AsrException.Code.ASR_UPLOAD_FAILED,
                    "获取上传凭证请求异常: " + sanitize(truncate(e.getMessage())), e);
        }
        UploadPolicyData data = response == null ? null : response.data();
        if (data == null || isBlank(data.policy()) || isBlank(data.signature())
                || isBlank(data.uploadHost()) || isBlank(data.uploadDir())
                || isBlank(data.ossAccessKeyId())) {
            throw new AsrException(AsrException.Code.ASR_UPLOAD_FAILED,
                    "上传凭证响应缺少必填字段（policy/signature/upload_host/upload_dir/oss_access_key_id）");
        }
        return data;
    }

    /**
     * OSS 表单上传。字段与官方要求一致：OSSAccessKeyId / Signature / policy /
     * x-oss-* / key / callback（若 getPolicy 返回）/ success_action_status，文件字段放最后。
     * 不带 Authorization（API Key 不出 DashScope 域），不显式覆盖 Content-Type（由转换器生成 boundary）。
     */
    private void postOssForm(UploadPolicyData policy, String ossKey, String filename,
                             Path audioFile, String objectKey) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("OSSAccessKeyId", policy.ossAccessKeyId());
        builder.part("Signature", policy.signature());
        builder.part("policy", policy.policy());
        if (!isBlank(policy.xOssObjectAcl())) {
            builder.part("x-oss-object-acl", policy.xOssObjectAcl());
        }
        if (!isBlank(policy.xOssForbidOverwrite())) {
            builder.part("x-oss-forbid-overwrite", policy.xOssForbidOverwrite());
        }
        builder.part("key", ossKey);
        if (!isBlank(policy.callback())) {
            builder.part("callback", policy.callback());
        }
        builder.part("success_action_status", "200");
        // 文件字段放最后（官方表单要求）；Spring 7 的 part() 返回 PartBuilder，流式设置文件名与内容类型
        builder.part("file", new FileSystemResource(audioFile))
                .filename(filename)
                .contentType(MediaType.parseMediaType(contentTypeFor(objectKey)));
        try {
            restClient.post()
                    .uri(policy.uploadHost())
                    .body(builder.build())
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            throw new AsrException(AsrException.Code.ASR_UPLOAD_FAILED,
                    "OSS 表单上传失败 status=" + e.getStatusCode().value()
                            + " body=" + sanitize(truncate(e.getResponseBodyAsString())));
        } catch (RuntimeException e) {
            throw new AsrException(AsrException.Code.ASR_UPLOAD_FAILED,
                    "OSS 表单上传请求异常: " + sanitize(truncate(e.getMessage())), e);
        }
    }

    // ---------------------------------------------------------------- 提交

    @Override
    public String submitTask(String ossUrl) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", properties.getModel());
        payload.put("input", Map.of("file_urls", List.of(ossUrl)));
        payload.put("parameters", Map.of());
        String body;
        try {
            body = objectMapper.writeValueAsString(payload);
        } catch (RuntimeException e) {
            throw new AsrException(AsrException.Code.ASR_TASK_SUBMIT_FAILED,
                    "提交请求体序列化失败: " + sanitize(truncate(e.getMessage())), e);
        }
        TaskResponse response;
        try {
            response = restClient.post()
                    .uri(properties.getBaseUrl() + TRANSCRIPTION_PATH)
                    .header(HttpHeaders.AUTHORIZATION, bearer())
                    .header("X-DashScope-Async", "enable")
                    .header("X-DashScope-OssResourceResolve", "enable")
                    .contentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8))
                    .body(body)
                    .retrieve()
                    .body(TaskResponse.class);
        } catch (RestClientResponseException e) {
            throw new AsrException(AsrException.Code.ASR_TASK_SUBMIT_FAILED,
                    "任务提交失败 status=" + e.getStatusCode().value()
                            + " body=" + sanitize(truncate(e.getResponseBodyAsString())));
        } catch (RuntimeException e) {
            throw new AsrException(AsrException.Code.ASR_TASK_SUBMIT_FAILED,
                    "任务提交请求异常: " + sanitize(truncate(e.getMessage())), e);
        }
        String taskId = response == null || response.output() == null
                ? null : response.output().taskId();
        if (isBlank(taskId)) {
            String detail = response != null && response.output() != null
                    && (!isBlank(response.output().code()) || !isBlank(response.output().message()))
                    ? "：code=" + response.output().code() + ", message=" + response.output().message()
                    : "";
            throw new AsrException(AsrException.Code.ASR_TASK_SUBMIT_FAILED,
                    "提交响应缺少 task_id" + detail);
        }
        log.info("ASR 任务已提交 taskId={} status={}", taskId, response.output().taskStatus());
        return taskId;
    }

    // ---------------------------------------------------------------- 查询

    @Override
    public AsrTaskStatus queryTask(String taskId) {
        TaskResponse response;
        try {
            response = restClient.get()
                    .uri(properties.getBaseUrl() + TASK_PATH + taskId)
                    .header(HttpHeaders.AUTHORIZATION, bearer())
                    .retrieve()
                    .body(TaskResponse.class);
        } catch (RestClientResponseException e) {
            throw new AsrException(AsrException.Code.ASR_TASK_QUERY_FAILED,
                    "任务查询失败 task_id=" + taskId + " status=" + e.getStatusCode().value()
                            + " body=" + sanitize(truncate(e.getResponseBodyAsString())));
        } catch (RuntimeException e) {
            throw new AsrException(AsrException.Code.ASR_TASK_QUERY_FAILED,
                    "任务查询请求异常 task_id=" + taskId + ": " + sanitize(truncate(e.getMessage())), e);
        }
        if (response == null || response.output() == null) {
            throw new AsrException(AsrException.Code.ASR_TASK_QUERY_FAILED,
                    "任务查询响应缺少 output task_id=" + taskId);
        }
        TaskResponse.Output output = response.output();
        String status = output.taskStatus();
        String code = output.code();
        String message = output.message();
        String transcriptionUrl = null;

        TaskResponse.TaskResult first = output.results() == null || output.results().isEmpty()
                ? null : output.results().get(0);
        if (first != null && !isBlank(first.subtaskStatus()) && !STATUS_SUCCEEDED.equals(first.subtaskStatus())) {
            // 任务级 SUCCEEDED 但子任务失败：归一为 FAILED，保留子任务错误码
            status = "FAILED";
            if (isBlank(code)) {
                code = first.code();
            }
            if (isBlank(message)) {
                message = first.message();
            }
        } else if (STATUS_SUCCEEDED.equals(status)) {
            if (first != null && !isBlank(first.transcriptionUrl())) {
                transcriptionUrl = first.transcriptionUrl();
            } else {
                transcriptionUrl = TaskResponse.extractResultUrl(output.result());
            }
        }
        String normalizedTaskId = isBlank(output.taskId()) ? taskId : output.taskId();
        return new AsrTaskStatus(normalizedTaskId, status, code, message, transcriptionUrl);
    }

    // ---------------------------------------------------------------- 结果

    @Override
    public String downloadTranscription(String transcriptionUrl) {
        String body;
        try {
            body = restClient.get()
                    .uri(URI.create(transcriptionUrl))
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            throw new AsrException(AsrException.Code.ASR_RESULT_DOWNLOAD_FAILED,
                    "转写结果下载失败 status=" + e.getStatusCode().value()
                            + " body=" + sanitize(truncate(e.getResponseBodyAsString())));
        } catch (RuntimeException e) {
            throw new AsrException(AsrException.Code.ASR_RESULT_DOWNLOAD_FAILED,
                    "转写结果下载请求异常: " + sanitize(truncate(e.getMessage())), e);
        }
        if (isBlank(body)) {
            throw new AsrException(AsrException.Code.ASR_RESULT_DOWNLOAD_FAILED,
                    "转写结果下载内容为空");
        }
        return body;
    }

    @Override
    public AsrResult parseTranscription(String transcriptionJson, String taskId) {
        if (isBlank(transcriptionJson)) {
            throw new AsrException(AsrException.Code.ASR_RESULT_PARSE_FAILED,
                    "转写结果 JSON 为空 task_id=" + taskId);
        }
        TranscriptionResult result;
        try {
            result = objectMapper.readValue(transcriptionJson, TranscriptionResult.class);
        } catch (RuntimeException e) {
            throw new AsrException(AsrException.Code.ASR_RESULT_PARSE_FAILED,
                    "转写结果 JSON 解析失败 task_id=" + taskId
                            + ": " + sanitize(truncate(e.getMessage())), e);
        }
        if (result == null || result.transcripts() == null || result.transcripts().isEmpty()) {
            throw new AsrException(AsrException.Code.ASR_RESULT_PARSE_FAILED,
                    "转写结果缺少 transcripts task_id=" + taskId);
        }
        String text = result.transcripts().stream()
                .map(TranscriptionResult.Transcript::text)
                .filter(t -> t != null)
                .collect(Collectors.joining("\n"));
        return new AsrResult(taskId, text);
    }

    // ---------------------------------------------------------------- 工具

    private String bearer() {
        return "Bearer " + apiKey;
    }

    /** 上传对象名用 ASCII 随机名（与已验证探测一致），规避表单非 ASCII 边界问题；原对象名只用于取扩展名 */
    private String tempObjectName(String objectKey) {
        return "asr-" + UUID.randomUUID() + extension(objectKey);
    }

    private String extension(String objectKey) {
        int dot = objectKey.lastIndexOf('.');
        if (dot < 0 || dot == objectKey.length() - 1) {
            return ".bin";
        }
        String ext = objectKey.substring(dot + 1).toLowerCase(Locale.ROOT);
        return ext.matches("[a-z0-9]{1,10}") ? "." + ext : ".bin";
    }

    private String contentTypeFor(String objectKey) {
        String ext = extension(objectKey);
        return switch (ext) {
            case ".m4a", ".mp4" -> "audio/mp4";
            case ".mp3" -> "audio/mpeg";
            case ".wav" -> "audio/wav";
            case ".aac" -> "audio/aac";
            case ".amr" -> "audio/amr";
            case ".ogg" -> "audio/ogg";
            case ".flac" -> "audio/flac";
            default -> "application/octet-stream";
        };
    }

    private long fileSize(Path file) {
        try {
            return Files.size(file);
        } catch (Exception e) {
            return -1L;
        }
    }

    private String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() > MAX_REMOTE_DETAIL_LENGTH
                ? value.substring(0, MAX_REMOTE_DETAIL_LENGTH) + "..."
                : value;
    }

    /** 防御性日志/异常文本清洗：即便远端回显也绝不让 API Key 出现在消息里 */
    private String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "no detail";
        }
        if (apiKey != null && !apiKey.isBlank()) {
            return value.replace(apiKey, "***");
        }
        return value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

}
