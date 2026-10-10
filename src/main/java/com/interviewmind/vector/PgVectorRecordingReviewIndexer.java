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
    public void index(RecordingReview review, List<RecordingReviewQuestion> questions) {
        if (review.getResumeId() == null) {
            throw new IllegalArgumentException("录音复盘必须关联 resumeId 后才能进入个人 RAG 知识库");
        }
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

    private static void addDocument(List<Document> documents, RecordingReview review, String kind,
                                    String content, Integer questionNo, String memoryType) {
        addDocument(documents, review, kind, content, questionNo, memoryType, null);
    }

    private static void addDocument(List<Document> documents, RecordingReview review, String kind,
                                    String content, Integer questionNo, String memoryType,
                                    String knowledgePoint) {
        if (content == null || content.isBlank()) return;
        Long reviewId = review.getId();
        String stableKey = "recording-review:" + reviewId + ":" + kind + ":" +
                (questionNo == null ? "summary" : questionNo);
        String id = UUID.nameUUIDFromBytes(stableKey.getBytes(StandardCharsets.UTF_8)).toString();
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("sourceType", "RECORDING_REVIEW");
        metadata.put("sourceId", reviewId);
        metadata.put("objectKey", review.getObjectKey());
        metadata.put("documentType", kind);
        metadata.put("resumeId", review.getResumeId());
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
