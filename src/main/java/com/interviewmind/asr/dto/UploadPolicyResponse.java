package com.interviewmind.asr.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * GET /api/v1/uploads?action=getPolicy 实时返回结构（字段集已按真实响应核实）。
 * getPolicy 目前不返回 callback/key：表单 key 由 upload_dir + 临时文件名拼出。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UploadPolicyResponse(
        @JsonProperty("request_id") String requestId,
        @JsonProperty("data") UploadPolicyData data) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UploadPolicyData(
            @JsonProperty("policy") String policy,
            @JsonProperty("signature") String signature,
            @JsonProperty("upload_dir") String uploadDir,
            @JsonProperty("upload_host") String uploadHost,
            @JsonProperty("oss_access_key_id") String ossAccessKeyId,
            @JsonProperty("x_oss_object_acl") String xOssObjectAcl,
            @JsonProperty("x_oss_forbid_overwrite") String xOssForbidOverwrite,
            @JsonProperty("callback") String callback,
            @JsonProperty("expire_in_seconds") Integer expireInSeconds,
            @JsonProperty("max_file_size_mb") Integer maxFileSizeMb) {
    }

}
