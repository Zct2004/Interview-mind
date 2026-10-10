package com.interviewmind.memory;

import com.interviewmind.entity.RecordingReview;
import com.interviewmind.entity.RecordingReviewQuestion;

import java.util.List;

/** 历史录音复盘的向量索引出口；测试可替换为 mock，避免真实 Embedding 调用。 */
public interface RecordingReviewVectorIndexer {
    /** 查询该复盘是否已有向量文档；不得仅依赖业务库中的 vectorStatus。 */
    boolean exists(RecordingReview review);

    void index(RecordingReview review, List<RecordingReviewQuestion> questions);

    /** 删除指定录音复盘对应的稳定 ID 文档，不影响同一简历下其他来源的经验。 */
    void delete(RecordingReview review, List<RecordingReviewQuestion> questions);
}
