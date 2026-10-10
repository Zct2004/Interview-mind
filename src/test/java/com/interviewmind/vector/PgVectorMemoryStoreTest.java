package com.interviewmind.vector;

import com.interviewmind.memory.dto.InterviewMemory;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class PgVectorMemoryStoreTest {

    @Test
    void mapsRecordingReviewMemoryWithoutMockInterviewSessionId() {
        Document document = Document.builder()
                .id("recording-review-doc")
                .text("JVM 回答缺少内存区域说明")
                .metadata(Map.of(
                        "resumeId", 7L,
                        "sourceType", "RECORDING_REVIEW",
                        "memoryType", "WEAK_POINT",
                        "knowledgePoint", "JVM"))
                .build();

        InterviewMemory memory = PgVectorMemoryStore.fromDocument(document);

        assertNotNull(memory);
        assertEquals(7L, memory.resumeId());
        assertNull(memory.sessionId());
        assertEquals(InterviewMemory.MemoryType.WEAK_POINT, memory.memoryType());
        assertEquals("JVM", memory.knowledgePoint());
        assertEquals("JVM 回答缺少内存区域说明", memory.content());
    }
}
