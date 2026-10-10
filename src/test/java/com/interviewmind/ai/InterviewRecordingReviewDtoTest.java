package com.interviewmind.ai;

import com.interviewmind.ai.dto.InterviewRecordingReviewAiResult;
import com.interviewmind.ai.dto.RecordingQuestionReviewItem;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class InterviewRecordingReviewDtoTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void deserializesStructuredReviewAndQuestionFields() throws Exception {
        String json = """
                {
                  "summary":"回答覆盖基础概念，但细节不足",
                  "strengths":["表达清楚"],
                  "weakPoints":["缺少原理说明"],
                  "suggestions":["补充执行流程"],
                  "keyTopics":["JVM"],
                  "questions":[{
                    "questionNo":1,
                    "question":"请解释 JVM 内存区域",
                    "questionType":"技术问题",
                    "knowledgePoints":["JVM 内存模型"],
                    "interviewerFollowUp":"",
                    "candidateAnswer":"提到了堆和栈",
                    "answerSummary":"只回答了部分区域",
                    "answerStrengths":["提到堆"],
                    "missingPoints":["未说明方法区"],
                    "referenceAnswer":"应说明运行时数据区",
                    "improvedAnswer":"可按运行时数据区分类回答",
                    "evidence":"请解释 JVM 内存区域",
                    "confidence":"高"
                  }]
                }
                """;

        InterviewRecordingReviewAiResult result = objectMapper.readValue(
                json, InterviewRecordingReviewAiResult.class);

        assertEquals("回答覆盖基础概念，但细节不足", result.summary());
        assertEquals(1, result.questions().size());
        RecordingQuestionReviewItem question = result.questions().getFirst();
        assertEquals("请解释 JVM 内存区域", question.question());
        assertEquals("提到了堆和栈", question.candidateAnswer());
        assertEquals("高", question.confidence());
        assertNotNull(result.suggestions());
        assertEquals(List.of("JVM"), result.keyTopics());
    }
}
