package com.readlater.controller;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查：不经 /api/v1 前缀，返回 { status, ollama }（Spec N5）
 * <p>ollama:false 表示模型服务不可达，用于区分"服务挂"与"模型挂"</p>
 */
@RestController
public class HealthController {

    private final RestTemplate healthRestTemplate;
    private final String ollamaUrl;

    public HealthController(@Qualifier("healthRestTemplate") RestTemplate healthRestTemplate,
                            @Value("${app.ollama-url}") String ollamaUrl) {
        this.healthRestTemplate = healthRestTemplate;
        this.ollamaUrl = ollamaUrl;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        boolean ollama;
        try {
            ollama = healthRestTemplate.getForEntity(ollamaUrl + "/api/tags", String.class)
                    .getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            ollama = false;
        }
        Map<String, Object> result = new LinkedHashMap<>(2);
        result.put("status", "ok");
        result.put("ollama", ollama);
        return result;
    }
}
