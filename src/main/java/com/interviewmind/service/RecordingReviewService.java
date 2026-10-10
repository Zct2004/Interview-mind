package com.interviewmind.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.interviewmind.ai.InterviewRecordingReviewAiParser;
import com.interviewmind.dto.RecordingLibraryItem;
import com.interviewmind.entity.FileMetadata;
import com.interviewmind.entity.RecordingReview;
import com.interviewmind.entity.RecordingReviewQuestion;
import com.interviewmind.asr.AsrService;
import com.interviewmind.asr.dto.AsrResult;
import com.interviewmind.mapper.RecordingReviewMapper;
import com.interviewmind.mapper.RecordingReviewQuestionMapper;
import com.interviewmind.mapper.FileMetadataMapper;
import com.interviewmind.mapper.ResumeMapper;
import com.interviewmind.memory.RecordingReviewVectorIndexer;
import com.interviewmind.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** LLM 分析、PostgreSQL 元数据事务、向量索引分阶段执行，避免长事务包住模型请求。 */
@Service
@RequiredArgsConstructor
public class RecordingReviewService {
    private final AsrService asrService;
    private final RecordingReviewMapper reviewMapper;
    private final FileMetadataMapper fileMetadataMapper;
    private final RecordingReviewQuestionMapper questionMapper;
    private final InterviewRecordingReviewAiParser parser;
    private final RecordingReviewPersistenceService persistenceService;
    private final RecordingReviewVectorIndexer vectorIndexer;
    private final ResumeMapper resumeMapper;

    /** 直接读取 MinIO 音频进行 ASR，转写文本仅在内存中传给 LLM，不写入数据库。 */
    public RecordingReview analyzeSavedTranscript(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException("objectKey 不能为空");
        }
        RecordingReview existing = reviewMapper.selectOne(new LambdaQueryWrapper<RecordingReview>()
                .eq(RecordingReview::getObjectKey, objectKey.trim()));
        if (existing != null && "COMPLETED".equals(existing.getProcessingStatus())) {
            // 已有报告时直接复用，避免用户重复点击或刷新后重复消耗 ASR/LLM 额度。
            return existing;
        }

        AsrResult asrResult = asrService.transcribe(objectKey.trim());
        if (asrResult.text() == null || asrResult.text().isBlank()) {
            throw new IllegalStateException("ASR 返回空转写文本，未调用 LLM");
        }
        // ASR 文本只保存在当前请求内存中；数据库只保存 LLM 结构化结果。
        RecordingReview review = persistenceService.save(objectKey.trim(), parser.analyze(asrResult.text()));
        // 本轮只验证结构化 PostgreSQL 存储，不自动调用 Embedding。
        return review;
    }
    /** 独立索引入口：首次索引必须绑定所属简历，确保知识只进入对应个人 RAG。 */
    public RecordingReview index(Long reviewId) {
        return index(reviewId, null);
    }

    public RecordingReview index(Long reviewId, Long resumeId) {
        RecordingReview review = reviewMapper.selectById(reviewId);
        if (review == null) throw new BusinessException(404, "录音复盘记录不存在: " + reviewId);
        if (resumeId != null) {
            if (resumeMapper.selectById(resumeId) == null) {
                throw new BusinessException(404, "简历不存在: " + resumeId);
            }
            if (review.getResumeId() != null && !review.getResumeId().equals(resumeId)) {
                throw new BusinessException(409, "该录音复盘已关联其他简历，禁止跨知识库移动");
            }
            review.setResumeId(resumeId);
        }
        if (review.getResumeId() == null) {
            throw new BusinessException(400, "首次建立 RAG 索引时必须提供 resumeId，例如 ?resumeId=1");
        }
        // 先持久化关联关系；即使 Embedding 失败，重试时也能保持同一知识库归属。
        review.setUpdatedAt(LocalDateTime.now());
        reviewMapper.updateById(review);
        List<RecordingReviewQuestion> questions = getQuestions(reviewId);
        try {
            vectorIndexer.index(review, questions);
            review.setVectorStatus("INDEXED");
            review.setLastVectorError(null);
        } catch (Exception e) {
            review.setVectorStatus("FAILED");
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            review.setLastVectorError(message.substring(0, Math.min(500, message.length())));
            review.setUpdatedAt(LocalDateTime.now());
            reviewMapper.updateById(review);
            throw new IllegalStateException("结构化复盘已保存，但向量索引失败；可重试该索引接口", e);
        }
        review.setUpdatedAt(LocalDateTime.now());
        reviewMapper.updateById(review);
        return review;
    }

    /**
     * 将当前简历下所有已完成的录音复盘批量加入统一个人 RAG。
     * 已索引的记录跳过；已绑定其他简历的记录绝不跨库移动。
     */
    public BulkIndexResult indexAll(Long resumeId) {
        if (resumeId == null || resumeMapper.selectById(resumeId) == null) {
            throw new BusinessException(404, "简历不存在: " + resumeId);
        }
        List<RecordingReview> reviews = reviewMapper.selectList(
                new LambdaQueryWrapper<RecordingReview>()
                        .eq(RecordingReview::getProcessingStatus, "COMPLETED")
                        .orderByAsc(RecordingReview::getId));
        int indexed = 0;
        int alreadyIndexed = 0;
        int skippedOtherResume = 0;
        int failed = 0;
        int noReport = (int) list().stream().filter(item -> item.review() == null).count();
        for (RecordingReview review : reviews) {
            if (review.getResumeId() != null && !review.getResumeId().equals(resumeId)) {
                skippedOtherResume++;
                continue;
            }
            if (resumeId.equals(review.getResumeId()) && "INDEXED".equals(review.getVectorStatus())) {
                alreadyIndexed++;
                continue;
            }
            try {
                index(review.getId(), resumeId);
                indexed++;
            } catch (Exception e) {
                failed++;
            }
        }
        return new BulkIndexResult(resumeId, indexed, alreadyIndexed, skippedOtherResume, failed, noReport);
    }

    public record BulkIndexResult(
            Long resumeId, int indexed, int alreadyIndexed, int skippedOtherResume, int failed, int noReport) {}

    /**
     * 持久化音频资料库：列出已上传音频（无报告也显示）并关联复盘报告。
     * 兼容历史上只存在 recording_review、尚未写入 file_metadata 的录音。
     */
    public List<RecordingLibraryItem> list() {
        List<RecordingReview> reviews = reviewMapper.selectList(
                new LambdaQueryWrapper<RecordingReview>().orderByDesc(RecordingReview::getUpdatedAt));
        Map<String, RecordingReview> reviewByObjectKey = new HashMap<>();
        Set<String> includedKeys = new HashSet<>();
        for (RecordingReview review : reviews) reviewByObjectKey.put(review.getObjectKey(), review);

        List<RecordingLibraryItem> result = new ArrayList<>();
        List<FileMetadata> files = fileMetadataMapper.selectList(
                new LambdaQueryWrapper<FileMetadata>().orderByDesc(FileMetadata::getCreatedAt));
        for (FileMetadata file : files) {
            if (!isAudioFile(file.getOriginalName()) && !isAudioFile(file.getObjectKey())) continue;
            RecordingReview review = reviewByObjectKey.get(file.getObjectKey());
            result.add(new RecordingLibraryItem(file.getId(), file.getObjectKey(), file.getOriginalName(),
                    file.getFileSize(), file.getContentType(), file.getCreatedAt(), review));
            includedKeys.add(file.getObjectKey());
        }

        // 老数据如果只有复盘表记录，也必须能在前端继续打开报告。
        for (RecordingReview review : reviews) {
            if (includedKeys.contains(review.getObjectKey())) continue;
            result.add(new RecordingLibraryItem(null, review.getObjectKey(), review.getObjectKey(),
                    null, null, review.getCreatedAt(), review));
        }
        result.sort((left, right) -> {
            if (left.uploadedAt() == null) return 1;
            if (right.uploadedAt() == null) return -1;
            return right.uploadedAt().compareTo(left.uploadedAt());
        });
        return result;
    }

    private boolean isAudioFile(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return List.of(".m4a", ".mp3", ".wav", ".aac", ".flac", ".ogg", ".opus", ".webm", ".mp4")
                .stream().anyMatch(lower::endsWith);
    }

    public RecordingReview getById(Long id) {
        return reviewMapper.selectById(id);
    }

    public List<RecordingReviewQuestion> getQuestions(Long reviewId) {
        return questionMapper.selectList(new LambdaQueryWrapper<RecordingReviewQuestion>()
                .eq(RecordingReviewQuestion::getReviewId, reviewId)
                .orderByAsc(RecordingReviewQuestion::getQuestionNo));
    }
}
