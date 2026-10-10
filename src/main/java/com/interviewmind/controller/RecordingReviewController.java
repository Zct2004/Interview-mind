package com.interviewmind.controller;

import com.interviewmind.common.ApiResponse;
import com.interviewmind.entity.RecordingReview;
import com.interviewmind.entity.RecordingReviewQuestion;
import com.interviewmind.dto.RecordingLibraryItem;
import com.interviewmind.service.RecordingReviewService;
import com.interviewmind.service.RecordingUploadService;
import org.springframework.web.multipart.MultipartFile;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/recording-reviews")
@RequiredArgsConstructor
public class RecordingReviewController {
    private final RecordingReviewService reviewService;
    private final RecordingUploadService uploadService;

    /** 上传用户本地录音到 MinIO，返回后续复盘所需的 objectKey。 */
    @PostMapping("/upload")
    public ResponseEntity<ApiResponse<RecordingUploadService.RecordingUploadResponse>> upload(
            @RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(ApiResponse.success(uploadService.upload(file)));
    }

    /** 从 MinIO 读取录音，执行 ASR 与 LLM 结构化复盘；ASR 原文仅在内存中传递。 */
    @PostMapping("/analyze")
    public ResponseEntity<ApiResponse<RecordingReview>> analyze(@RequestParam String objectKey) {
        return ResponseEntity.ok(ApiResponse.success(reviewService.analyzeSavedTranscript(objectKey)));
    }

    /** 手动/失败后重试向量索引；仅此接口会调用 Embedding。 */
    @PostMapping("/{reviewId}/index")
    public ResponseEntity<ApiResponse<RecordingReview>> index(
            @PathVariable Long reviewId,
            @RequestParam(required = false) Long resumeId) {
        return ResponseEntity.ok(ApiResponse.success(reviewService.index(reviewId, resumeId)));
    }

    /** 将当前简历所有已完成的录音复盘批量加入同一份个人 RAG 知识库。 */
    @PostMapping("/index-all")
    public ResponseEntity<ApiResponse<RecordingReviewService.BulkIndexResult>> indexAll(
            @RequestParam Long resumeId) {
        return ResponseEntity.ok(ApiResponse.success(reviewService.indexAll(resumeId)));
    }

    /** 列出历史复盘，方便重新打开已有记录并加入个人知识库，不需要重复执行 ASR/LLM。 */
    @GetMapping
    public ResponseEntity<ApiResponse<List<RecordingLibraryItem>>> list() {
        return ResponseEntity.ok(ApiResponse.success(reviewService.list()));
    }

    @GetMapping("/{reviewId}")
    public ResponseEntity<ApiResponse<RecordingReviewDetail>> get(@PathVariable Long reviewId) {
        RecordingReview review = reviewService.getById(reviewId);
        if (review == null) {
            return ResponseEntity.status(404).body(ApiResponse.error(404, "录音复盘记录不存在"));
        }
        return ResponseEntity.ok(ApiResponse.success(
                new RecordingReviewDetail(review, reviewService.getQuestions(reviewId))));
    }

    public record RecordingReviewDetail(RecordingReview review, List<RecordingReviewQuestion> questions) {}
}
