package com.interviewmind.vector;

import com.interviewmind.memory.InterviewMemoryStore;
import com.interviewmind.memory.dto.InterviewMemory;
import com.interviewmind.memory.dto.InterviewMemory.MemoryType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Spring AI PgVectorStore 出口：Document(text=Memory 语义文本, metadata=结构化过滤字段)
 * → EmbeddingModel → PgVectorStore。不手写 INSERT INTO vector，不算余弦相似度。
 * <p>
 * 调用位于 finish 事务之外（由 InterviewMemoryService 保证），Embedding 与向量写入
 * 绝不进入业务长事务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PgVectorMemoryStore implements InterviewMemoryStore {

    private final VectorStore vectorStore;

    @Override
    public void deleteBySessionId(Long sessionId) {
        if (sessionId == null) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        // 仅删除 metadata 中 sessionId 精确匹配的模拟面试记忆；录音复盘记忆不含此字段。
        vectorStore.delete(new org.springframework.ai.vectorstore.filter.FilterExpressionBuilder()
                .eq("sessionId", sessionId)
                .build());
        log.info("PGVector 模拟面试记忆删除完成: sessionId={}", sessionId);
    }

    @Override
    public void saveAll(List<InterviewMemory> memories) {
        List<Document> documents = memories.stream().map(PgVectorMemoryStore::toDocument).toList();
        long startMillis = System.currentTimeMillis();
        // EmbeddingModel 调用 + PgVector INSERT 都发生在 add 内部，均在数据库事务之外
        vectorStore.add(documents);
        log.info("PGVector 写入完成: count={}, cost={}ms", documents.size(),
                System.currentTimeMillis() - startMillis);
    }

    @Override
    public List<InterviewMemory> search(String query, Long resumeId, int topK) {
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(topK)
                // 接受全部相似度：范围已由 metadata filter 限定，不用相似度阈值再裁剪
                .similarityThresholdAll()
                // 统一个人 RAG：resumeId 为 null 时不加简历过滤，录音经验与模拟面试经验共同参与检索。
                .build();
        long startMillis = System.currentTimeMillis();
        List<Document> documents = vectorStore.similaritySearch(request);
        List<InterviewMemory> memories = documents.stream()
                .map(PgVectorMemoryStore::fromDocument)
                .filter(Objects::nonNull)
                .toList();
        log.info("PGVector 统一 RAG 检索完成: count={}, cost={}ms",
                memories.size(), System.currentTimeMillis() - startMillis);
        return memories;
    }

    /** Document → Memory：metadata 结构化字段还原；残缺或非法文档跳过（返回 null）。 */
    static InterviewMemory fromDocument(Document document) {
        Map<String, Object> metadata = document.getMetadata();
        Long resumeId = asLong(metadata.get("resumeId"));
        Long sessionId = asLong(metadata.get("sessionId"));
        String memoryType = asText(metadata.get("memoryType"));
        String content = document.getText();
        // 录音复盘记忆没有模拟面试 sessionId；resumeId 才是个人知识库隔离边界。
        if (memoryType == null || content == null) {
            return null;
        }
        MemoryType type;
        try {
            type = MemoryType.valueOf(memoryType);
        } catch (IllegalArgumentException e) {
            return null;
        }
        return new InterviewMemory(
                resumeId,
                sessionId,
                asLong(metadata.get("questionId")),
                type,
                asText(metadata.get("knowledgePoint")),
                asInteger(metadata.get("score")),
                content);
    }

    private static Long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private static Integer asInteger(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static String asText(Object value) {
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    /**
     * Memory → Document：content 作为语义检索文本；
     * resumeId/sessionId/questionId/memoryType/knowledgePoint/score 进 metadata 供过滤。
     */
    static Document toDocument(InterviewMemory memory) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("resumeId", memory.resumeId());
        metadata.put("sessionId", memory.sessionId());
        if (memory.questionId() != null) {
            metadata.put("questionId", memory.questionId());
        }
        metadata.put("memoryType", memory.memoryType().name());
        if (memory.knowledgePoint() != null) {
            metadata.put("knowledgePoint", memory.knowledgePoint());
        }
        if (memory.score() != null) {
            metadata.put("score", memory.score());
        }
        return Document.builder()
                .id(UUID.randomUUID().toString())
                .text(memory.content())
                .metadata(metadata)
                .build();
    }

}
