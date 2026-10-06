package com.workorder.agent.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.LinkedHashSet;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 本地 HTTP 桩：模拟 OpenAI 兼容的 {@code /v1/chat/completions} 边界。
 *
 * <p><b>为什么用真 HTTP 桩而不是 mock 客户端</b>（`docs/AGENT-PLAN.md` §10）：
 * 被测的是"模型请求工具 → 程序执行 → 回传结果 → 提交报告"这条协议链，
 * 真实 HTTP 写读与 JSON 解析本身就是被验证的对象（真供应商兼容性靠它背书）。
 * 用 mock 把客户端换掉，测的就只剩被测方自己的假设。
 *
 * <p>脚本化：每个用例按顺序排好"第 N 次请求回什么"，桩把每次收到的请求体原样留下，
 * 测试据此断言"回传给模型的 transcript 里到底有什么"。
 */
public final class StubModelServer implements Closeable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 一次响应的写法。实现类自己发 HTTP 响应（可延时、可无限写）。 */
    public interface Reply {
        void respond(HttpExchange exchange) throws IOException;
    }

    private final HttpServer server;
    private final BlockingQueue<Reply> script = new LinkedBlockingQueue<>();
    private final List<JsonNode> received = new CopyOnWriteArrayList<>();
    private final List<String> protocolViolations = new CopyOnWriteArrayList<>();

    public StubModelServer(Reply... replies) {
        this.script.addAll(Arrays.asList(replies));
        try {
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException("桩启动失败", e);
        }
        server.createContext("/v1/chat/completions", this::handle);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "stub-model");
            t.setDaemon(true);
            return t;
        }));
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions";
    }

    /**
     * 追加脚本（用于"桩先起、再按用例排脚本"的场景——例如 `@DynamicPropertySource` 需要 URL 在容器启动前确定，
     * 而脚本要按用例排）。
     */
    public void enqueue(Reply... replies) {
        script.addAll(java.util.Arrays.asList(replies));
    }

    /** 收到的第 index 个（从 0 开始）请求体。 */
    public JsonNode received(int index) {
        return received.get(index);
    }

    public int requestCount() {
        return received.size();
    }

    /**
     * 请求里未配对的工具调用（assistant 的 {@code tool_calls[].id} 没有后续 {@code role=tool} 应答）。
     *
     * <p>真供应商会直接判 400，所以桩也必须判——否则"报告校验失败用 system 消息回复"这类
     * 协议破坏在本机测不出来（复核发现 1）。
     */
    public List<String> protocolViolations() {
        return List.copyOf(protocolViolations);
    }

    private void handle(HttpExchange exchange) {
        try {
            byte[] raw = exchange.getRequestBody().readAllBytes();
            JsonNode request = MAPPER.readTree(raw);
            received.add(request);
            List<String> unanswered = unansweredToolCalls(request);
            if (!unanswered.isEmpty()) {
                protocolViolations.add("未配对的 tool_call：" + unanswered);
                ObjectNode error = MAPPER.createObjectNode();
                error.putObject("error").put("message",
                        "invalid request: tool_call without tool response: " + unanswered);
                writeJson(exchange, 400, error.toString());
                return;
            }
            Reply reply = script.poll(5, TimeUnit.SECONDS);
            if (reply == null) {
                writeJson(exchange, 500, "{\"error\":{\"message\":\"stub script exhausted\"}}");
                return;
            }
            reply.respond(exchange);
        } catch (Exception e) {
            // 客户端提前断开（超大响应用例）会在这里抛 IOException——属于预期，不污染测试输出。
        } finally {
            exchange.close();
        }
    }

    /** 顺序扫描整段对话：每个 assistant 的 tool_call 都要有更靠后的 role=tool 应答。 */
    private static List<String> unansweredToolCalls(JsonNode request) {
        Set<String> pending = new LinkedHashSet<>();
        for (JsonNode message : request.path("messages")) {
            String role = message.path("role").asText("");
            if ("assistant".equals(role)) {
                for (JsonNode call : message.path("tool_calls")) {
                    pending.add(call.path("id").asText(""));
                }
            } else if ("tool".equals(role)) {
                pending.remove(message.path("tool_call_id").asText(""));
            }
        }
        pending.remove("");
        return new ArrayList<>(pending);
    }

    private static void writeJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        // 必须显式关闭连接：JDK 的 HttpURLConnection 会把 POST 缓存到 keep-alive 连接上，
        // 连接被服务端关闭后会**静默重发一次请求**（实测：2 步脚本收到 3 次请求，第二次响应用在了重发上）。
        // 同一个坑在 scripts/stub-llm.py 的类注释里已记过一次（当时的表象是"连接被中止"）。
        exchange.getResponseHeaders().add("Connection", "close");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    // ---------- 脚本构造器 ----------

    public static Reply json(JsonNode body) {
        return exchange -> writeJson(exchange, 200, body.toString());
    }

    public static Reply httpError(int status, String message) {
        ObjectNode err = MAPPER.createObjectNode();
        err.putObject("error").put("message", message);
        return exchange -> writeJson(exchange, status, err.toString());
    }

    /** 先睡 delayMs 再回——用来触发客户端读取超时。 */
    public static Reply delayed(JsonNode body, long delayMs) {
        return exchange -> {
            sleep(delayMs);
            writeJson(exchange, 200, body.toString());
        };
    }

    /**
     * 永不结束的响应体：按块无限写，直到客户端断开（写抛 IOException）。
     *
     * <p>这是 `docs/AGENT-PLAN.md` §4.2 要求的那类桩——若被测方"读完整响应再判大小"，
     * 用例会挂死；只有"读满即中止并释放连接"才能让用例正常结束。
     */
    public static EndlessReply endless(int chunkBytes) {
        return new EndlessReply(chunkBytes);
    }

    public static final class EndlessReply implements Reply {

        private final int chunkBytes;
        private volatile long written;

        EndlessReply(int chunkBytes) {
            this.chunkBytes = chunkBytes;
        }

        /** 客户端断开前，服务端成功写出的字节数。 */
        public long written() {
            return written;
        }

        @Override
        public void respond(HttpExchange exchange) throws IOException {
            byte[] chunk = new byte[chunkBytes];
            Arrays.fill(chunk, (byte) 'x');
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("Connection", "close");
            exchange.sendResponseHeaders(200, 0);   // 0 = 无 Content-Length，分块传输
            OutputStream out = exchange.getResponseBody();
            try {
                while (true) {
                    out.write(chunk);
                    out.flush();
                    written += chunk.length;
                    sleep(1);
                }
            } catch (IOException clientAborted) {
                // 预期：客户端读满上限后断开
            } finally {
                try {
                    out.close();
                } catch (IOException ignored) {
                    // 连接已断
                }
            }
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // ---------- OpenAI 兼容响应体构造器 ----------

    /** 模型这一轮"请求调用一个工具"。 */
    public static ObjectNode toolCallTurn(String callId, String toolName, String argumentsJson) {
        return toolCallsTurn(new String[]{callId}, new String[]{toolName}, new String[]{argumentsJson});
    }

    /** 模型这一轮"同时请求调用多个工具"（验证调用 ID 关联）。 */
    public static ObjectNode toolCallsTurn(String[] callIds, String[] toolNames, String[] argumentsJson) {
        ObjectNode root = envelope();
        ObjectNode message = (ObjectNode) root.withArray("choices").get(0).get("message");
        ArrayNode toolCalls = message.withArray("tool_calls");
        for (int i = 0; i < callIds.length; i++) {
            ObjectNode call = toolCalls.addObject();
            call.put("id", callIds[i]);
            call.put("type", "function");
            ObjectNode function = call.putObject("function");
            function.put("name", toolNames[i]);
            function.put("arguments", argumentsJson[i]);
        }
        return root;
    }

    /** 模型这一轮"提交报告"（finish_report 是终止动作，不是业务工具）。 */
    public static ObjectNode finishTurn(String problemType, List<String> evidenceIds, List<String> suggestionIds) {
        ObjectNode args = MAPPER.createObjectNode();
        args.put("problemType", problemType);
        ArrayNode ev = args.putArray("evidenceIds");
        evidenceIds.forEach(ev::add);
        ArrayNode sg = args.putArray("suggestionIds");
        suggestionIds.forEach(sg::add);
        return toolCallTurn("call_finish", "finish_report", args.toString());
    }

    /**
     * 模型这一轮"请求调用一个工具"，并额外带上**供应商扩展字段**（如 {@code reasoning_content}、
     * 平台追踪 id）——用来验证"回填给模型的只有白名单字段"（§11-1 / D78）。
     *
     * @param extraKeyValues 交替出现的键值对，例如 {@code "reasoning_content", "先取快照"}
     */
    public static ObjectNode toolCallTurnWithExtras(String callId, String toolName, String argumentsJson,
                                                    String... extraKeyValues) {
        ObjectNode root = toolCallTurn(callId, toolName, argumentsJson);
        ObjectNode message = (ObjectNode) root.withArray("choices").get(0).get("message");
        for (int i = 0; i + 1 < extraKeyValues.length; i += 2) {
            message.put(extraKeyValues[i], extraKeyValues[i + 1]);
        }
        return root;
    }

    /** 原样提交 {@code finish_report} 的参数——用来构造"字段多余 / 缺字段 / 类型不对"的畸形报告。 */
    public static ObjectNode finishTurnRaw(String argumentsJson) {
        return toolCallTurn("call_finish", "finish_report", argumentsJson);
    }

    /** 模型这一轮"只说话，既不调工具也不交报告"。 */
    public static ObjectNode contentOnlyTurn(String content) {
        ObjectNode root = envelope();
        ObjectNode message = (ObjectNode) root.withArray("choices").get(0).get("message");
        message.put("content", content);
        return root;
    }

    /** 拿不到 choices 的响应（协议损坏）。 */
    public static ObjectNode malformedTurn() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("id", "stub-malformed");
        return root;
    }

    private static ObjectNode envelope() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("id", "stub");
        root.put("object", "chat.completion");
        ArrayNode choices = root.putArray("choices");
        ObjectNode choice = choices.addObject();
        choice.put("index", 0);
        ObjectNode message = choice.putObject("message");
        message.put("role", "assistant");
        message.putNull("content");
        message.putArray("tool_calls");
        return root;
    }

    /** 取某次请求里第 index 条消息（用于断言 transcript 内容）。 */
    public static JsonNode message(JsonNode request, int index) {
        return request.path("messages").path(index);
    }

    /** 取某次请求的最后一条消息（重试提示就是最后一条）。 */
    public static JsonNode lastMessage(JsonNode request) {
        JsonNode messages = request.path("messages");
        return messages.path(messages.size() - 1);
    }

    /** 取某次请求里对指定 tool_call_id 的应答消息；没有则返回缺失节点。 */
    public static JsonNode toolMessageFor(JsonNode request, String callId) {
        for (JsonNode message : toolMessages(request)) {
            if (callId.equals(message.path("tool_call_id").asText())) {
                return message;
            }
        }
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }

    /** 取本次请求里所有 {@code role=tool} 的消息。 */
    public static List<JsonNode> toolMessages(JsonNode request) {
        List<JsonNode> result = new ArrayList<>();
        for (JsonNode m : request.path("messages")) {
            if ("tool".equals(m.path("role").asText())) {
                result.add(m);
            }
        }
        return result;
    }

    /** 取本次请求里所有 {@code role=assistant} 的消息。 */
    public static List<JsonNode> assistantMessages(JsonNode request) {
        List<JsonNode> result = new ArrayList<>();
        for (JsonNode m : request.path("messages")) {
            if ("assistant".equals(m.path("role").asText())) {
                result.add(m);
            }
        }
        return result;
    }
}
