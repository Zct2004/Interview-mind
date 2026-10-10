package com.interviewmind.vector;

import com.interviewmind.entity.RecordingReview;
import com.interviewmind.entity.RecordingReviewQuestion;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class PgVectorRecordingReviewIndexerTest {

    @Test
    void usesRecordingSourceMetadataAndStableDocumentIdsWithoutRealEmbedding() {
        VectorStore vectorStore = mock(VectorStore.class);
        PgVectorRecordingReviewIndexer indexer = new PgVectorRecordingReviewIndexer(vectorStore);

        RecordingReview review = new RecordingReview();
        review.setId(42L);
        review.setResumeId(7L);
        review.setObjectKey("recording/test.m4a");
        review.setSummary("总结：回答缺少原理说明");

        RecordingReviewQuestion question = new RecordingReviewQuestion();
        question.setQuestionNo(1);
        question.setQuestion("什么是 JVM？");
        question.setCandidateAnswer("负责运行 Java 程序");
        question.setMissingPoints("[\"内存区域\"]");

        indexer.index(review, List.of(question));
        indexer.index(review, List.of(question));

        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(vectorStore, times(2)).add(anyList());
        verify(vectorStore, times(2)).add(captor.capture());
        @SuppressWarnings("unchecked")
        List<Document> firstBatch = (List<Document>) captor.getAllValues().get(0);
        @SuppressWarnings("unchecked")
        List<Document> secondBatch = (List<Document>) captor.getAllValues().get(1);

        assertEquals(2, firstBatch.size());
        assertEquals("RECORDING_REVIEW", firstBatch.get(0).getMetadata().get("sourceType"));
        assertEquals(42L, ((Number) firstBatch.get(0).getMetadata().get("sourceId")).longValue());
        assertEquals("recording/test.m4a", firstBatch.get(0).getMetadata().get("objectKey"));
        assertEquals(7L, ((Number) firstBatch.get(0).getMetadata().get("resumeId")).longValue());
        assertEquals("INTERVIEW_SUMMARY", firstBatch.get(0).getMetadata().get("memoryType"));
        assertEquals("WEAK_POINT", firstBatch.get(1).getMetadata().get("memoryType"));
        assertEquals(firstBatch.get(0).getId(), secondBatch.get(0).getId());
        assertEquals(firstBatch.get(1).getId(), secondBatch.get(1).getId());
        assertNotEquals(firstBatch.get(0).getId(), firstBatch.get(1).getId());
    }
}
