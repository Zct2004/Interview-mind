package com.interviewmind.ai;

import com.interviewmind.ai.dto.ResumeAiResult;
import com.interviewmind.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Spring AI ChatClient 实现：rawText → LLM → {@link ResumeAiResult}。
 * <p>
 * system prompt 从 classpath:prompts/resume-structure-system.txt 读取；
 * 结构化输出使用 ChatClient 的 entity 能力，由 Spring AI 生成 JSON 约束，不做手动 JSON 解析。
 */
@Slf4j
@Service
public class OpenAiResumeParser implements ResumeAiParser {

    private static final int MAX_ERROR_MESSAGE_LENGTH = 300;

    private final ChatClient chatClient;

    public OpenAiResumeParser(ChatClient.Builder chatClientBuilder,
                              @Value("classpath:prompts/resume-structure-system.txt") Resource systemPromptResource) {
        this.chatClient = chatClientBuilder
                .defaultSystem(readText(systemPromptResource))
                .build();
    }

    @Override
    public ResumeAiResult parse(String rawText) {
        try {
            ResumeAiResult result = chatClient.prompt()
                    .user(rawText)
                    .call()
                    .entity(ResumeAiResult.class);
            if (result == null) {
                throw new IllegalStateException("LLM 返回空结果");
            }
            return result;
        } catch (Exception e) {
            log.error("Spring AI 简历结构化调用失败", e);
            throw new BusinessException(502, "AI 简历结构化失败: " + truncate(e.getMessage()));
        }
    }

    private static String readText(Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取 system prompt 失败: " + resource, e);
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

}
