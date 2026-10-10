package com.interviewmind.controller;

import com.interviewmind.asr.AsrService;
import com.interviewmind.asr.dto.AsrResult;
import com.interviewmind.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 手动触发 MinIO 音频对象的 ASR 转写；不持久化转写文本，不修改或删除原始音频。 */
@RestController
@RequestMapping("/api/asr")
@RequiredArgsConstructor
public class AsrController {

    private final AsrService asrService;

    /**
     * POST /api/asr/transcriptions?objectKey=audio/recording.m4a
     * objectKey 必须是 MinIO bucket 内已经存在的对象键，而不是本地文件路径。
     */
    @PostMapping("/transcriptions")
    public ResponseEntity<ApiResponse<AsrResult>> transcribe(
            @RequestParam("objectKey") String objectKey) {
        if (objectKey.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(400, "objectKey 不能为空"));
        }
        AsrResult result = asrService.transcribe(objectKey);
        return ResponseEntity.ok(ApiResponse.success(result));
    }
}
