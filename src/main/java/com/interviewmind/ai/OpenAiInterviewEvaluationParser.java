package com.interviewmind.ai;

import com.interviewmind.ai.dto.InterviewEvaluationAiResult;
import com.interviewmind.ai.dto.QuestionEvaluationItem;
import com.interviewmind.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Spring AI ChatClient 实现：结构化评价输入 → LLM → {@link InterviewEvaluationAiResult}。
 * <p>
 * 与简历解析、题目生成相同的已验证模式：system prompt 从
 * classpath:prompts/interview-evaluation-system.txt 读取，结构化输出使用
 * ChatClient 的 entity 能力。AI 配置完全复用 application.yml 现有的 spring.ai.openai。
 * <p>
 * 输出防御（均只定向重试一次，不无限重试）：
 * ① 坏 JSON 定向重试一次；② 结构校验（逐题评价不足 20 条 / summary 为空 /
 * 某条缺少 score 或 analysis）重试一次，仍不满足报 502；③ 超出 20 条截断；
 * 网络类失败由 SDK 自带重试，不在此重复放大。
 */
@Slf4j
@Service
public class OpenAiInterviewEvaluationParser implements InterviewEvaluationAiParser {

    private static final int QUESTION_COUNT = 20;
    private static final int MAX_ERROR_MESSAGE_LENGTH = 300;

    private final ChatClient chatClient;

    public OpenAiInterviewEvaluationParser(ChatClient.Builder chatClientBuilder,
                                           @Value("classpath:prompts/interview-evaluation-system.txt") Resource systemPromptResource) {
        this.chatClient = chatClientBuilder
                .defaultSystem(readText(systemPromptResource))
                .build();
    }

    @Override
    public InterviewEvaluationAiResult evaluate(String evaluationInput) {
        InterviewEvaluationAiResult result = invoke(evaluationInput);
        List<String> problems = validate(result);
        if (!problems.isEmpty()) {
            log.warn("LLM 返回评价不满足结构要求，重试一次: {}", problems);
            result = invoke(evaluationInput);
            problems = validate(result);
            if (!problems.isEmpty()) {
                log.error("LLM 重试后返回的评价仍不满足结构要求: {}", problems);
                throw new BusinessException(502, "AI 面试评价失败: " + String.join("；", problems));
            }
        }
        int count = result.questionEvaluations().size();
        if (count > QUESTION_COUNT) {
            log.warn("LLM 返回逐题评价数量超出（{} 条），截断为 {} 条", count, QUESTION_COUNT);
            result = new InterviewEvaluationAiResult(
                    result.totalScore(), result.summary(), result.strengths(),
                    result.weakPoints(), result.suggestions(),
                    result.questionEvaluations().subList(0, QUESTION_COUNT));
        }
        return result;
    }

    /**
     * 结构校验：逐题评价必须恰好 20 条、summary 非空、每条 score 与 analysis 必须给出。
     * 任一不满足都触发一次定向重试，不满足则如实报 502（不无限重试）。
     */
    private List<String> validate(InterviewEvaluationAiResult result) {
        List<String> problems = new ArrayList<>();
        int count = result.questionEvaluations() == null ? 0 : result.questionEvaluations().size();
        if (count < QUESTION_COUNT) {
            problems.add("逐题评价数量不足 " + count + "/" + QUESTION_COUNT);
        }
        if (result.summary() == null || result.summary().isBlank()) {
            problems.add("summary 为空");
        }
        if (result.questionEvaluations() != null) {
            for (int i = 0; i < Math.min(count, QUESTION_COUNT); i++) {
                QuestionEvaluationItem item = result.questionEvaluations().get(i);
                if (item.score() == null) {
                    problems.add("第 " + (i + 1) + " 条缺少 score");
                }
                if (item.analysis() == null || item.analysis().isBlank()) {
                    problems.add("第 " + (i + 1) + " 条缺少 analysis");
                }
            }
        }
        return problems;
    }

    /** 坏 JSON 定向重试一次；重试仍失败转 502，其他异常原样上抛。 */
    private InterviewEvaluationAiResult invoke(String evaluationInput) {
        try {
            return callModel(evaluationInput);
        } catch (LlmJsonException e) {
            log.warn("LLM 返回的 JSON 无法反序列化，重试一次: {}", e.getMessage());
            try {
                return callModel(evaluationInput);
            } catch (LlmJsonException retry) {
                log.error("LLM 重试后返回的 JSON 仍无法反序列化", retry);
                throw new BusinessException(502, "AI 面试评价失败: LLM 输出的 JSON 无法解析");
            }
        }
    }

    private InterviewEvaluationAiResult callModel(String evaluationInput) {
        try {
            InterviewEvaluationAiResult result = chatClient.prompt()
                    .user(evaluationInput)
                    .call()
                    .entity(InterviewEvaluationAiResult.class);
            if (result == null) {
                throw new IllegalStateException("LLM 返回空结果");
            }
            return result;
        } catch (Exception e) {
            if (containsJacksonException(e)) {
                log.error("LLM 返回的 JSON 反序列化失败", e);
                throw new LlmJsonException(truncate(e.getMessage()), e);
            }
            log.error("Spring AI 面试评价调用失败", e);
            throw new BusinessException(502, "AI 面试评价失败: " + truncate(e.getMessage()));
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

    /** LLM 输出的 JSON 无法反序列化（区别于网络失败的内部信号，最终统一映射为 502）。 */
    private static class LlmJsonException extends RuntimeException {

        LlmJsonException(String message, Throwable cause) {
            super(message, cause);
        }

    }

}
