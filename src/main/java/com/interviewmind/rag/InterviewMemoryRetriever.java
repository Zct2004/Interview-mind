package com.interviewmind.rag;

import com.interviewmind.memory.InterviewMemoryStore;
import com.interviewmind.memory.dto.InterviewMemory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * RAG 读取入口：根据当前简历 resumeId 从 PGVector 语义检索历史 Interview Memory。
 * <p>
 * 链路：InterviewMemoryRetriever → InterviewMemoryStore → PgVectorMemoryStore
 * → Spring AI PgVectorStore.similaritySearch（一次查询 + metadata 过滤，禁止 N+1）。
 * 不新建 vector 表、不手写向量 SQL、不自算余弦相似度。
 * <p>
 * 故障隔离：本类不吞异常，由 InterviewQuestionService 统一兜底——
 * RAG 是增强能力，不是出题的硬依赖。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InterviewMemoryRetriever {

    /**
     * 第一版固定查询语义：与当前候选人技术栈相关的宽泛描述，
     * 一次 similaritySearch 取回 topK 后完全在 Java 内存中聚合。
     */
    static final String RETRIEVAL_QUERY = "Java Spring MySQL Redis MQ 项目技术能力 面试表现 薄弱点";

    /** 第一版检索条数上限 */
    static final int TOP_K = 30;

    private final InterviewMemoryStore interviewMemoryStore;

    /**
     * 检索当前简历的历史记忆（metadata filter 限定 resumeId）。
     *
     * @return 命中的历史记忆；无历史时为空列表（不是异常）
     */
    public List<InterviewMemory> retrieve(Long resumeId) {
        List<InterviewMemory> memories =
                interviewMemoryStore.search(RETRIEVAL_QUERY, resumeId, TOP_K);
        List<InterviewMemory> result = memories == null ? List.of() : memories;
        log.info("RAG retrieval: resumeId={}, topK={}, retrievedCount={}",
                resumeId, TOP_K, result.size());
        return result;
    }

}
