package com.interviewmind.ai;

import com.interviewmind.ai.dto.InterviewEvaluationAiResult;

/**
 * AI 面试整体评价：结构化评价输入 → LLM → 全场 20 题评价 + 总结。
 * <p>
 * 只负责文本分析，不依赖 MinIO、PDFBox、数据库与 HTTP 层。
 */
public interface InterviewEvaluationAiParser {

    /**
     * 调用大模型对整场面试做一次性分析评价。
     *
     * @param evaluationInput Service 层构建的结构化输入（简历 + 20 题 + 20 回答 + 20 参考答案）
     * @throws com.interviewmind.exception.BusinessException AI 调用失败（HTTP 502）
     */
    InterviewEvaluationAiResult evaluate(String evaluationInput);

}
