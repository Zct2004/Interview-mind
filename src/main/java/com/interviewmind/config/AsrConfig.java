package com.interviewmind.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * ASR 模块装配：注册 {@link AsrProperties} 与专用 {@link RestClient}。
 * <p>
 * 项目此前没有统一 HTTP 基础设施，本模块使用 Spring 自带 RestClient（不引入任何额外 SDK）；
 * 超时在 Bean 级别收敛，避免单次 HTTP 挂死绕过轮询总超时。
 */
@Configuration
@EnableConfigurationProperties(AsrProperties.class)
public class AsrConfig {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    /** 覆盖轮询默认总超时 300s，保证任何单次请求都不会永久挂起 */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(300);

    @Bean
    public RestClient asrRestClient() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        return RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }

}
