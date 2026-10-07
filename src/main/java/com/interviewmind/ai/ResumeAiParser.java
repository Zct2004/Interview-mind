package com.interviewmind.ai;

import com.interviewmind.ai.dto.ResumeAiResult;

/**
 * AI 简历结构化：rawText → LLM → 结构化结果。
 * <p>
 * 不依赖 MinIO、PDFBox、数据库与 HTTP 层，只负责文本分析。
 */
public interface ResumeAiParser {

    /**
     * 调用大模型把简历原文抽取为结构化数据。
     *
     * @throws com.interviewmind.exception.BusinessException AI 调用失败（HTTP 502）
     */
    ResumeAiResult parse(String rawText);

}
