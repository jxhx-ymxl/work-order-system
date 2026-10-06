package com.workorder.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 模型边界的 HTTP 实现（OpenAI 兼容的 {@code chat/completions}）。
 *
 * <p>两条边界在这里落地，不在调用方：
 * <ul>
 *   <li><b>读取期限字节</b>（§4.2）：超限在**读取循环里**抛出并释放连接，
 *       不是"读完整响应再判大小"——后者对不结束的响应等于没有边界。</li>
 *   <li><b>超时</b>：连接与读取各自有上限，超时归类为 {@code MODEL_TIMEOUT}。</li>
 * </ul>
 *
 * <p>用 {@link HttpURLConnection} 而不是 {@code RestTemplate}：需要"边读边计数、超限立即断开"
 * 这种对流量的直接控制，且 S1 不想为它引入新的 HTTP 抽象（CLAUDE.md §1 第 3 条）。
 */
public final class HttpAgentModel implements AgentModel {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int BRIEF_LIMIT = 200;

    private final String apiUrl;
    private final String apiKey;
    private final String model;
    private final List<Map<String, Object>> toolDefinitions;
    private final int configuredConnectTimeoutMillis;
    private final int configuredReadTimeoutMillis;
    private final int maxResponseBytes;

    public HttpAgentModel(String apiUrl, String apiKey, String model, List<Map<String, Object>> toolDefinitions,
                          Duration connectTimeout, Duration readTimeout, int maxResponseBytes) {
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.toolDefinitions = List.copyOf(toolDefinitions);
        this.configuredConnectTimeoutMillis = (int) connectTimeout.toMillis();
        this.configuredReadTimeoutMillis = (int) readTimeout.toMillis();
        this.maxResponseBytes = maxResponseBytes;
    }

    @Override
    public ModelTurn respond(List<JsonNode> transcript, Duration readTimeout) {
        int readTimeoutMillis = effectiveTimeout(readTimeout, configuredReadTimeoutMillis, "读取");
        // 连接也不能比本轮预算更久：预算收敛的是"这一轮的全部时间"，不是只有读取
        int connectTimeoutMillis = Math.min(configuredConnectTimeoutMillis, readTimeoutMillis);
        byte[] payload = serialize(transcript);
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(apiUrl).openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(connectTimeoutMillis);
            connection.setReadTimeout(readTimeoutMillis);
            connection.setRequestProperty("Content-Type", "application/json");
            if (apiKey != null && !apiKey.isBlank()) {
                connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            }
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(payload);
            }

            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                String body = readAtMost(connection.getErrorStream(), maxResponseBytes);
                throw new AgentModelException("MODEL_HTTP_ERROR", "HTTP " + status + "：" + brief(body));
            }
            String body = readAtMost(connection.getInputStream(), maxResponseBytes);
            return parse(body);
        } catch (SocketTimeoutException e) {
            throw new AgentModelException("MODEL_TIMEOUT", "模型读取超时（" + readTimeoutMillis + "ms）");
        } catch (AgentModelException e) {
            throw e;
        } catch (IOException e) {
            throw new AgentModelException("MODEL_UNREACHABLE", e.getClass().getSimpleName() + "：" + e.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();   // 断开即释放连接——包括"读满即中止"的那条路径
            }
        }
    }

    /**
     * 本轮实际生效的超时 = min(调用方给的剩余预算, 配置上限)，且**必须 ≥ 1ms**。
     *
     * <p>两道闸门都是必要的：调用方给的上限不能超过配置（否则配置形同虚设），
     * 而小于 1ms 会被 {@link HttpURLConnection#setReadTimeout(int)} 当成**无限等待**（复核发现 3）。
     */
    private static int effectiveTimeout(Duration requested, int configuredMillis, String what) {
        long requestedMillis = requested == null ? configuredMillis : requested.toMillis();
        if (requestedMillis < 1) {
            throw new AgentModelException("MODEL_TIMEOUT",
                    "本轮剩余" + what + "超时不足 1ms（0 在 HttpURLConnection 里等于无限等待）");
        }
        return (int) Math.min(requestedMillis, configuredMillis);
    }

    private byte[] serialize(List<JsonNode> transcript) {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("model", model);
        ArrayNode messages = request.putArray("messages");
        transcript.forEach(messages::add);
        ArrayNode tools = request.putArray("tools");
        toolDefinitions.forEach(definition -> tools.add(MAPPER.valueToTree(definition)));
        request.put("tool_choice", "auto");
        request.put("temperature", 0);
        try {
            return MAPPER.writeValueAsBytes(request);
        } catch (JsonProcessingException e) {
            throw new AgentModelException("MODEL_PROTOCOL_ERROR", "请求体序列化失败：" + e.getMessage());
        }
    }

    /**
     * 读取上限**在循环内**判定（§4.2）。
     *
     * <p>关键不是"抛了个异常"，而是抛出的时机：读满 {@code maxBytes} 的下一块就越界 → 立即中止，
     * 不等对方把响应写完。对"永不结束的响应"这才是唯一的边界。
     */
    private static String readAtMost(InputStream in, int maxBytes) throws IOException {
        if (in == null) {
            return "";
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int total = 0;
        int read;
        while ((read = in.read(chunk)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new AgentModelException("RESPONSE_TOO_LARGE",
                        "模型响应超过读取上限 " + maxBytes + " 字节，已在读取过程中中止并释放连接");
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private ModelTurn parse(String body) {
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (JsonProcessingException e) {
            throw new AgentModelException("MODEL_PROTOCOL_ERROR", "响应不是合法 JSON：" + brief(body));
        }
        JsonNode message = root.path("choices").path(0).path("message");
        if (message.isMissingNode() || !message.isObject()) {
            throw new AgentModelException("MODEL_PROTOCOL_ERROR", "响应缺少 choices[0].message：" + brief(body));
        }

        List<ModelToolCall> toolCalls = new ArrayList<>();
        for (JsonNode call : message.path("tool_calls")) {
            String callId = call.path("id").asText("");
            String name = call.path("function").path("name").asText("");
            if (callId.isBlank() || name.isBlank()) {
                throw new AgentModelException("MODEL_PROTOCOL_ERROR", "tool_calls 缺少 id 或 function.name");
            }
            toolCalls.add(new ModelToolCall(callId, name, parseArguments(call.path("function").path("arguments"))));
        }
        JsonNode contentNode = message.path("content");
        String content = contentNode.isMissingNode() || contentNode.isNull() ? null : contentNode.asText();
        return new ModelTurn(content, toolCalls, assistantMessageForEcho(message),
                body.getBytes(StandardCharsets.UTF_8).length);
    }

    /**
     * **回填给模型的 assistant 消息：保真回填**（原样返回供应商给的 message），
     * 只移除 {@link #ECHO_DENYLIST} 里**经过实测**会引发 400 的字段——**当前 denylist 是空集**。
     *
     * <p><b>2026-10-06 真供应商实测推翻了原来的白名单方向</b>（§11-1 → D78 追加引用块）：
     * `deepseek-flash` 在 thinking 模式下**要求把 `reasoning_content` 原样回填**——
     * 带它 → 200；白名单把它删掉 → 400，原文
     * `The reasoning_content in the thinking mode must be passed back to the API.`。
     * 也就是说：上一版"删字段"才是 400 的来源，而"留字段"是供应商要求的行为。
     * 代价随之反转——原来的白名单会**稳定地**让多步调查在第二轮 400。
     *
     * <p><b>这条规则按模型而异，不能按供应商推广</b>：denylist 只在**有复现证据**时才加一条，
     * 加的时候必须写明**模型名 + 日期 + 复现命令**（探测脚本 `scripts/agent-provider-probe.ps1` 可复跑）。
     */
    private static final Set<String> ECHO_DENYLIST = Set.of();

    private static ObjectNode assistantMessageForEcho(JsonNode message) {
        ObjectNode echo = message.deepCopy();
        for (String field : ECHO_DENYLIST) {
            echo.remove(field);
        }
        return echo;
    }

    private JsonNode parseArguments(JsonNode raw) {
        if (raw.isMissingNode() || raw.isNull()) {
            return MAPPER.createObjectNode();
        }
        if (raw.isObject()) {
            return raw;
        }
        String text = raw.asText("");
        if (text.isBlank()) {
            return MAPPER.createObjectNode();
        }
        JsonNode parsed;
        try {
            parsed = MAPPER.readTree(text);
        } catch (JsonProcessingException e) {
            throw new AgentModelException("MODEL_PROTOCOL_ERROR", "工具参数不是合法 JSON：" + brief(text));
        }
        if (!parsed.isObject()) {
            throw new AgentModelException("MODEL_PROTOCOL_ERROR", "工具参数不是 JSON 对象：" + brief(text));
        }
        return parsed;
    }

    private static String brief(String body) {
        if (body == null) {
            return "（无响应体）";
        }
        String oneLine = SensitiveDataRedactor.redactText(body.replaceAll("\\s+", " "));
        return oneLine.length() <= BRIEF_LIMIT ? oneLine : oneLine.substring(0, BRIEF_LIMIT) + "…";
    }
}
