package com.readlater.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * RestTemplate 配置：Ollama 调用与健康检查分开（超时不同）
 * <p>遵守规范：HTTP 调用统一走 RestTemplate</p>
 */
@Configuration
public class RestTemplateConfig {

    /** 调 Ollama 生成摘要：读超时 = summary-timeout-ms（默认 30s） */
    @Bean
    public RestTemplate ollamaRestTemplate(RestTemplateBuilder builder,
                                           @Value("${app.summary-timeout-ms}") long timeoutMs) {
        return builder
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .readTimeout(Duration.ofMillis(timeoutMs))
                .build();
    }

    /** 健康检查探测 Ollama：1.5s 短超时（对齐 Node 版 AbortSignal.timeout(1500)） */
    @Bean
    public RestTemplate healthRestTemplate(RestTemplateBuilder builder) {
        return builder
                .connectTimeout(Duration.ofMillis(1500))
                .readTimeout(Duration.ofMillis(1500))
                .build();
    }
}
