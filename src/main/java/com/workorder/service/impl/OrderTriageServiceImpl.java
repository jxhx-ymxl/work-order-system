package com.workorder.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workorder.common.dto.TriageResult;
import com.workorder.service.OrderTriageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
public class OrderTriageServiceImpl implements OrderTriageService {

    private static final Set<String> VALID_TYPES = Set.of("REPAIR", "LEAVE", "REIMBURSE", "OTHER");

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String apiUrl;
    private final String apiKey;

    public OrderTriageServiceImpl(
            @Value("${llm.api.url:}") String apiUrl,
            @Value("${llm.api.key:}") String apiKey,
            @Value("${llm.api.timeout:5000}") int timeoutMs) {
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        this.restTemplate = new RestTemplate(factory);
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public TriageResult triage(String title, String content) {
        if (apiUrl == null || apiUrl.isBlank()) {
            return TriageResult.fallback();
        }

        try {
            String prompt = buildPrompt(title, content);
            String response = callLlm(prompt);
            return parseResponse(response);
        } catch (Exception e) {
            log.warn("LLM triage失败，使用默认值。原因: {}", e.getMessage());
            return TriageResult.fallback();
        }
    }

    private String buildPrompt(String title, String content) {
        return String.format("""
                根据以下工单内容，判断工单类型和优先级。
                类型可选: REPAIR(报修), LEAVE(请假), REIMBURSE(报销), OTHER(其他)
                优先级: 0(普通), 1(紧急)
                返回JSON: {"type":"REPAIR","priority":1}

                工单标题: %s
                工单内容: %s
                """, title, content);
    }

    private String callLlm(String prompt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (apiKey != null && !apiKey.isBlank()) {
            headers.set("Authorization", "Bearer " + apiKey);
        }

        Map<String, Object> body = Map.of(
                "model", "gpt-3.5-turbo",
                "messages", new Object[]{
                        Map.of("role", "user", "content", prompt)
                },
                "temperature", 0.1
        );

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(apiUrl, request, String.class);
        return response.getBody();
    }

    private TriageResult parseResponse(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode choices = root.get("choices");
            if (choices == null || !choices.isArray() || choices.isEmpty()) {
                throw new IllegalArgumentException("响应缺少choices字段");
            }
            String messageContent = choices.get(0).get("message").get("content").asText();
            return extractResult(messageContent);
        } catch (Exception e) {
            log.warn("LLM响应格式异常，使用默认值: {}", e.getMessage());
            return TriageResult.fallback();
        }
    }

    private TriageResult extractResult(String text) {
        try {
            // 从文本中提取 JSON 对象
            int start = text.indexOf('{');
            int end = text.lastIndexOf('}');
            if (start < 0 || end < 0) {
                throw new IllegalArgumentException("响应中未找到JSON对象");
            }
            String jsonStr = text.substring(start, end + 1);
            JsonNode node = objectMapper.readTree(jsonStr);

            String type = node.has("type") ? node.get("type").asText().toUpperCase() : null;
            int priority = node.has("priority") ? node.get("priority").asInt() : 0;

            if (type == null || !VALID_TYPES.contains(type)) {
                throw new IllegalArgumentException("非法的工单类型: " + type);
            }
            if (priority != 0 && priority != 1) {
                throw new IllegalArgumentException("非法的优先级: " + priority);
            }

            return new TriageResult(type, priority);
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON解析失败: " + e.getMessage(), e);
        }
    }
}
