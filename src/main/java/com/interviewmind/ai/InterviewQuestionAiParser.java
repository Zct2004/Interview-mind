package com.interviewmind.ai;

import com.interviewmind.ai.dto.InterviewQuestionAiResult;
import com.interviewmind.rag.InterviewRagContext;

/**
 * AI 面试题生成：rawText（+ 可选历史 RAG 上下文）→ LLM → 20 道结构化面试题。
 * <p>
 * 不依赖 MinIO、PDFBox、数据库与 HTTP 层，只负责文本分析。
 * RAG 上下文只提供“历史经验 + 后端算好的动态权重”，权重计算绝不交给 LLM。
 */
public interface InterviewQuestionAiParser {

    /**
     * 调用大模型根据简历原文生成一轮 20 道面试题。
     *
     * @param rawText    简历原文
     * @param ragContext 历史面试 RAG 上下文；无历史 / RAG 失败时传
     *                   {@link InterviewRagContext#empty()}（行为与纯简历出题一致）
     * @throws com.interviewmind.exception.BusinessException AI 调用失败（HTTP 502）
     */
    InterviewQuestionAiResult parse(String rawText, InterviewRagContext ragContext);

}
