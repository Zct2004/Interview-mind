package com.interviewmind.ai;

import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatModel.ResponseFormat;
import org.springframework.ai.openai.OpenAiChatOptions;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiInterviewQuestionParserTest {

    @Test
    void enablesJsonObjectResponseFormatForQuestionGeneration() {
        OpenAiChatOptions options = OpenAiInterviewQuestionParser.jsonModeOptions().build();

        assertThat(options.getResponseFormat()).isNotNull();
        assertThat(options.getResponseFormat().getType()).isEqualTo(ResponseFormat.Type.JSON_OBJECT);
    }
}
