package com.interviewmind.ai;

import com.interviewmind.ai.dto.InterviewRecordingReviewAiResult;

/**
 * 将 ASR 转写全文整理为结构化面试复盘结果。
 * 只负责 LLM 分析，不访问 MinIO、数据库或向量库。
 */
public interface InterviewRecordingReviewAiParser {

    /**
     * 分析一段完整的面试录音转写文本。
     * 无法可靠识别的问题或说话人应在结果中明确标记不确定，而不是猜测。
     *
     * @param transcript ASR 转写全文
     * @return 结构化复盘结果
     */
    InterviewRecordingReviewAiResult analyze(String transcript);
}
