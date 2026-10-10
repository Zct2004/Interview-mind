package com.interviewmind.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.interviewmind.ai.dto.InterviewRecordingReviewAiResult;
import com.interviewmind.ai.dto.RecordingQuestionReviewItem;
import com.interviewmind.entity.RecordingReview;
import com.interviewmind.entity.RecordingReviewQuestion;
import com.interviewmind.mapper.RecordingReviewMapper;
import com.interviewmind.mapper.RecordingReviewQuestionMapper;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** 仅负责 PostgreSQL 结构化元数据的原子保存；不调用 LLM 或 Embedding。 */
@Service
@RequiredArgsConstructor
public class RecordingReviewPersistenceService {
    private final RecordingReviewMapper reviewMapper;
    private final RecordingReviewQuestionMapper questionMapper;
    private final ObjectMapper objectMapper;

    @Transactional
    public RecordingReview save(String objectKey, InterviewRecordingReviewAiResult parsed) {
        LocalDateTime now = LocalDateTime.now();
        RecordingReview review = reviewMapper.selectOne(
                new LambdaQueryWrapper<RecordingReview>()
                        .eq(RecordingReview::getObjectKey, objectKey));
        if (review == null) {
            review = new RecordingReview();
            review.setObjectKey(objectKey);
            review.setCreatedAt(now);
        }
        review.setSummary(parsed.summary());
        review.setStrengths(toJson(parsed.strengths()));
        review.setWeakPoints(toJson(parsed.weakPoints()));
        review.setSuggestions(toJson(parsed.suggestions()));
        review.setKeyTopics(toJson(parsed.keyTopics()));
        review.setProcessingStatus("COMPLETED");
        review.setVectorStatus("PENDING");
        review.setLastVectorError(null);
        review.setUpdatedAt(now);
        if (review.getId() == null) reviewMapper.insert(review);
        else reviewMapper.updateById(review);

        questionMapper.delete(new LambdaQueryWrapper<RecordingReviewQuestion>()
                .eq(RecordingReviewQuestion::getReviewId, review.getId()));
        if (parsed.questions() != null) {
            for (RecordingQuestionReviewItem item : parsed.questions()) {
                questionMapper.insert(toEntity(review.getId(), item, now));
            }
        }
        return review;
    }

    private RecordingReviewQuestion toEntity(Long reviewId, RecordingQuestionReviewItem item, LocalDateTime now) {
        RecordingReviewQuestion row = new RecordingReviewQuestion();
        row.setReviewId(reviewId);
        row.setQuestionNo(item.questionNo());
        row.setQuestion(item.question());
        row.setQuestionType(item.questionType());
        row.setKnowledgePoints(toJson(item.knowledgePoints()));
        row.setInterviewerFollowUp(item.interviewerFollowUp());
        row.setCandidateAnswer(item.candidateAnswer());
        row.setAnswerSummary(item.answerSummary());
        row.setAnswerStrengths(toJson(item.answerStrengths()));
        row.setMissingPoints(toJson(item.missingPoints()));
        row.setReferenceAnswer(item.referenceAnswer());
        row.setImprovedAnswer(item.improvedAnswer());
        row.setEvidence(item.evidence());
        row.setConfidence(item.confidence());
        row.setCreatedAt(now);
        return row;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (JacksonException e) {
            throw new IllegalStateException("结构化复盘 JSON 序列化失败", e);
        }
    }
}
