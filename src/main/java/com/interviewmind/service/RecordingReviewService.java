package com.interviewmind.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.interviewmind.ai.InterviewRecordingReviewAiParser;
import com.interviewmind.dto.RecordingLibraryItem;
import com.interviewmind.entity.FileMetadata;
import com.interviewmind.entity.RecordingReview;
import com.interviewmind.entity.RecordingReviewQuestion;
import com.interviewmind.entity.Resume;
import com.interviewmind.asr.AsrService;
import com.interviewmind.asr.dto.AsrResult;
import com.interviewmind.mapper.RecordingReviewMapper;
import com.interviewmind.mapper.RecordingReviewQuestionMapper;
import com.interviewmind.mapper.FileMetadataMapper;
import com.interviewmind.mapper.ResumeMapper;
import com.interviewmind.memory.RecordingReviewVectorIndexer;
import com.interviewmind.exception.BusinessException;
import com.interviewmind.storage.minio.ObjectStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** LLM 分析、PostgreSQL 元数据事务、向量索引分阶段执行，避免长事务包住模型请求。 */
@Slf4j
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
    private final ObjectStorageService objectStorageService;

    private final Object operationMonitor = new Object();
    private final Set<String> activeAnalyses = ConcurrentHashMap.newKeySet();
    private final Set<Long> activeIndexes = ConcurrentHashMap.newKeySet();
    private final Set<String> deletingObjectKeys = ConcurrentHashMap.newKeySet();

    /** 直接读取 MinIO 音频进行 ASR，转写文本仅在内存中传给 LLM，不写入数据库。 */
    public RecordingReview analyzeSavedTranscript(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException("objectKey 不能为空");
        }
        String key = objectKey.trim();
        synchronized (operationMonitor) {
            if (deletingObjectKeys.contains(key)) {
                throw new BusinessException(409, "该录音正在删除，暂时不能分析");
            }
            if (!activeAnalyses.add(key)) {
                throw new BusinessException(409, "该录音正在分析中，请勿重复提交以免重复消耗 AI 额度");
            }
        }
        try {
            RecordingReview existing = reviewMapper.selectOne(new LambdaQueryWrapper<RecordingReview>()
                    .eq(RecordingReview::getObjectKey, key));
            if (existing != null && "COMPLETED".equals(existing.getProcessingStatus())) {
                return existing;
            }
            AsrResult asrResult = asrService.transcribe(key);
            if (asrResult.text() == null || asrResult.text().isBlank()) {
                throw new IllegalStateException("ASR 返回空转写文本，未调用 LLM");
            }
            RecordingReview saved = persistenceService.save(key, parser.analyze(asrResult.text()));
            // 复盘元数据和题目已提交后，自动写入统一 RAG；索引失败不丢失已保存的复盘，可通过同步按钮补齐。
            try {
                index(saved.getId());
            } catch (Exception indexError) {
                log.error("新录音复盘已保存，但自动写入统一 RAG 失败: reviewId={}, objectKey={}",
                        saved.getId(), key, indexError);
            }
            return saved;
        } finally {
            synchronized (operationMonitor) {
                activeAnalyses.remove(key);
            }
        }
    }
    /** 将录音复盘加入统一个人 RAG，不要求也不修改简历关联。 */
    public RecordingReview index(Long reviewId) {
        return index(reviewId, null);
    }

    /** resumeId 参数仅为兼容旧接口保留，不再用于限制或改变 RAG 归属。 */
    public RecordingReview index(Long reviewId, Long resumeId) {
        RecordingReview review = reviewMapper.selectById(reviewId);
        if (review == null) throw new BusinessException(404, "录音复盘记录不存在: " + reviewId);
        synchronized (operationMonitor) {
            if (deletingObjectKeys.contains(review.getObjectKey())) {
                throw new BusinessException(409, "该录音正在删除，暂时不能建立 RAG 索引");
            }
            if (activeAnalyses.contains(review.getObjectKey())) {
                throw new BusinessException(409, "该录音正在分析中，暂时不能建立 RAG 索引");
            }
            if (!activeIndexes.add(reviewId)) {
                throw new BusinessException(409, "该录音正在建立 RAG 索引，请勿重复提交");
            }
        }
        try {
            // 录音经验属于统一个人 RAG。resumeId 仅保留为历史来源元数据，不作为索引前置条件。

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
        } finally {
            synchronized (operationMonitor) {
                activeIndexes.remove(reviewId);
            }
        }
    }

    /** 将所有已完成的录音复盘补齐到统一个人 RAG，不按简历归属过滤。 */
    public BulkIndexResult indexAll() {
        List<RecordingReview> reviews = reviewMapper.selectList(
                new LambdaQueryWrapper<RecordingReview>()
                        .eq(RecordingReview::getProcessingStatus, "COMPLETED")
                        .orderByAsc(RecordingReview::getId));
        int indexed = 0;
        int alreadyIndexed = 0;
        int skippedOtherResume = 0;
        int failed = 0;
        int noReport = (int) list().stream().filter(item -> item.review() == null).count();
        log.info("开始补齐统一录音 RAG: completedReviews={}", reviews.size());
        for (RecordingReview review : reviews) {
            try {
                boolean existsInVectorStore = vectorIndexer.exists(review);
                log.info("录音 RAG 存在性检查: reviewId={}, sourceResumeId={}, exists={}",
                        review.getId(), review.getResumeId(), existsInVectorStore);
                if (existsInVectorStore) {
                    alreadyIndexed++;
                    continue;
                }
                // 向量不存在时，index() 从 PostgreSQL 读取复盘及题目元数据，重新调用 Embedding 并写入 pgvector。
                index(review.getId());
                indexed++;
                log.info("录音 RAG 补齐成功: reviewId={}, sourceResumeId={}", review.getId(), review.getResumeId());
            } catch (Exception e) {
                failed++;
                log.warn("录音复盘 RAG 补齐失败: reviewId={}, resumeId={}", review.getId(), review.getResumeId(), e);
            }
        }
        log.info("统一录音 RAG 批量补齐结束: indexed={}, alreadyIndexed={}, failed={}, noReport={}",
                indexed, alreadyIndexed, failed, noReport);
        return new BulkIndexResult(null, indexed, alreadyIndexed, skippedOtherResume, failed, noReport);
    }

    public record BulkIndexResult(
            Long resumeId, int indexed, int alreadyIndexed, int skippedOtherResume, int failed, int noReport) {}

    /** 删除录音原文件、复盘题目/报告以及仅属于该复盘的 RAG 文档。 */
    @Transactional(rollbackFor = Exception.class)
    public void deleteRecording(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new BusinessException(400, "objectKey 不能为空");
        }
        String key = objectKey.trim();
        FileMetadata metadata = fileMetadataMapper.selectOne(new LambdaQueryWrapper<FileMetadata>()
                .eq(FileMetadata::getObjectKey, key));
        RecordingReview review = reviewMapper.selectOne(new LambdaQueryWrapper<RecordingReview>()
                .eq(RecordingReview::getObjectKey, key));
        if (metadata == null && review == null) {
            throw new BusinessException(404, "录音不存在或已被删除");
        }
        synchronized (operationMonitor) {
            if (activeAnalyses.contains(key) || (review != null && activeIndexes.contains(review.getId()))) {
                throw new BusinessException(409, "录音正在分析或建立 RAG 索引，完成后才能删除");
            }
            if (!deletingObjectKeys.add(key)) {
                throw new BusinessException(409, "该录音正在删除，请勿重复操作");
            }
        }
        boolean deferDeleteMarkerRelease = false;
        try {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        releaseDeleteMarker(key);
                    }
                });
                deferDeleteMarkerRelease = true;
            }
            if (metadata != null && resumeMapper.selectCount(new LambdaQueryWrapper<Resume>()
                    .eq(Resume::getFileId, metadata.getId())) > 0) {
                throw new BusinessException(409, "该文件元数据仍被简历引用，为避免误删，已拒绝删除");
            }
            List<RecordingReviewQuestion> questions = review == null ? List.of() : getQuestions(review.getId());
            // 先在当前数据库事务内删除业务行，再清理外部资源；任一外部操作失败会回滚数据库删除。
            if (review != null) reviewMapper.deleteById(review.getId());
            if (metadata != null) fileMetadataMapper.deleteById(metadata.getId());
            if (review != null) vectorIndexer.delete(review, questions);
            objectStorageService.delete(key);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("录音删除未能完整完成: objectKey={}", key, e);
            throw new BusinessException(500, "删除录音未能完整完成，请检查 MinIO 与 PostgreSQL 状态后重试");
        } finally {
            if (!deferDeleteMarkerRelease) releaseDeleteMarker(key);
        }
    }

    private void releaseDeleteMarker(String objectKey) {
        synchronized (operationMonitor) {
            deletingObjectKeys.remove(objectKey);
        }
    }

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
