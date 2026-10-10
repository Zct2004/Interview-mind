package com.interviewmind.vector;

import com.interviewmind.entity.RecordingReview;
import com.interviewmind.entity.RecordingReviewQuestion;
import com.interviewmind.memory.RecordingReviewVectorIndexer;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 将 PostgreSQL 中已持久化的结构化复盘映射为稳定 ID 的 RAG 文档。 */
@Component
public class PgVectorRecordingReviewIndexer implements RecordingReviewVectorIndexer {
    private final VectorStore vectorStore;

    public PgVectorRecordingReviewIndexer(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public boolean exists(RecordingReview review) {
        if (review == null || review.getId() == null) return false;
        // 使用来源元数据限定查询，确认 pgvector 中确实存在这条录音复盘，而不是相信 PostgreSQL 业务表的旧状态。
        List<Document> matches = vectorStore.similaritySearch(
                org.springframework.ai.vectorstore.SearchRequest.builder()
                        .query("录音复盘经验 " + review.getId())
                        .topK(1)
                        .similarityThresholdAll()
                        .filterExpression("sourceType == 'RECORDING_REVIEW' && sourceId == " + review.getId())
                        .build());
        return matches != null && !matches.isEmpty();
    }

    @Override
    public void index(RecordingReview review, List<RecordingReviewQuestion> questions) {
        List<Document> documents = new ArrayList<>();
        String summary = join(
                review.getSummary(),
                labeled("优势", review.getStrengths()),
                labeled("薄弱点", review.getWeakPoints()),
                labeled("改进建议", review.getSuggestions()),
                labeled("重点知识", review.getKeyTopics()));
        addDocument(documents, review, "summary", summary, null, "INTERVIEW_SUMMARY");
        if (questions != null) {
            for (RecordingReviewQuestion question : questions) {
                String content = join(
                        labeled("问题", question.getQuestion()),
                        labeled("候选人回答", question.getCandidateAnswer()),
                        labeled("回答总结", question.getAnswerSummary()),
                        labeled("优势", question.getAnswerStrengths()),
                        labeled("待补充", question.getMissingPoints()),
                        labeled("参考答案", question.getReferenceAnswer()),
                        labeled("改进答案", question.getImprovedAnswer()),
                        labeled("知识点", question.getKnowledgePoints()));
                String knowledgePoint = normalizeKnowledgePoints(question.getKnowledgePoints());
                boolean hasMissingPoints = question.getMissingPoints() != null
                        && !question.getMissingPoints().isBlank()
                        && !"[]".equals(question.getMissingPoints().trim());
                addDocument(documents, review, "question", content,
                        question.getQuestionNo(), hasMissingPoints ? "WEAK_POINT" : "EXPERIENCE",
                        knowledgePoint);
            }
        }
        if (!documents.isEmpty()) {
            vectorStore.add(documents);
        }
    }

    @Override
    public void delete(RecordingReview review, List<RecordingReviewQuestion> questions) {
        if (review == null || review.getId() == null) return;
        List<String> ids = new ArrayList<>();
        ids.add(documentId(review.getId(), "summary", null));
        if (questions != null) {
            for (RecordingReviewQuestion question : questions) {
                if (question.getQuestionNo() != null) {
                    ids.add(documentId(review.getId(), "question", question.getQuestionNo()));
                }
            }
        }
        vectorStore.delete(ids);
    }

    private static String documentId(Long reviewId, String kind, Integer questionNo) {
        String stableKey = "recording-review:" + reviewId + ":" + kind + ":" +
                (questionNo == null ? "summary" : questionNo);
        return UUID.nameUUIDFromBytes(stableKey.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static void addDocument(List<Document> documents, RecordingReview review, String kind,
                                    String content, Integer questionNo, String memoryType) {
        addDocument(documents, review, kind, content, questionNo, memoryType, null);
    }

    private static void addDocument(List<Document> documents, RecordingReview review, String kind,
                                    String content, Integer questionNo, String memoryType,
                                    String knowledgePoint) {
        if (content == null || content.isBlank()) return;
        Long reviewId = review.getId();
        String id = documentId(reviewId, kind, questionNo);
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("sourceType", "RECORDING_REVIEW");
        metadata.put("sourceId", reviewId);
        if (review.getObjectKey() != null && !review.getObjectKey().isBlank()) {
            metadata.put("objectKey", review.getObjectKey());
        }
        metadata.put("documentType", kind);
        // 简历关联是可选的：统一 RAG 中的录音经验不依赖 resumeId。
        if (review.getResumeId() != null) metadata.put("resumeId", review.getResumeId());
        metadata.put("memoryType", memoryType);
        if (knowledgePoint != null && !knowledgePoint.isBlank()) metadata.put("knowledgePoint", knowledgePoint);
        if (questionNo != null) metadata.put("questionNo", questionNo);
        documents.add(Document.builder().id(id).text(content).metadata(metadata).build());
    }

    /** knowledge_points 以 JSON 数组文本保存；压成简洁标签供权重聚合使用。 */
    private static String normalizeKnowledgePoints(String json) {
        if (json == null || json.isBlank() || "[]".equals(json.trim())) return null;
        String value = json.trim();
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        value = value.replace("\\\"", "").replace("\"", "")
                .replace(",", "、").trim();
        return value.isBlank() ? null : value;
    }

    private static String labeled(String label, String value) {
        return value == null || value.isBlank() || "null".equalsIgnoreCase(value.trim())
                ? null : label + "：" + value;
    }

    private static String join(String... values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (value != null && !value.isBlank() && !value.endsWith(":null")) {
                if (!result.isEmpty()) result.append('\n');
                result.append(value);
            }
        }
        return result.toString();
    }
}
