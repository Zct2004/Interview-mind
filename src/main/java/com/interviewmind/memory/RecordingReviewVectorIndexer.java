package com.interviewmind.memory;

import com.interviewmind.entity.RecordingReview;
import com.interviewmind.entity.RecordingReviewQuestion;

import java.util.List;

/** 历史录音复盘的向量索引出口；测试可替换为 mock，避免真实 Embedding 调用。 */
public interface RecordingReviewVectorIndexer {
    void index(RecordingReview review, List<RecordingReviewQuestion> questions);
}
