package com.interviewmind.rag;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class InterviewQuestionDeduplicatorTest {
    @Test
    void normalizesPunctuationAndWhitespace() {
        assertEquals(InterviewQuestionDeduplicator.normalize("HashMap 的底层原理？"),
                InterviewQuestionDeduplicator.normalize(" hashmap 的底层原理 "));
    }

    @Test
    void detectsRepeatedQuestions() {
        assertTrue(InterviewQuestionDeduplicator.isDuplicate(
                "请介绍一下 HashMap 的底层原理？", List.of("请介绍一下HashMap的底层原理")));
    }

    @Test
    void keepsDifferentQuestionsAndFirstOccurrence() {
        List<String> unique = InterviewQuestionDeduplicator.unique(
                List.of("HashMap 的底层数据结构是什么？", "HashMap的底层数据结构是什么", "Redis 如何实现持久化？"),
                value -> value);
        assertEquals(2, unique.size());
    }
}
