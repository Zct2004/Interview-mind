package com.interviewmind.ai;

import com.interviewmind.ai.dto.InterviewQuestionAiResult;
import com.interviewmind.exception.BusinessException;
import com.interviewmind.memory.dto.InterviewMemory;
import com.interviewmind.rag.InterviewQuestionWeight;
import com.interviewmind.rag.InterviewRagContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Spring AI ChatClient 实现：rawText → LLM → {@link InterviewQuestionAiResult}。
 * <p>
 * 与 {@link OpenAiResumeParser} 相同的已验证模式：system prompt 从
 * classpath:prompts/interview-question-system.txt 读取，结构化输出使用
 * ChatClient 的 entity 能力。AI 配置完全复用 application.yml 中现有的
 * spring.ai.openai，不新增任何配置。
 * <p>
 * 输出防御（实测 qwen3.8-27b 偶发输出坏 JSON 或少于 20 道题）：
 * ① 坏 JSON 定向重试一次；② 数量不足重试一次；③ 超出 20 截断；
 * 仍不足如实报 502。网络类失败由 SDK 自带重试，不在此重复放大。
 */
@Slf4j
@Service
public class OpenAiInterviewQuestionParser implements InterviewQuestionAiParser {

    private static final int QUESTION_COUNT = 20;
    private static final int MAX_ERROR_MESSAGE_LENGTH = 300;
    /** 每个知识点最多带入的历史经验条数（控制 Prompt 体积） */
    private static final int MAX_EXPERIENCE_LINES = 2;
    /** 单条历史经验摘要长度上限 */
    private static final int MAX_EXPERIENCE_CHARS = 80;

    private final ChatClient chatClient;

    public OpenAiInterviewQuestionParser(ChatClient.Builder chatClientBuilder,
                                         @Value("classpath:prompts/interview-question-system.txt") Resource systemPromptResource) {
        this.chatClient = chatClientBuilder
                .defaultSystem(readText(systemPromptResource))
                .build();
    }

    @Override
    public InterviewQuestionAiResult parse(String rawText, InterviewRagContext ragContext) {
        InterviewQuestionAiResult result = invoke(rawText, ragContext);
        if (result.questions().size() < QUESTION_COUNT) {
            log.warn("LLM 返回面试题数量不足（{} 道），重试一次", result.questions().size());
            result = invoke(rawText, ragContext);
        }
        if (result.questions().size() < QUESTION_COUNT) {
            log.error("LLM 重试后面试题数量仍不足: {}/{}", result.questions().size(), QUESTION_COUNT);
            throw new BusinessException(502, "AI 生成面试题数量不足: "
                    + result.questions().size() + "/" + QUESTION_COUNT);
        }
        if (result.questions().size() > QUESTION_COUNT) {
            log.warn("LLM 返回面试题数量超出（{} 道），截断为 {} 道", result.questions().size(), QUESTION_COUNT);
            result = new InterviewQuestionAiResult(result.questions().subList(0, QUESTION_COUNT));
        }
        return result;
    }

    /**
     * 坏 JSON 定向重试一次（模型同输入的输出在运行间存在差异，重试有效）；
     * 重试仍失败则转为 502，其他异常原样上抛。
     */
    private InterviewQuestionAiResult invoke(String rawText, InterviewRagContext ragContext) {
        try {
            return callModel(rawText, ragContext);
        } catch (LlmJsonException e) {
            log.warn("LLM 返回的 JSON 无法反序列化，重试一次: {}", e.getMessage());
            try {
                return callModel(rawText, ragContext);
            } catch (LlmJsonException retry) {
                log.error("LLM 重试后返回的 JSON 仍无法反序列化", retry);
                throw new BusinessException(502, "AI 面试题生成失败: LLM 输出的 JSON 无法解析");
            }
        }
    }

    /**
     * 用户消息 = 简历原文（+ 可选 RAG 段）。
     * 无历史权重时返回原始 rawText，保证第一轮出题行为与改造前完全一致。
     * 拼装内容仅含：后端算好的知识点权重 + 历史记忆摘要（不打印、不外传其他数据）。
     */
    private String buildUserMessage(String rawText, InterviewRagContext ragContext) {
        if (ragContext == null || ragContext.weights().isEmpty()) {
            return rawText;
        }

        StringBuilder sb = new StringBuilder(rawText);
        sb.append("\n\n【动态出题权重】\n");
        sb.append("（由后端根据你的历史面试表现计算，不得自行修改或重算）\n");
        for (InterviewQuestionWeight weight : ragContext.weights()) {
            sb.append(weight.knowledgePoint()).append(": ")
                    .append(String.format("%.1f", weight.weight())).append('\n');
        }

        sb.append("\n【历史经验】\n");
        Map<String, List<InterviewMemory>> memoriesByKnowledgePoint =
                ragContext.relevantMemories().stream()
                        .filter(memory -> memory.knowledgePoint() != null
                                && !memory.knowledgePoint().isBlank())
                        .collect(Collectors.groupingBy(
                                InterviewMemory::knowledgePoint,
                                LinkedHashMap::new,
                                Collectors.toList()));
        for (InterviewQuestionWeight weight : ragContext.weights()) {
            List<InterviewMemory> group = memoriesByKnowledgePoint
                    .getOrDefault(weight.knowledgePoint(), List.of());
            sb.append(weight.knowledgePoint())
                    .append("（历史平均分 ").append(String.format("%.1f", weight.averageScore()))
                    .append("，记忆 ").append(weight.memoryCount())
                    .append(" 条，薄弱 ").append(weight.weakPointCount()).append(" 条）：\n");
            group.stream().limit(MAX_EXPERIENCE_LINES).forEach(memory ->
                    sb.append("- ").append(truncate(memory.content(), MAX_EXPERIENCE_CHARS))
                            .append('\n'));
        }
        return sb.toString();
    }

    private InterviewQuestionAiResult callModel(String rawText, InterviewRagContext ragContext) {
        try {
            InterviewQuestionAiResult result = chatClient.prompt()
                    .user(buildUserMessage(rawText, ragContext))
                    .call()
                    .entity(InterviewQuestionAiResult.class);
            if (result == null || result.questions() == null || result.questions().isEmpty()) {
                throw new IllegalStateException("LLM 返回空的面试题列表");
            }
            return result;
        } catch (Exception e) {
            if (containsJacksonException(e)) {
                log.error("LLM 返回的 JSON 反序列化失败", e);
                throw new LlmJsonException(truncate(e.getMessage()), e);
            }
            log.error("Spring AI 面试题生成调用失败", e);
            throw new BusinessException(502, "AI 面试题生成失败: " + truncate(e.getMessage()));
        }
    }

    /** 沿 cause 链查找 Jackson 反序列化异常（模型输出坏 JSON），与网络/认证类错误区分。 */
    private static boolean containsJacksonException(Throwable e) {
        for (Throwable t = e; t != null && t != t.getCause(); t = t.getCause()) {
            if (t instanceof JacksonException) {
                return true;
            }
        }
        return false;
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

    /** 历史经验摘要截断（按字符数，不拆语义段）。 */
    private static String truncate(String content, int maxLength) {
        if (content == null) {
            return "";
        }
        return content.length() > maxLength
                ? content.substring(0, maxLength) + "..."
                : content;
    }

    /** LLM 输出的 JSON 无法反序列化（区别于网络失败的内部信号，最终统一映射为 502）。 */
    private static class LlmJsonException extends RuntimeException {

        LlmJsonException(String message, Throwable cause) {
            super(message, cause);
        }

    }

}
