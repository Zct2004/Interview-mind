package com.interviewmind.controller;

import com.interviewmind.common.ApiResponse;
import com.interviewmind.dto.RecordingTranscriptImportRequest;
import com.interviewmind.entity.RecordingTranscript;
import com.interviewmind.service.RecordingTranscriptService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/recording-transcripts")
@RequiredArgsConstructor
public class RecordingTranscriptController {

    private final RecordingTranscriptService transcriptService;

    /** 导入已有文本，不触发 ASR 或 LLM。 */
    @PostMapping("/import")
    public ResponseEntity<ApiResponse<RecordingTranscript>> importText(
            @Valid @RequestBody RecordingTranscriptImportRequest request) {
        return ResponseEntity.ok(ApiResponse.success(transcriptService.importText(request)));
    }

    /** 按 MinIO objectKey 读取已持久化文本，不触发模型。 */
    @GetMapping
    public ResponseEntity<ApiResponse<RecordingTranscript>> getByObjectKey(
            @RequestParam("objectKey") String objectKey) {
        RecordingTranscript result = transcriptService.getByObjectKey(objectKey);
        if (result == null) {
            return ResponseEntity.status(404)
                    .body(ApiResponse.error(404, "该音频对象尚无已保存的转写文本"));
        }
        return ResponseEntity.ok(ApiResponse.success(result));
    }
}
