package com.interviewmind.memory.dto;

/**
 * 一次模拟面试提炼出的长期记忆单元（业务侧结构，非数据库 Entity、非 AI DTO）。
 * <p>
 * content 是唯一参与语义检索的文本（Embedding 输入），由
 * {@link com.interviewmind.memory.InterviewMemoryBuilder} 用可读的语义段落拼装；
 * 其余字段是结构化定位信息，只进入 Document metadata 用于后续过滤，
 * 不拼进主要语义文本。
 *
 * @param resumeId      归属简历
 * @param sessionId     产生该记忆的面试会话
 * @param questionId    关联题目，整场级记忆为 null
 * @param memoryType    记忆类型
 * @param knowledgePoint 知识点（整场级记忆为 null）
 * @param score         相关评分（题目级 0-10；整场级 0-100），可为 null
 * @param content       语义文本（Embedding 的主要输入）
 */
public record InterviewMemory(
        Long resumeId,
        Long sessionId,
        Long questionId,
        MemoryType memoryType,
        String knowledgePoint,
        Integer score,
        String content) {

    /**
     * 记忆类型词表（与后续 RAG 阶段的过滤维度对齐）。
     * EXPERIENCE 第一版预留不生成。
     */
    public enum MemoryType {
        /** 某道题暴露的薄弱知识点 */
        WEAK_POINT,
        /** 高价值回答模式（score 达标且点评非空的优秀表现） */
        ANSWER_PATTERN,
        /** 整场面试总结 */
        INTERVIEW_SUMMARY,
        /** 简历/经历类记忆（预留） */
        EXPERIENCE
    }

}
