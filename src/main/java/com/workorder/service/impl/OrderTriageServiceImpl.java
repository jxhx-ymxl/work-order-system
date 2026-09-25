package com.workorder.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workorder.common.dto.TriageResult;
import com.workorder.service.OrderTriageService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
public class OrderTriageServiceImpl implements OrderTriageService {

    /**
     * 合法类型集合：**与 {@code WorkOrderServiceImpl.ALLOWED_TYPES} 保持一致**（R4 之后的四类）。
     *
     * <p>历史漂移（P5 步骤 1 修掉）：R4 把类型换成 NETWORK/UTILITY/DORM/OTHER 时漏改了这个校验集合与 prompt，
     * 于是 LLM 即使返回新类型也会被判非法并静默回落——"校验与实际不一致"的又一处实例。
     */
    private static final Set<String> VALID_TYPES = Set.of("NETWORK", "UTILITY", "DORM", "OTHER");

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String apiUrl;
    private final String apiKey;
    private final String model;

    /** 生产用：Spring 走这个（多构造器时必须显式标注，否则注入会因歧义失败） */
    @org.springframework.beans.factory.annotation.Autowired
    public OrderTriageServiceImpl(
            @Value("${llm.api.url:}") String apiUrl,
            @Value("${llm.api.key:}") String apiKey,
            @Value("${llm.api.timeout:5000}") int timeoutMs,
            @Value("${llm.api.model:}") String model) {
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
        // 模型名**不留默认值**：默认值必须与 LLM_API_URL 所属供应商一致，而供应商彼此不通用
        // （DeepSeek 只认 deepseek-flash/deepseek-v4-pro，写 gpt-3.5-turbo 必然 400）。
        // 留空则交给启动自检报 ERROR"未配置 LLM_MODEL"——**"指向错误目标的默认值比没有默认值更糟"**（D58）。
        this.model = model;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        this.restTemplate = new RestTemplate(factory);
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 兼容构造器：给"直连实例化"的单测用（它们只关心 url/key/timeout，不关心模型名）。
     *
     * <p>为什么不把测试都改成 4 参：那会把"测试的构造方式"与"生产的注入方式"绑在一起，
     * 以后每加一个配置项就要改一遍测试。留一个委派构造器成本更低。
     */
    public OrderTriageServiceImpl(String apiUrl, String apiKey, int timeoutMs) {
        this(apiUrl, apiKey, timeoutMs, null);
    }

    /**
     * 启动期探测（由 {@code LlmStartupCheck} 调用并负责打日志；本方法只做"探测 + 人话描述失败原因"）。
     *
     * <p>分类的意义：400 通常是**模型名不对**、401/403 是 **key 无效**、连不上或超时是 **URL/网络问题**——
     * 这三类处置完全不同，混成一句"LLM 不可用"等于让人重新排查一遍。
     */
    @Override
    public String probeFailure() {
        if (apiUrl == null || apiUrl.isBlank()) {
            return "未配置 LLM_API_URL";
        }
        if (apiKey == null || apiKey.isBlank()) {
            return "未配置 LLM_API_KEY";
        }
        if (model == null || model.isBlank()) {
            return "未配置 LLM_MODEL（必须显式配置，且要与 LLM_API_URL 所属供应商支持的模型名一致，例如 DeepSeek: deepseek-flash）";
        }
        try {
            callLlm("ping");   // 最小请求：只验证"打得通、认得出模型、key 有效"
            return null;
        } catch (HttpClientErrorException e) {
            int code = e.getStatusCode().value();
            if (code == 400) {
                return "HTTP 400（模型名可能不被支持：LLM_MODEL=" + model
                        + " 必须与 LLM_API_URL 所属供应商匹配）：" + brief(e.getResponseBodyAsString());
            }
            if (code == 401 || code == 403) {
                return "HTTP " + code + "（LLM_API_KEY 无效或无权限）：" + brief(e.getResponseBodyAsString());
            }
            return "HTTP " + code + "：" + brief(e.getResponseBodyAsString());
        } catch (ResourceAccessException e) {
            return "不可达或超时（检查 LLM_API_URL / 网络 / 代理）：" + e.getMessage();
        } catch (Exception e) {
            return e.getClass().getSimpleName() + "：" + e.getMessage();
        }
    }

    private String brief(String body) {
        if (body == null) {
            return "（无响应体）";
        }
        String oneLine = body.replaceAll("\\s+", " ");
        return oneLine.length() <= 200 ? oneLine : oneLine.substring(0, 200) + "…";
    }

    @Override
    public TriageResult triage(String title, String content) {
        if (apiUrl == null || apiUrl.isBlank()) {
            // P5 步骤 3 补：**不再返回兜底值冒充结论**（见 TriageUnavailableException 的类注释）。
            throw new com.workorder.common.TriageUnavailableException("未配置 LLM_API_URL，无法分诊");
        }

        try {
            String prompt = buildPrompt(title, content);
            String response = callLlm(prompt);
            return parseResponse(response);
        } catch (Exception e) {
            // 保留 WARN 日志（失败必须留痕），但**把失败交给调用方**去重试，而不是降级成"AI 判成其他"。
            log.warn("[triage] LLM 调用失败，本次不产生分类结论（由消费端按阶梯重试）：{}", e.getMessage());
            throw new com.workorder.common.TriageUnavailableException(
                    "LLM triage 失败：" + e.getMessage(), e);
        }
    }

    private String buildPrompt(String title, String content) {
        return String.format("""
                根据以下工单内容，判断工单类型和优先级。
                类型可选: NETWORK(网络/断网), UTILITY(水电), DORM(宿舍/住宿), OTHER(其他)
                优先级: 0(普通), 1(紧急)
                返回JSON: {"type":"NETWORK","priority":1}

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

        // 用 HashMap 而不是 Map.of：**model 允许为 null**（未配置时）。Map.of 遇到 null 会抛 NPE，
        // 那会把"模型名没配"变成一次 NPE，而不是一个可解释的 400 响应（启动自检已会报 ERROR，这里只保证不炸）。
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("model", model);
        body.put("messages", new Object[]{Map.of("role", "user", "content", prompt)});
        body.put("temperature", 0.1);

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
            // 同上：响应体不合法 = 本次**没有结论**，不能当成"AI 判成其他"。
            log.warn("[triage] LLM 响应格式异常，本次不产生分类结论：{}", e.getMessage());
            throw new com.workorder.common.TriageUnavailableException("LLM 响应不可用：" + e.getMessage(), e);
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
