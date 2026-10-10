package com.interviewmind.ai;

import com.interviewmind.ai.dto.InterviewRecordingReviewAiResult;
import com.interviewmind.ai.dto.RecordingQuestionReviewItem;
import com.interviewmind.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 使用 Spring AI 将 ASR 全文转换为结构化录音复盘结果。 */
@Slf4j
@Service
public class OpenAiInterviewRecordingReviewParser implements InterviewRecordingReviewAiParser {

    private static final int MAX_TRANSCRIPT_CHARS = 30_000;
    private static final int MAX_ERROR_MESSAGE_LENGTH = 300;

    private final ChatClient chatClient;
    private final String chatModel;

    public OpenAiInterviewRecordingReviewParser(
            ChatClient.Builder chatClientBuilder,
            @Value("classpath:prompts/interview-recording-review-system.txt") Resource systemPromptResource,
            @Value("${spring.ai.openai.chat.options.model:qwen3.8-27b}") String chatModel) {
        this.chatClient = chatClientBuilder
                .defaultSystem(readText(systemPromptResource))
                .build();
        this.chatModel = chatModel;
    }

    @Override
    public InterviewRecordingReviewAiResult analyze(String transcript) {
        if (transcript == null || transcript.isBlank()) {
            throw new BusinessException(400, "面试录音转写文本不能为空");
        }
        if (transcript.length() > MAX_TRANSCRIPT_CHARS) {
            throw new BusinessException(400, "当前转写文本超过单次分析上限 "
                    + MAX_TRANSCRIPT_CHARS + " 字符，请在后续分段分析功能中处理");
        }

        InterviewRecordingReviewAiResult result = invoke(transcript);
        List<String> problems = validate(result);
        if (!problems.isEmpty()) {
            log.warn("录音复盘 LLM 输出不满足结构要求，重试一次: {}", problems);
            result = invoke(transcript);
            problems = validate(result);
            if (!problems.isEmpty()) {
                log.error("录音复盘 LLM 重试后仍不满足结构要求: {}", problems);
                throw new BusinessException(502, "AI 录音复盘失败: " + String.join("；", problems));
            }
        }
        return result;
    }

    /** 坏 JSON 只定向重试一次；其他调用错误不重复放大请求。 */
    private InterviewRecordingReviewAiResult invoke(String transcript) {
        try {
            return callModel(transcript);
        } catch (LlmJsonException e) {
            log.warn("录音复盘 LLM 返回 JSON 无法解析，重试一次: {}", e.getMessage());
            try {
                return callModel(transcript);
            } catch (LlmJsonException retry) {
                log.error("录音复盘 LLM 重试后 JSON 仍无法解析", retry);
                throw new BusinessException(502, "AI 录音复盘失败: LLM 输出的 JSON 无法解析");
            }
        }
    }

    private InterviewRecordingReviewAiResult callModel(String transcript) {
        try {
            // Spring AI 2.0.1 的结构化输出可能为 Prompt 附加 options，导致隐式 60 秒超时覆盖客户端配置。
            // 在本次调用显式指定模型、温度、思考开关、超时和重试次数，避免请求退回 SDK 默认值。
            InterviewRecordingReviewAiResult result = chatClient.prompt()
                    .options(OpenAiChatOptions.builder()
                            .model(chatModel)
                            .temperature(0.0)
                            .extraBody(Map.of("enable_thinking", false))
                            .timeout(Duration.ofMinutes(10))
                            .maxRetries(0))
                    .user("请分析以下面试录音 ASR 转写文本，并严格遵守 system 规则输出 JSON。\n\n"
                            + "【转写文本开始】\n" + transcript + "\n【转写文本结束】")
                    .call()
                    .entity(InterviewRecordingReviewAiResult.class);
            if (result == null) {
                throw new IllegalStateException("LLM 返回空的录音复盘结果");
            }
            return result;
        } catch (Exception e) {
            if (containsJacksonException(e)) {
                log.error("录音复盘 LLM 返回 JSON 无法解析", e);
                throw new LlmJsonException(truncate(e.getMessage()), e);
            }
            log.error("Spring AI 录音复盘调用失败", e);
            throw new BusinessException(502, "AI 录音复盘调用失败: " + truncate(e.getMessage()));
        }
    }

    private List<String> validate(InterviewRecordingReviewAiResult result) {
        List<String> problems = new ArrayList<>();
        if (result.summary() == null || result.summary().isBlank()) {
            problems.add("summary 为空");
        }
        if (result.questions() == null || result.questions().isEmpty()) {
            problems.add("未识别到任何面试问题");
        } else {
            for (int i = 0; i < result.questions().size(); i++) {
                RecordingQuestionReviewItem item = result.questions().get(i);
                if (item == null || item.question() == null || item.question().isBlank()) {
                    problems.add("第 " + (i + 1) + " 条问题为空");
                }
            }
        }
        return problems;
    }

    private static boolean containsJacksonException(Throwable error) {
        for (Throwable current = error; current != null && current != current.getCause();
             current = current.getCause()) {
            if (current instanceof JacksonException) {
                return true;
            }
        }
        return false;
    }

    private static String readText(Resource resource) {
        try (InputStream input = resource.getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取录音复盘 system prompt 失败: " + resource, e);
        }
    }

    private static String truncate(String message) {
        if (message == null || message.isBlank()) {
            return "未知错误";
        }
        return message.length() > MAX_ERROR_MESSAGE_LENGTH
                ? message.substring(0, MAX_ERROR_MESSAGE_LENGTH) + "..."
                : message;
    }

    private static class LlmJsonException extends RuntimeException {
        LlmJsonException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
