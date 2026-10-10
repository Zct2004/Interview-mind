package com.interviewmind.memory;

import com.interviewmind.memory.dto.InterviewMemory;

import java.util.List;

/**
 * Interview Memory 的持久化出口。
 * <p>
 * 生产实现 {@link com.interviewmind.vector.PgVectorMemoryStore}：EmbeddingModel → PgVectorStore。
 * 普通测试以确定性测试桩替换，保证 mvn test 不产生任何真实 Embedding 调用。
 */
public interface InterviewMemoryStore {

    /**
     * 向量化并写入向量库。实现方不开启数据库长事务；
     * 失败由调用方（InterviewMemoryService）兜底记录，不影响面试主流程。
     */
    void saveAll(List<InterviewMemory> memories);

    /** 删除指定模拟面试会话沉淀的 RAG 记忆，不影响同一简历的其他面试或录音复盘。 */
    default void deleteBySessionId(Long sessionId) {
        throw new UnsupportedOperationException("当前 MemoryStore 不支持按 sessionId 删除记忆");
    }

    /**
     * 语义检索统一个人 RAG 的历史记忆：底层为 Spring AI similaritySearch
     * （一次查询，不按 resumeId 过滤）。
     * <p>
     * 失败由调用方（InterviewQuestionService 的 RAG 兜底）处理——
     * RAG 是增强能力，检索失败必须能回退到普通出题。
     *
     * @param query    查询语义文本（会被向量化）
     * @param resumeId 兼容旧接口保留；统一 RAG 不按此参数过滤
     * @param topK     返回条数上限
     * @return 命中的历史记忆，无历史时为空列表
     */
    List<InterviewMemory> search(String query, Long resumeId, int topK);

}
