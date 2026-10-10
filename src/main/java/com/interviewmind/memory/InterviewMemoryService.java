package com.interviewmind.memory;

import com.interviewmind.ai.dto.InterviewEvaluationAiResult;
import com.interviewmind.entity.InterviewQuestion;
import com.interviewmind.memory.dto.InterviewMemory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Interview Memory 编排：构建（纯规则）→ 向量化入库（Embedding + PgVectorStore）。
 * <p>
 * 在 finish 的数据库事务提交之后调用；向量化失败只记录日志并返回 0，
 * 绝不使已经 COMPLETED 的模拟面试回滚或失败（重试机制后续阶段再引入）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewMemoryService {

    private final InterviewMemoryBuilder interviewMemoryBuilder;
    private final InterviewMemoryStore interviewMemoryStore;

    /** 删除指定模拟面试会话对应的 RAG 记忆。删除失败时向上抛出，阻止数据库先行删除。 */
    public void deleteMemories(Long sessionId) {
        interviewMemoryStore.deleteBySessionId(sessionId);
    }

    /**
     * 为一场已完成的面试构建并索引 Interview Memory。
     *
     * @return 实际写入的记忆条数；失败返回 0（不影响主流程）
     */
    public int indexMemories(Long resumeId, Long sessionId,
                             List<InterviewQuestion> questions,
                             InterviewEvaluationAiResult result) {
        try {
            List<InterviewMemory> memories =
                    interviewMemoryBuilder.build(resumeId, sessionId, questions, result);
            if (memories.isEmpty()) {
                log.warn("Interview Memory 构建结果为空，跳过向量化: sessionId={}", sessionId);
                return 0;
            }
            interviewMemoryStore.saveAll(memories);
            log.info("Interview Memory 已写入向量库: sessionId={}, count={}",
                    sessionId, memories.size());
            return memories.size();
        } catch (Exception e) {
            log.error("Interview Memory 索引失败（面试已 COMPLETED，主流程不受影响）: sessionId={}",
                    sessionId, e);
            return 0;
        }
    }

}
