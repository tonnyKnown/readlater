package com.readlater.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.readlater.common.BizException;
import com.readlater.enums.ErrorCode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 摘要服务：调用本地 Ollama 生成摘要
 * <p>1:1 移植 Node 版 backend/src/worker.js 的 summarize()，含 think 块剥离修复</p>
 */
@Service
public class SummaryService {

    /** 剥离 <think> 块，兼容未闭合（输出被 num_predict 截断）的情况 */
    private static final Pattern THINK_BLOCK_PATTERN =
            Pattern.compile("<think>[\\s\\S]*?(</think>|$)", Pattern.CASE_INSENSITIVE);

    private final RestTemplate ollamaRestTemplate;
    private final ObjectMapper objectMapper;
    private final String ollamaUrl;
    private final String model;

    public SummaryService(@Qualifier("ollamaRestTemplate") RestTemplate ollamaRestTemplate,
                          @Value("${app.ollama-url}") String ollamaUrl,
                          @Value("${app.ollama-model}") String model) {
        this.ollamaRestTemplate = ollamaRestTemplate;
        this.objectMapper = new ObjectMapper();
        this.ollamaUrl = ollamaUrl;
        this.model = model;
    }

    public String getModel() {
        return model;
    }

    /**
     * 生成摘要：不超过 120 字，正文截取前 3000 字，num_predict 上限 500
     */
    public String summarize(String text) {
        Map<String, Object> body = new HashMap<>(4);
        body.put("model", model);
        body.put("prompt", "请用不超过 120 字总结以下文章的核心要点，直接输出总结，不要客套话：\n\n"
                + text.substring(0, Math.min(3000, text.length())));
        body.put("stream", false);
        body.put("options", Map.of("num_predict", 500)); // 上限防失控（推理模型 think 块可能很长）

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        JsonNode resp;
        try {
            resp = ollamaRestTemplate.postForObject(
                    ollamaUrl + "/api/generate", new HttpEntity<>(body, headers), JsonNode.class);
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "Ollama 调用失败：" + e.getMessage());
        }
        if (resp == null || resp.get("response") == null) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "Ollama 返回为空");
        }
        // deepseek-r1 等推理模型有时把内容放在 thinking 字段而 response 为空，做回退兼容
        String raw = resp.get("response").asText();
        if (raw.trim().isEmpty() && resp.get("thinking") != null) {
            raw = resp.get("thinking").asText();
        }
        // 剥离 think 块；剥完为空说明输出被思考过程占满（截断），未生成有效摘要
        String summary = THINK_BLOCK_PATTERN.matcher(raw).replaceAll("").trim();
        if (summary.isEmpty()) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "模型输出被 think 块占满（截断），未生成有效摘要");
        }
        return summary;
    }
}
