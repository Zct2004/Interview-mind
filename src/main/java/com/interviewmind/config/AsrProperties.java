package com.interviewmind.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * DashScope 异步 ASR 配置（interview.asr.*）。
 * <p>
 * API Key 不在本类：复用 {@code spring.ai.openai.api-key}（DashScope 同一把 key），
 * 在客户端构造器以 @Value 注入，不产生第二份配置。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "interview.asr")
public class AsrProperties {

    /** FileTrans 模型名，固定 qwen-audio-3.1-asr-flash-filetrans */
    private String model;

    /** DashScope OpenAPI 根地址（非 compatible-mode） */
    private String baseUrl;

    /** 轮询间隔（毫秒） */
    private long pollIntervalMs;

    /** 轮询总超时（毫秒），超过即抛 ASR_TIMEOUT，绝不无限轮询 */
    private long pollTimeoutMs;

}
