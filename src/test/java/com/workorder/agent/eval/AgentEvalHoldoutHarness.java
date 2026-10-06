package com.workorder.agent.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.workorder.agent.AgentEvidence;
import com.workorder.agent.AgentLimits;
import com.workorder.agent.AgentReport;
import com.workorder.agent.AgentReportRenderer;
import com.workorder.agent.AgentRunResult;
import com.workorder.agent.AgentToolRegistry;
import com.workorder.agent.FinalReview;
import com.workorder.agent.FixedFlowInvestigator;
import com.workorder.agent.HttpAgentModel;
import com.workorder.agent.InvestigationAgent;
import com.workorder.agent.PermissionRecheck;
import com.workorder.agent.ToolContext;
import com.workorder.agent.support.StubModelServer;
import com.workorder.agent.tool.DeptComparisonTool;
import com.workorder.agent.tool.OrderFactsTool;
import com.workorder.entity.User;
import com.workorder.entity.UserRole;
import com.workorder.entity.WorkOrder;
import com.workorder.entity.WorkOrderLog;
import com.workorder.mapper.UserMapper;
import com.workorder.mapper.UserRoleMapper;
import com.workorder.mapper.WorkOrderLogMapper;
import com.workorder.mapper.WorkOrderMapper;
import com.workorder.service.WorkOrderService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * **S6 阶段 1：holdout harness + 桩跑通 + 失败保留证明**（类名不带 `Test` → 默认 `mvn test` 不跑它）。
 *
 * <p>这一遍**只是验证 harness**：24 例 × 2 方案（fixed / agent）× 3 次 = **144 次**，
 * 产出统计与失败清单，并证明**失败不会被吞**。桩对任何输入返回固定值
 * （与 `scripts/triage-eval.py` 文件头同一口径）——**这一遍不是成绩**，**不是**两方案对照结论。
 *
 * <p><b>成对条件</b>（手册 L121-L127 / L145）：同一模型端点（本地 HTTP 桩）、同一数据快照
 * （每次运行前重建库）、同权限（同一 {@code ToolContext} 构造路径）、同任务集（同一 24 例）、
 * 同一最高预算（同一 {@link AgentLimits}）；每例每方案 3 次、**三次结果全部保留**。
 *
 * <p><b>模型走真实 HTTP</b>：agent 侧复用 `AgentConfiguration` 装配的 {@link HttpAgentModel}，
 * 只把 `llm.api.url` 指向本轮启动的**本地 HTTP 桩**——所以真实 HTTP 写读 + JSON 解析 + 有界重试都在链路上。
 * 桩按 transcript 决定回工具调用还是 finish_report；故障注入槽（19/20/21/22 与 16/17/24）由桩按场景驱动。
 *
 * <p><b>专用库</b>：`work_order_holdout`（结构克隆自 `work_order_test` + `t_role` 参考行），
 * **不写** `work_order_test`、**不碰**业务库；跑完按 D19 最宽口径逐表统计后 DROP。
 *
 * <p><b>怎么跑</b>：
 * <pre>
 * $env:MYSQL_PORT='3307'; $env:MYSQL_PASSWORD='&lt;deploy/.env&gt;'
 * mvn -o test "-Dtest=AgentEvalHoldoutHarness"
 * </pre>
 */
@SpringBootTest(properties = {
        "agent.investigation.enabled=true",
        "workorder.outbox.dispatch.enabled=false"
})
@ActiveProfiles("test")
@DisplayName("S6 holdout harness（24×2×3，桩跑通）")
class AgentEvalHoldoutHarness {

    private static final String SOURCE_DB = "work_order_test";
    private static final String HOLDOUT_DB = "work_order_holdout";
    private static final String DB_HOST = System.getenv().getOrDefault("MYSQL_HOST", "localhost");
    private static final String DB_PORT = System.getenv().getOrDefault("MYSQL_PORT", "3306");
    private static final String DB_USER = System.getenv().getOrDefault("MYSQL_USER", "root");
    private static final String DB_PASSWORD = System.getenv().getOrDefault("MYSQL_PASSWORD", "123456");
    private static final Path CASES = Path.of("scripts", "agent-eval-holdout.json");
    private static final Path RESULTS = Path.of("docs", "agent-eval", "s6-holdout-stub-run-20261007.md");

    private static final long DEPT_A = 7011L;
    private static final long DEPT_B = 7012L;
    private static final Map<String, Long> ROLE_IDS = Map.of(
            "SYS_ADMIN", 1L, "SUBMITTER", 2L, "HANDLER", 3L, "DEPT_ADMIN", 4L);
    private static final int REPEATS = 3;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, Integer> ROW_COUNTS_BEFORE_DROP = new TreeMap<>();
    private static final List<String> TABLES = new ArrayList<>();

    // ─────────────────────── 本地 HTTP 桩（真实 HTTP + JSON 解析 + 有界重试） ───────────────────────

    /** 桩的行为模式：默认 transcript 驱动；故障注入槽各自一种。 */
    private enum Injection {
        NORMAL,                 // 读根 → finish（ORDER_STATUS）
        ERROR_429_ONCE,         // 首次 429，其后可恢复（槽 21）
        ERROR_401,              // 401（槽 22）
        ILLEGAL_BATCH,          // 同轮同一 callId 不同参数（槽 19）
        REPEAT_SAME_TOOL,       // 连续两轮同工具同参数（槽 20）
        MUTATE_PERM,            // 首轮撤掉 DEPT_ADMIN 角色（槽 16）
        MUTATE_DEADLINE,        // 首轮改 sla_deadline（槽 24）
        MUTATE_PEER             // 先查对照单、再把它调出部门、再 finish（槽 17）
    }

    private static final ObjectMapper STUB_MAPPER = new ObjectMapper();
    private static final Pattern ORDER_REF = Pattern.compile("orderRef=(WO-[0-9]+-[0-9]+)");
    private static final Pattern FACT_LINE = Pattern.compile("-\\s*([a-z][a-z0-9_.]*)=[^\\n]*?\\((E\\d+)\\)");
    private static HttpServer stubServer;
    private static volatile Injection injection = Injection.NORMAL;
    private static final AtomicInteger requestInRun = new AtomicInteger();
    private static final AtomicInteger retriesSoFar = new AtomicInteger();
    private static volatile String peerOrderNoForInjection;
    private static volatile Long callerIdForInjection;

    @DynamicPropertySource
    static void stubAndDatabase(DynamicPropertyRegistry registry) throws Exception {
        prepareDatabase();
        startStub();
        registry.add("spring.datasource.url", AgentEvalHoldoutHarness::jdbcUrl);
        registry.add("llm.api.url", () -> stubServerUrl());
        registry.add("llm.api.key", () -> "stub-key");
        registry.add("llm.api.model", () -> "stub-model");
        registry.add("agent.investigation.mode", () -> "agent");
    }

    private static synchronized void startStub() throws Exception {
        if (stubServer != null) {
            return;
        }
        stubServer = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        stubServer.createContext("/v1/chat/completions", AgentEvalHoldoutHarness::handleStub);
        stubServer.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "holdout-stub");
            t.setDaemon(true);
            return t;
        }));
        stubServer.start();
    }

    private static String stubServerUrl() {
        return "http://127.0.0.1:" + stubServer.getAddress().getPort() + "/v1/chat/completions";
    }

    private static void handleStub(HttpExchange exchange) {
        try {
            byte[] raw = exchange.getRequestBody().readAllBytes();
            JsonNode request = STUB_MAPPER.readTree(raw);
            String body = request.toString();
            int n = requestInRun.incrementAndGet();
            Injection mode = injection;
            switch (mode) {
                case ERROR_429_ONCE -> {
                    if (retriesSoFar.getAndIncrement() == 0) {
                        writeJson(exchange, 429, "{\"error\":{\"message\":\"rate limited\"}}");
                    } else {
                        normal(exchange, body);
                    }
                }
                case ERROR_401 -> writeJson(exchange, 401, "{\"error\":{\"message\":\"unauthorized\"}}");
                case ILLEGAL_BATCH -> writeJson(exchange, 200, illegalBatchTurn(body).toString());
                case REPEAT_SAME_TOOL -> writeJson(exchange, 200,
                        StubModelServer.toolCallTurn("call_read", OrderFactsTool.NAME, orderNoArgs(body)).toString());
                case MUTATE_PERM -> {
                    if (n == 1) {
                        revokeDeptAdminRole(callerIdForInjection);
                    }
                    normal(exchange, body);
                }
                case MUTATE_DEADLINE -> {
                    if (n == 1) {
                        bumpDeadline(body);
                    }
                    normal(exchange, body);
                }
                case MUTATE_PEER -> {
                    if (n == 1) {
                        writeJson(exchange, 200,
                                StubModelServer.toolCallTurn("call_read", OrderFactsTool.NAME, orderNoArgs(body)).toString());
                    } else if (n == 2) {
                        writeJson(exchange, 200, StubModelServer.toolCallTurn("call_peer",
                                DeptComparisonTool.NAME, peerArgs(body)).toString());
                    } else {
                        movePeerOutOfDept(peerOrderNoForInjection);
                        writeJson(exchange, 200, finishTurn(body).toString());
                    }
                }
                default -> normal(exchange, body);
            }
        } catch (Exception e) {
            try {
                writeJson(exchange, 500, "{\"error\":{\"message\":\"stub failure\"}}");
            } catch (Exception ignored) {
                // 连接已断
            }
        } finally {
            exchange.close();
        }
    }

    /** 默认桩：还没拿到 role=tool 就回一次工具调用；拿到了就 finish（固定 ORDER_STATUS）。 */
    private static void normal(HttpExchange exchange, String body) throws Exception {
        boolean hasToolAnswer = body.contains("\"role\":\"tool\"");
        JsonNode turn = hasToolAnswer ? finishTurn(body)
                : StubModelServer.toolCallTurn("call_read", OrderFactsTool.NAME, orderNoArgs(body));
        writeJson(exchange, 200, turn.toString());
    }

    /** finish_report 引用 ORDER_STATUS 的三个必需事实（编号从系统提示里解析，不编造）。 */
    private static JsonNode finishTurn(String body) {
        Map<String, String> factIds = parseFactIds(body);
        List<String> ids = new ArrayList<>();
        for (String fact : List.of("order.exists", "order.status", "order.assignee")) {
            String id = factIds.get(fact);
            if (id != null) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            ids = List.of("E1");
        }
        return StubModelServer.finishTurn("ORDER_STATUS", ids, List.of());
    }

    /** 同轮同一 callId、参数不一致 → 非法批次（D84 / L133 / L137）。 */
    private static JsonNode illegalBatchTurn(String body) {
        String orderNo = parseOrderNo(body);
        return StubModelServer.toolCallsTurn(
                new String[]{"call_dup", "call_dup"},
                new String[]{OrderFactsTool.NAME, OrderFactsTool.NAME},
                new String[]{"{\"orderNo\":\"" + orderNo + "\"}",
                        "{\"orderNo\":\"" + orderNo + "\",\"extra\":\"x\"}"});
    }

    private static String orderNoArgs(String body) {
        return "{\"orderNo\":\"" + parseOrderNo(body) + "\"}";
    }

    private static String peerArgs(String body) {
        return "{\"orderNo\":\"" + parseOrderNo(body) + "\",\"relation\":\""
                + DeptComparisonTool.RELATION_SAME_ASSIGNEE_ACTIVE + "\"}";
    }

    private static String parseOrderNo(String body) {
        Matcher matcher = ORDER_REF.matcher(body);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "WO-20261006-00001";
    }

    private static Map<String, String> parseFactIds(String body) {
        Map<String, String> map = new LinkedHashMap<>();
        Matcher matcher = FACT_LINE.matcher(body);
        while (matcher.find()) {
            map.putIfAbsent(matcher.group(1), matcher.group(2));
        }
        return map;
    }

    private static void writeJson(HttpExchange exchange, int status, String payload) throws Exception {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.getResponseHeaders().add("Connection", "close");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    // ─────────────────────── 注入用的库改写（harness 侧，agent 代码不动） ───────────────────────

    private static void revokeDeptAdminRole(Long callerId) {
        if (callerId == null) {
            return;
        }
        exec("DELETE FROM t_user_role WHERE user_id=" + callerId + " AND role_id=" + 4L);
        System.out.println("[holdout] 注入：撤掉 user " + callerId + " 的 DEPT_ADMIN 角色（槽 16）");
    }

    private static void bumpDeadline(String body) {
        String orderNo = parseOrderNo(body);
        // 改一个**证据里登记过**的业务字段（sla_deadline）；version 不改（设计稿 L213：不只比 version）。
        exec("UPDATE t_work_order SET sla_deadline = DATE_ADD(sla_deadline, INTERVAL 3 HOUR)"
                + " WHERE order_no='" + orderNo + "'");
        System.out.println("[holdout] 注入：改 " + orderNo + " 的 sla_deadline（槽 24）");
    }

    private static void movePeerOutOfDept(String peerOrderNo) {
        if (peerOrderNo == null) {
            return;
        }
        exec("UPDATE t_user SET dept_id=" + DEPT_B
                + " WHERE id = (SELECT submitter_id FROM t_work_order WHERE order_no='" + peerOrderNo + "')");
        System.out.println("[holdout] 注入：把对照单 " + peerOrderNo + " 的提交人调出部门（槽 17）");
    }

    private static java.sql.Statement jdbcStatement;

    private static synchronized java.sql.Statement jdbc() {
        try {
            if (jdbcStatement == null || jdbcStatement.isClosed()) {
                jdbcStatement = DriverManager.getConnection(jdbcUrl(), DB_USER, DB_PASSWORD).createStatement();
            }
            return jdbcStatement;
        } catch (Exception e) {
            throw new IllegalStateException("打开注入连接失败：" + e.getMessage(), e);
        }
    }

    private static void exec(String sql) {
        try {
            jdbc().executeUpdate(sql);
        } catch (Exception e) {
            throw new IllegalStateException("注入 SQL 失败：" + e.getMessage(), e);
        }
    }

    // ─────────────────────── 库准备 ───────────────────────

    private static void prepareDatabase() throws Exception {
        if (!HOLDOUT_DB.startsWith("work_order_holdout")) {
            throw new IllegalStateException("拒绝操作非 holdout 库：" + HOLDOUT_DB);
        }
        try (Connection conn = DriverManager.getConnection(serverJdbcUrl(), DB_USER, DB_PASSWORD);
             Statement st = conn.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS `" + HOLDOUT_DB + "`");
            st.execute("CREATE DATABASE `" + HOLDOUT_DB + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            try (ResultSet rs = conn.createStatement().executeQuery(
                    "SELECT table_name FROM information_schema.tables WHERE table_schema='" + SOURCE_DB
                            + "' AND table_type='BASE TABLE' ORDER BY table_name")) {
                while (rs.next()) {
                    TABLES.add(rs.getString(1));
                }
            }
            for (String table : TABLES) {
                st.execute("CREATE TABLE `" + HOLDOUT_DB + "`.`" + table + "` LIKE `" + SOURCE_DB + "`.`" + table + "`");
            }
            st.execute("INSERT INTO `" + HOLDOUT_DB + "`.`t_role` SELECT * FROM `" + SOURCE_DB + "`.`t_role`");
            System.out.println("[holdout] 专用库 " + HOLDOUT_DB + " 就绪（克隆表 " + TABLES.size() + " 张 + t_role）");
        }
    }

    private static String serverJdbcUrl() {
        return "jdbc:mysql://" + DB_HOST + ":" + DB_PORT
                + "/?useUnicode=true&characterEncoding=utf8&connectionTimeZone=%2B08:00&useSSL=false&allowPublicKeyRetrieval=true";
    }

    private static String jdbcUrl() {
        return "jdbc:mysql://" + DB_HOST + ":" + DB_PORT + "/" + HOLDOUT_DB
                + "?useUnicode=true&characterEncoding=utf8&connectionCollation=utf8mb4_unicode_ci"
                + "&connectionTimeZone=%2B08:00&forceConnectionTimeZoneToSession=true&useSSL=false&allowPublicKeyRetrieval=true";
    }

    // ─────────────────────── 装配（与 AgentConfiguration 同一批零件） ───────────────────────

    @Autowired private HttpAgentModel wiredModel;
    @Autowired private AgentToolRegistry tools;
    @Autowired private AgentLimits limits;
    @Autowired private AgentReportRenderer renderer;
    @Autowired private FinalReview finalReview;
    @Autowired private PermissionRecheck permissionRecheck;
    @Autowired private WorkOrderService workOrderService;
    @Autowired private UserMapper userMapper;
    @Autowired private UserRoleMapper userRoleMapper;
    @Autowired private WorkOrderMapper workOrderMapper;
    @Autowired private WorkOrderLogMapper workOrderLogMapper;

    // ─────────────────────── 主流程 ───────────────────────

    @Test
    @DisplayName("24 例 × 2 方案 × 3 次 = 144 次；产出统计 + 失败清单 + 失败保留证明")
    void holdoutPairedStubRun() throws Exception {
        List<JsonNode> cases = loadCases();
        assertEquals(24, cases.size(), "冻结集必须是 24 条（分母固定）");

        InvestigationAgent agent = new InvestigationAgent(wiredModel, tools, limits, OrderFactsTool.NAME,
                finalReview, permissionRecheck);
        FixedFlowInvestigator fixed = new FixedFlowInvestigator(tools, limits, finalReview, permissionRecheck);

        List<Row> rows = new ArrayList<>();
        for (int rep = 1; rep <= REPEATS; rep++) {
            for (int i = 0; i < cases.size(); i++) {
                JsonNode evalCase = cases.get(i);
                materialize(evalCase, i);           // 每次运行前重建该例夹具 → 同一数据快照
                rows.add(runOne("fixed", rep, evalCase, i, fixed, agent, false));
                rows.add(runOne("agent", rep, evalCase, i, fixed, agent, true));
            }
        }
        assertEquals(24 * 2 * REPEATS, rows.size(), "必须跑满 144 次（失败与超时保留）");

        assertDeterministic(rows);
        List<Row> failures = rows.stream().filter(r -> !r.contractPass()).toList();
        assertFalse(failures.isEmpty(),
                "本轮**必须**留下失败（桩对任何输入返回固定值）——否则说明失败被吞了");
        assertFailureRetained(rows, cases);

        collectRowCounts();
        Files.createDirectories(RESULTS.getParent());
        Files.writeString(RESULTS, render(rows, failures, cases), StandardCharsets.UTF_8);
        System.out.println("[holdout] 结果已写入 " + RESULTS + "（失败 " + failures.size() + " / " + rows.size() + "）");
    }

    private Row runOne(String scheme, int rep, JsonNode evalCase, int index,
                       FixedFlowInvestigator fixed, InvestigationAgent agent, boolean agentPath) {
        String id = evalCase.get("id").asText();
        String question = evalCase.get("question").asText();
        String orderRef = evalCase.get("order_ref").asText();
        FixtureIds ids = ids(index);

        List<String> fixtureProblems = verifyFixture(evalCase, index);
        if (!fixtureProblems.isEmpty()) {
            return new Row(scheme, rep, id, evalCase.get("expect_terminal").asText(),
                    "未执行（fixture 自检不过）", evalCase.get("expect_problem_type").asText(), null,
                    0, 0L, false, false, false, 0, 0, "（未执行）", fixtureProblems);
        }

        // 与受理层**同一个 ToolContext 构造路径**（§11-2 / D79）：部门范围走 resolveDepartmentScope。
        WorkOrderService.DepartmentScope scope = workOrderService.resolveDepartmentScope(ids.callerId());
        if (!scope.isDepartment()) {
            // 本轮 24 例的调用者都是 DEPT_ADMIN；这里只兜底，避免静默按错误范围跑。
            return new Row(scheme, rep, id, evalCase.get("expect_terminal").asText(),
                    "FAILED(FORBIDDEN)", evalCase.get("expect_problem_type").asText(), null,
                    0, 0L, false, false, false, 0, 0, "受理期就拒绝（拿不到部门范围）", List.of());
        }
        ToolContext ctx = ToolContext.ofDepartment("holdout-" + scheme + "-" + id + "-" + rep,
                String.valueOf(ids.callerId()), String.valueOf(scope.deptId()));

        if (agentPath) {
            configureInjection(evalCase, index, ids, orderRef);
        }
        long started = System.nanoTime();
        AgentRunResult result = agentPath
                ? agent.investigate(ctx, orderRef, question)
                : fixed.investigate(ctx, orderRef, question);
        long millis = (System.nanoTime() - started) / 1_000_000L;

        String code = result.failure() == null ? null : result.failure().code();
        String actualTerminal = result.status().name() + (code == null ? "" : "(" + code + ")");
        return evaluate(scheme, rep, id, evalCase, actualTerminal, result.report(), result.evidence(),
                result.toolCalls(), millis);
    }

    /** 按用例的 injection / 场景选桩模式；普通槽走 transcript 驱动。 */
    private void configureInjection(JsonNode evalCase, int index, FixtureIds ids, String orderRef) {
        requestInRun.set(0);
        retriesSoFar.set(0);
        callerIdForInjection = ids.callerId();
        peerOrderNoForInjection = null;
        JsonNode fixture = evalCase.get("fixture");
        if (!fixture.has("injection")) {
            injection = Injection.NORMAL;
            return;
        }
        String injectionText = fixture.get("injection").asText();
        String id = evalCase.get("id").asText();
        injection = switch (id) {
            case "16" -> Injection.MUTATE_PERM;
            case "17" -> {
                peerOrderNoForInjection = peerOrderNo(index);
                yield Injection.MUTATE_PEER;
            }
            case "19" -> Injection.ILLEGAL_BATCH;
            case "20" -> Injection.REPEAT_SAME_TOOL;
            case "21" -> Injection.ERROR_429_ONCE;
            case "22" -> Injection.ERROR_401;
            case "24" -> Injection.MUTATE_DEADLINE;
            default -> Injection.NORMAL;
        };
        System.out.println("[holdout] 槽 " + id + " 注入：" + injection + "（" + injectionText + "）");
    }

    // ─────────────────────── 评分 ───────────────────────

    private Row evaluate(String scheme, int rep, String id, JsonNode evalCase, String actualTerminal,
                         AgentReport report, List<AgentEvidence> evidence, int toolCalls, long millis) {
        String expectedTerminal = evalCase.get("expect_terminal").asText();
        String expectedType = evalCase.get("expect_problem_type").asText();
        String actualType = report == null ? null : report.problemType().name();

        boolean terminalMatch = expectedTerminal.equals(actualTerminal);
        boolean typeMatch = "N/A".equals(expectedType) || (actualType != null && actualType.equals(expectedType));

        List<String> mustCover = textList(evalCase.get("must_cover_facts"));
        List<String> citedFacts = report == null ? List.of() : citedFacts(report, evidence);
        long covered = mustCover.stream().filter(citedFacts::contains).count();
        boolean factsOk = mustCover.stream().allMatch(citedFacts::contains);
        boolean contractPass = terminalMatch && typeMatch && factsOk;

        String detail = "报告=" + (report == null ? "（无）"
                : report.problemType() + "/evidence=" + report.evidenceIds() + "/suggestions=" + report.suggestionIds())
                + "；引用事实=" + citedFacts;
        return new Row(scheme, rep, id, expectedTerminal, actualTerminal, expectedType, actualType,
                toolCalls, millis, terminalMatch, typeMatch, factsOk, covered, mustCover.size(),
                detail, List.of());
    }

    /** 报告引用的证据编号 → 事实键。**判据只落在证据的 fact 上**（README §5）。 */
    private List<String> citedFacts(AgentReport report, List<AgentEvidence> evidence) {
        Map<String, AgentEvidence> byId = new LinkedHashMap<>();
        for (AgentEvidence item : evidence) {
            byId.put(item.id(), item);
        }
        List<String> facts = new ArrayList<>();
        for (String evidenceId : report.evidenceIds()) {
            AgentEvidence item = byId.get(evidenceId);
            if (item != null) {
                facts.add(item.fact());
            }
        }
        return facts;
    }

    // ─────────────────────── 夹具物化 ───────────────────────

    private void materialize(JsonNode evalCase, int index) {
        FixtureIds ids = ids(index);
        cleanupCase(index, evalCase);

        JsonNode fixture = evalCase.get("fixture");
        JsonNode caller = fixture.get("caller");
        long callerDept = dept(caller.get("dept").asText());
        insertUser(ids.callerId(), "holdout-caller-" + evalCase.get("id").asText(), callerDept);
        for (JsonNode role : caller.get("roles")) {
            bindRole(ids.callerId(), ROLE_IDS.get(role.asText()));
        }

        JsonNode mainOrder = fixture.get("main_order");
        long submitterDept = dept(mainOrder.get("submitter_dept").asText());
        insertUser(ids.submitterId(), "holdout-submitter-" + evalCase.get("id").asText(), submitterDept);

        String assigneeSpec = mainOrder.get("assignee").asText();
        Long assigneeId;
        if (assigneeSpec.contains("t_user 无该行")) {
            assigneeId = ids.ghostAssigneeId();
        } else if (assigneeSpec.contains("NULL") || assigneeSpec.startsWith("无")) {
            assigneeId = null;
        } else {
            insertUser(ids.assigneeId(), "holdout-handler-" + evalCase.get("id").asText(), submitterDept);
            assigneeId = ids.assigneeId();
        }

        String slaSpec = mainOrder.get("sla_deadline").asText();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime slaDeadline;
        if (slaSpec.contains("NULL")) {
            slaDeadline = null;
        } else if (slaSpec.contains("已过") || slaSpec.contains("T0-")) {
            slaDeadline = now.minusHours(1);
        } else if (slaSpec.contains("T0+2h")) {
            slaDeadline = now.plusHours(2);
        } else {
            slaDeadline = now.plusHours(4);
        }

        WorkOrder order = new WorkOrder();
        order.setOrderNo(mainOrder.get("order_no").asText());
        order.setTitle("holdout:" + evalCase.get("id").asText());
        order.setContent(mainOrder.has("remark") ? mainOrder.get("remark").asText() : evalCase.get("question").asText());
        order.setType("NETWORK");
        order.setPriority(0);
        order.setStatus(mainOrder.get("status").asText());
        order.setSubmitterId(ids.submitterId());
        order.setAssigneeId(assigneeId);
        order.setRejectCount(0);
        order.setMaxReject(3);
        order.setTriageStatus(mainOrder.has("triage_status") && mainOrder.get("triage_status").asText().startsWith("DONE")
                ? "DONE" : "DONE");
        order.setVersion(0);
        order.setSlaDeadline(slaDeadline);
        order.setCreatedAt(now.minusDays(10));
        order.setUpdatedAt(now);
        workOrderMapper.insert(order);

        String logs = mainOrder.get("logs").asText();
        boolean noHandlingLogs = logs.startsWith("空") || logs.contains("无 ACCEPT/ASSIGN/RELEASE/MANAGE");
        if (noHandlingLogs) {
            // 有意留空
        } else if (logs.contains("RELEASE")) {
            insertUser(ids.secondHandlerId(), "holdout-handler2-" + evalCase.get("id").asText(), submitterDept);
            insertLog(order, assigneeId, "ACCEPT", now.minusDays(2));
            insertLog(order, 0L, "RELEASE", now.minusDays(1));
            insertLog(order, ids.secondHandlerId(), "ACCEPT", now.minusHours(6));
        } else if (logs.contains("ACCEPT") || logs.contains("MANAGE")) {
            insertLog(order, assigneeId == null ? ids.submitterId() : assigneeId, "ACCEPT", now.minusHours(6));
        } else if (logs.contains("SUBMIT")) {
            insertLog(order, ids.submitterId(), "SUBMIT", now.minusDays(3));
        }

        materializePeers(evalCase, index, assigneeId, submitterDept, now);
    }

    /** 对照数据：槽 04（30 张同处理人未结单）、槽 11（同提交人近期单）、槽 17（1 张同处理人对照单）。 */
    private void materializePeers(JsonNode evalCase, int index, Long assigneeId, long deptId, LocalDateTime now) {
        String id = evalCase.get("id").asText();
        FixtureIds ids = ids(index);
        if ("04".equals(id) && assigneeId != null) {
            for (int k = 0; k < 30; k++) {
                long peerSubmitter = ids.peerSubmitterId() + k;
                insertUser(peerSubmitter, "holdout-peer-" + index + "-" + k, deptId);
                insertPeerOrder(index, k, peerSubmitter, assigneeId, "IN_PROGRESS", now.minusDays(5));
            }
        } else if ("11".equals(id)) {
            for (int k = 0; k < 3; k++) {
                // SAME_SUBMITTER_RECENT：锚在**主单提交人**上 → 这些单的提交人 = 主单提交人
                insertPeerOrder(index, 100 + k, ids.submitterId(), ids.assigneeId(), "IN_PROGRESS", now.minusDays(3));
            }
        } else if ("17".equals(id) && assigneeId != null) {
            long peerSubmitter = ids.peerSubmitterId() + 200;
            insertUser(peerSubmitter, "holdout-peer-" + index + "-200", deptId);
            insertPeerOrder(index, 200, peerSubmitter, assigneeId, "IN_PROGRESS", now.minusDays(4));
        }
    }

    private void insertPeerOrder(int index, int k, long submitterId, Long assigneeId, String status, LocalDateTime createdAt) {
        WorkOrder peer = new WorkOrder();
        peer.setOrderNo("WO-20261005-" + (90000 + index * 100 + k));
        peer.setTitle("holdout-peer:" + index + "-" + k);
        peer.setContent("对照单");
        peer.setType("NETWORK");
        peer.setPriority(0);
        peer.setStatus(status);
        peer.setSubmitterId(submitterId);
        peer.setAssigneeId(assigneeId);
        peer.setRejectCount(0);
        peer.setMaxReject(3);
        peer.setTriageStatus("DONE");
        peer.setVersion(0);
        peer.setSlaDeadline(createdAt.plusDays(2));
        peer.setCreatedAt(createdAt);
        peer.setUpdatedAt(createdAt);
        workOrderMapper.insert(peer);
    }

    private String peerOrderNo(int index) {
        return "WO-20261005-" + (90000 + index * 100 + 200);
    }

    private void cleanupCase(int index, JsonNode evalCase) {
        // 每次运行前重建该例 → 同一数据快照；这里删掉该例的工单与用户，避免三次重复之间互相污染。
        FixtureIds ids = ids(index);
        String orderNo = evalCase.get("fixture").get("main_order").get("order_no").asText();
        // 对照单按 **title 前缀** 删（order_no 的数值块不是稳定前缀，按 order_no 前缀删会漏）。
        for (WorkOrder order : workOrderMapper.selectList(new LambdaQueryWrapper<WorkOrder>()
                .likeRight(WorkOrder::getTitle, "holdout-peer:" + index + "-"))) {
            workOrderLogMapper.delete(new LambdaQueryWrapper<WorkOrderLog>().eq(WorkOrderLog::getOrderId, order.getId()));
            workOrderMapper.deleteById(order.getId());
        }
        workOrderMapper.delete(new LambdaQueryWrapper<WorkOrder>().eq(WorkOrder::getOrderNo, orderNo));
        userRoleMapper.delete(new LambdaQueryWrapper<UserRole>()
                .ge(UserRole::getUserId, ids.callerId()).le(UserRole::getUserId, ids.callerId() + 500));
        userMapper.delete(new LambdaQueryWrapper<User>()
                .ge(User::getId, ids.callerId()).le(User::getId, ids.callerId() + 500));
    }

    private void insertUser(long id, String username, long deptId) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setPassword("$2a$10$1s93/XO7m.kI61bcmONyRutCPPMw9hqxd14syjk.8G/82JKi9HVIe");
        user.setDeptId(deptId);
        user.setStatus(1);
        userMapper.insert(user);
    }

    private void bindRole(long userId, Long roleId) {
        UserRole binding = new UserRole();
        binding.setUserId(userId);
        binding.setRoleId(roleId);
        userRoleMapper.insert(binding);
    }

    private void insertLog(WorkOrder order, Long operatorId, String action, LocalDateTime at) {
        WorkOrderLog log = new WorkOrderLog();
        log.setOrderId(order.getId());
        log.setOrderNo(order.getOrderNo());
        log.setOperatorId(operatorId);
        log.setAction(action);
        log.setNewStatus(order.getStatus());
        log.setCreatedAt(at);
        workOrderLogMapper.insert(log);
    }

    // ─────────────────────── 夹具自检（描述 vs 库状态） ───────────────────────

    private List<String> verifyFixture(JsonNode evalCase, int index) {
        JsonNode mainOrder = evalCase.get("fixture").get("main_order");
        String orderNo = mainOrder.get("order_no").asText();
        List<String> problems = new ArrayList<>();
        WorkOrder order = workOrderMapper.selectOne(
                new LambdaQueryWrapper<WorkOrder>().eq(WorkOrder::getOrderNo, orderNo));
        if (order == null) {
            problems.add("库里没有这张单");
            return problems;
        }
        String logsSpec = mainOrder.get("logs").asText();
        List<WorkOrderLog> logs = workOrderLogMapper.selectList(
                new LambdaQueryWrapper<WorkOrderLog>().eq(WorkOrderLog::getOrderId, order.getId()));
        boolean describedEmpty = logsSpec.startsWith("空") || logsSpec.contains("无 ACCEPT/ASSIGN/RELEASE/MANAGE");
        if (describedEmpty && !logs.isEmpty()) {
            problems.add("描述为『空日志』，但库里有 " + logs.size() + " 条");
        }
        String assigneeSpec = mainOrder.get("assignee").asText();
        boolean describedUnassigned = assigneeSpec.contains("NULL") || assigneeSpec.startsWith("无");
        if (describedUnassigned && order.getAssigneeId() != null) {
            problems.add("描述为『未分配』，但库里 assignee_id=" + order.getAssigneeId());
        }
        String expectedDept = mainOrder.get("submitter_dept").asText();
        User submitter = order.getSubmitterId() == null ? null : userMapper.selectById(order.getSubmitterId());
        if (submitter == null) {
            problems.add("库里查不到提交人");
        } else if (submitter.getDeptId() == null || submitter.getDeptId() != dept(expectedDept)) {
            problems.add("提交人部门不符：描述 " + expectedDept + "，库里 " + submitter.getDeptId());
        }
        return problems;
    }

    // ─────────────────────── 判据 ───────────────────────

    private void assertDeterministic(List<Row> rows) {
        Map<String, String> first = new LinkedHashMap<>();
        for (Row row : rows) {
            String key = row.scheme + "|" + row.id;
            String fingerprint = row.actualTerminal + "|" + row.actualType;
            String previous = first.putIfAbsent(key, fingerprint);
            if (previous != null) {
                assertEquals(previous, fingerprint,
                        "确定性判据不过：" + key + " 三次重复的终态/类型不一致（" + previous + " vs " + fingerprint + "）");
            }
        }
    }

    /**
     * **失败保留证明**：故意造一条期望与实现不符的评分，断言它被判成失败并进入失败清单。
     * 这条**不进 144 的分母**——它是判分器本身的负向自检（"故意留一条失败用例证明失败不会被吞"）。
     */
    private void assertFailureRetained(List<Row> rows, List<JsonNode> cases) {
        JsonNode sample = cases.stream().filter(c -> "12".equals(c.get("id").asText())).findFirst().orElse(cases.get(0));
        Row sentinel = evaluate("fixed", 0, "SENTINEL-" + sample.get("id").asText(), withWrongExpectation(sample),
                "COMPLETED", null, List.of(), 0, 0L);
        assertFalse(sentinel.contractPass(), "负向自检：期望被故意改错，评分必须判失败（否则说明失败被吞了）");
        List<Row> withSentinel = new ArrayList<>(rows);
        withSentinel.add(sentinel);
        assertTrue(withSentinel.stream().anyMatch(r -> !r.contractPass()), "失败清单必须能列出失败");
        System.out.println("[holdout] 负向自检通过：故意改错的期望被判失败（" + sentinel.id + "）");
    }

    private static JsonNode withWrongExpectation(JsonNode evalCase) {
        com.fasterxml.jackson.databind.node.ObjectNode copy = evalCase.deepCopy();
        copy.put("expect_terminal", "FAILED(SENTINEL_DELIBERATE)");
        return copy;
    }

    // ─────────────────────── 结果渲染 ───────────────────────

    private String render(List<Row> rows, List<Row> failures, List<JsonNode> cases) {
        StringBuilder out = new StringBuilder();
        out.append("# S6 阶段 1：holdout harness 桩跑通记录（2026-10-07）\n\n");
        out.append("> ⚠ **这一遍不是成绩**：桩对任何输入返回固定值（与 `scripts/triage-eval.py` 文件头同一口径），\n");
        out.append("> 只能验证 **harness 本身**能跑完 144 次、能统计、能保留失败。**不是** fixed vs agent 的对照结论，\n");
        out.append("> **不是**模型能力，**不是**生产延迟（手册 L139：离线/桩延迟不代表生产延迟）。**holdout 仍未真正跑过。**\n\n");
        out.append("| 项 | 值 |\n| --- | --- |\n");
        out.append("| 日期 | ").append(LocalDate.now()).append(" |\n");
        out.append("| 用例文件 | `scripts/agent-eval-holdout.json`（冻结 24 例，**不改期望**） |\n");
        out.append("| 专用库 | `").append(HOLDOUT_DB).append("`（结构克隆自 `").append(SOURCE_DB)
                .append("` + `t_role`；跑完按 D19 最宽口径统计后 DROP） |\n");
        out.append("| 方案 | `fixed`（FixedFlowInvestigator）/ `agent`（InvestigationAgent，走**真实 HTTP 桩**） |\n");
        out.append("| 模型端点 | 本轮启动的**本地 HTTP 桩**（`llm.api.url` 指向它）；真实 HTTP 写读 + JSON 解析 + 有界重试都在链路上 |\n");
        out.append("| 比例 | 24 例 × 2 方案 × 3 次 = **").append(rows.size()).append(" 次**（分母固定；失败与超时保留） |\n");
        out.append("| 耗时口径 | 本机 + 桩 → **非生产延迟**（手册 L139） |\n\n");

        out.append("## 1. 汇总（分母固定 = 计划 run 数）\n\n");
        out.append("| 方案 | 计划 run | 通过 | 终态一致 | 类型一致 | 事实覆盖 | 失败 |\n");
        out.append("| --- | --- | --- | --- | --- | --- | --- |\n");
        for (String scheme : List.of("fixed", "agent")) {
            List<Row> group = rows.stream().filter(r -> r.scheme.equals(scheme)).toList();
            long pass = group.stream().filter(Row::contractPass).count();
            long terminal = group.stream().filter(r -> r.terminalMatch).count();
            long type = group.stream().filter(r -> r.typeMatch).count();
            long facts = group.stream().filter(r -> r.factsOk).count();
            out.append("| ").append(scheme).append(" | ").append(group.size()).append(" | ").append(pass)
                    .append(" | ").append(terminal).append(" | ").append(type).append(" | ").append(facts)
                    .append(" | ").append(group.size() - pass).append(" |\n");
        }
        long covered = rows.stream().mapToLong(Row::coveredFacts).sum();
        long mustCover = rows.stream().mapToLong(Row::mustCoverTotal).sum();
        out.append("\n> ⚠ **上表不是对照结论**：`agent` 侧的数字来自一个**固定返回 `ORDER_STATUS`** 的桩，\n");
        out.append("> `fixed` 侧的数字来自透明关键词表；两者都**不是**真模型成绩。差异只说明 harness 把两路都跑通了。\n");
        out.append("\n- 证据覆盖（必需事实被引用数 / 应覆盖数）：**").append(covered).append(" / ").append(mustCover).append("**\n");
        out.append("- 工具调用合计：**").append(rows.stream().mapToInt(Row::toolCalls).sum()).append("** 次\n");
        out.append("- 模型物理调用：agent 侧每次 ≈ 2 次（读根 + finish）——**桩固定行为**，不是模型选择\n");
        long[] latency = rows.stream().mapToLong(Row::millis).sorted().toArray();
        out.append("- 单次耗时 min/median/max：**").append(latency.length == 0 ? 0 : latency[0])
                .append(" / ").append(latency.length == 0 ? 0 : latency[latency.length / 2])
                .append(" / ").append(latency.length == 0 ? 0 : latency[latency.length - 1])
                .append(" ms**（本机 + 桩，**非生产延迟**）\n\n");

        out.append("## 2. 逐例结果（每例每方案 3 次全部保留）\n\n");
        out.append("| 方案 | 例 | 次 | 期望终态 | 实际终态 | 期望类型 | 实际类型 | 覆盖 | 工具调用 | 耗时(ms) | 通过 |\n");
        out.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (Row row : rows) {
            out.append("| ").append(row.scheme).append(" | ").append(row.id).append(" | ").append(row.rep)
                    .append(" | ").append(row.expectedTerminal).append(" | ").append(row.actualTerminal)
                    .append(" | ").append(row.expectedType)
                    .append(" | ").append(row.actualType == null ? "（无报告）" : row.actualType)
                    .append(" | ").append(row.coveredFacts).append("/").append(row.mustCoverTotal)
                    .append(" | ").append(row.toolCalls).append(" | ").append(row.millis)
                    .append(" | ").append(row.contractPass() ? "✅" : "❌").append(" |\n");
        }

        out.append("\n## 3. 完整失败清单（").append(failures.size()).append(" 条，不删难例）\n\n");
        out.append("> **为什么有这么多失败**：桩对任何输入返回固定值（固定 `ORDER_STATUS` + 主单三个必需事实），\n");
        out.append("> 所以**期望类型不是 `ORDER_STATUS`/`N/A` 的槽**天然判错——这正是「不是成绩」的直接证据；\n");
        out.append("> 它同时证明**失败被完整保留**（下面每条都在），没有被吞掉去凑好看。\n");
        out.append("> 另外，故障注入（16/17/19/20/22/24）只作用在 **agent 路径**（它是模型/执行期事件），\n");
        out.append("> 所以这些槽的 `fixed` 行显示 `COMPLETED` 属**驱动范围**，不是「fixed 少做了事」。\n\n");
        if (failures.isEmpty()) {
            out.append("- （无）——若真无失败，说明桩或判分器被写成了「必过」，需要复核。\n");
        } else {
            out.append("| 方案 | 例 | 次 | 期望 → 实际（终态） | 期望 → 实际（类型） | 覆盖 | 根因归类 |\n");
            out.append("| --- | --- | --- | --- | --- | --- | --- |\n");
            for (Row row : failures) {
                out.append("| ").append(row.scheme).append(" | ").append(row.id).append(" | ").append(row.rep)
                        .append(" | ").append(row.expectedTerminal).append(" → ").append(row.actualTerminal)
                        .append(" | ").append(row.expectedType).append(" → ")
                        .append(row.actualType == null ? "（无报告）" : row.actualType)
                        .append(" | ").append(row.coveredFacts).append("/").append(row.mustCoverTotal)
                        .append(" | ").append(cause(row)).append(" |\n");
            }
        }

        out.append("\n## 4. 确定性判据\n\n");
        out.append("- 同一批夹具 × 3 次：逐例终态与 problemType 一致（断言在 harness 里，失败会直接报错）：**通过**\n");
        out.append("- 桩是确定性的（按 transcript 决定，无随机）：**通过**\n\n");

        out.append("## 5. 失败保留证明（故意留一条失败用例）\n\n");
        out.append("- 除上面自然产生的失败外，harness 还做了一次**负向自检**：把某例的 `expect_terminal` 故意改成\n");
        out.append("  `FAILED(SENTINEL_DELIBERATE)`，断言判分器**判它失败**、且它**出现在失败清单里**。\n");
        out.append("  该自检**不进 144 的分母**——它证明的是判分器不吞失败，不是用例结果。**已通过**（见运行日志）。\n\n");

        out.append("## 6. 注入槽是怎么被驱动的（场景驱动，不是自然语言提问）\n\n");
        out.append("| 槽 | 场景 | 驱动方式 |\n| --- | --- | --- |\n");
        out.append("| 16 | 运行中撤权限 | 桩首轮回话**前**删掉调用者的 `DEPT_ADMIN` 绑定 → `PermissionRecheck` 下次工具调用前发现 → `CANCELLED(PERMISSION_REVOKED)` |\n");
        out.append("| 17 | 对照单调出部门 | 桩先让模型查对照单、再把对照单提交人调出部门、再 finish → `FinalReview` 复核发现 → `INCOMPLETE(STATE_CHANGED)` |\n");
        out.append("| 19 | 非法批次 | 桩回**同 callId 不同参数**的一轮 → `MODEL_PROTOCOL_ERROR`（该轮任何工具都不执行） |\n");
        out.append("| 20 | 无进展重复 | 桩**每轮都回同一工具同一参数** → 连续两轮无新证据 → `NO_PROGRESS` |\n");
        out.append("| 21 | 429 可恢复 | 桩首次回 429 → `HttpAgentModel` 有界重试 → 其后正常收尾 |\n");
        out.append("| 22 | 401 | 桩回 401 → 非 429 的 4xx **不重试** → `MODEL_HTTP_ERROR` |\n");
        out.append("| 23 | 工具故障 / 池饱和 | **本轮未驱动**：没有可注入故障的工具边界；本槽的「名额不提前释放」由 `InvestigationConcurrencyTest`（5 条）覆盖，「与成功空集区分」由 `TOOL_FAILED` 路径覆盖——真机端到端留 S6 后段 |\n");
        out.append("| 24 | 运行中状态变化 | 桩首轮回话**前**改 `sla_deadline` → `FinalReview` 逐字段比对发现 → `INCOMPLETE(STATE_CHANGED)` |\n\n");

        out.append("## 7. 真模型那一遍的成本与时长估算（供委托方批准）\n\n");
        out.append("| 项 | 估算 | 依据 |\n| --- | --- | --- |\n");
        out.append("| 计划调查次数 | 24 × 2 × 3 = **144** | 手册 L145 |\n");
        out.append("| 其中会用模型的 | **72**（agent 侧；fixed 无模型调用） | 基线是确定性流程 |\n");
        out.append("| 物理模型调用 | 约 **144–160**（每 run ≈ 2 次 + 少量重试） | 本 harness 桩跑通的调用形态 |\n");
        out.append("| token 量级 | 输入 ≈ **35 万–60 万**、输出 ≈ **3 万**（粗估） | 每次调用含系统提示 + 工具定义 + 累积 transcript |\n");
        out.append("| 墙钟（顺序） | ≈ **10–25 分钟** | D89 实测单次调用 2.2–26.1s、median ≈ 3s；144–160 次 + 重试 |\n");
        out.append("\n> **费用口径**：按供应商当期价目 × 实际 token（跑完以 usage 为准，**不凭记忆估**，手册 §6.1）。\n");
        out.append("> **与 S5 的分工**：S5 验的是**资源与主业务影响**（不泄漏、有界、主业务错误 0）；\n");
        out.append("> S6 验的是**质量与收益**（保留全部失败的成对对照）。两者证据不能互相替代。\n\n");

        out.append("## 8. D19 清理留痕（先按最宽口径统计再 DROP）\n\n");
        out.append("统计口径 = **专用库里每一张表都数一遍**：\n\n| 表 | 行数 |\n| --- | --- |\n");
        ROW_COUNTS_BEFORE_DROP.forEach((table, count) -> out.append("| ").append(table).append(" | ").append(count).append(" |\n"));
        out.append("\n- 清理动作：`DROP DATABASE ").append(HOLDOUT_DB)
                .append("`（只作用于本轮自建的、名字带 `work_order_holdout` 前缀的库）\n");
        out.append("- 共享的 `").append(SOURCE_DB).append("` 与业务库**未被写入**（只读了表结构 + `t_role`）\n");
        return out.toString();
    }

    private static String cause(Row row) {
        if (!row.terminalMatch) {
            return "终态不符（实现/驱动）";
        }
        if (!row.typeMatch) {
            return "类型不符（fixed=透明关键词表 / agent=桩固定 `ORDER_STATUS`）——**桩不是成绩**";
        }
        if (!row.factsOk) {
            return "必需事实未被引用（fixed=基线的证据取舍 / agent=桩只引主单三事实）";
        }
        return "—";
    }

    // ─────────────────────── 收尾 ───────────────────────

    private static void collectRowCounts() throws Exception {
        try (Connection conn = DriverManager.getConnection(serverJdbcUrl(), DB_USER, DB_PASSWORD);
             Statement st = conn.createStatement()) {
            for (String table : TABLES) {
                try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM `" + HOLDOUT_DB + "`.`" + table + "`")) {
                    ROW_COUNTS_BEFORE_DROP.put(table, rs.next() ? rs.getInt(1) : 0);
                }
            }
        }
    }

    @AfterAll
    static void dropHoldoutDatabase() {
        if (!HOLDOUT_DB.startsWith("work_order_holdout")) {
            throw new IllegalStateException("拒绝删除非 holdout 库：" + HOLDOUT_DB);
        }
        try {
            collectRowCounts();
            try (Connection conn = DriverManager.getConnection(serverJdbcUrl(), DB_USER, DB_PASSWORD);
                 Statement st = conn.createStatement()) {
                st.execute("DROP DATABASE IF EXISTS `" + HOLDOUT_DB + "`");
                System.out.println("[holdout] 已 DROP 专用库 " + HOLDOUT_DB);
            }
        } catch (Exception e) {
            System.out.println("[holdout] 清理失败（需人工确认 " + HOLDOUT_DB + "）：" + e.getMessage());
        }
        if (stubServer != null) {
            stubServer.stop(0);
        }
    }

    // ─────────────────────── 小工具 ───────────────────────

    private static List<JsonNode> loadCases() throws Exception {
        JsonNode root = MAPPER.readTree(Files.readString(CASES, StandardCharsets.UTF_8));
        List<JsonNode> cases = new ArrayList<>();
        root.get("cases").forEach(cases::add);
        return cases;
    }

    private static List<String> textList(JsonNode array) {
        List<String> list = new ArrayList<>();
        if (array != null) {
            array.forEach(node -> list.add(node.asText()));
        }
        return list;
    }

    private static long dept(String label) {
        return switch (label) {
            case "DEPT-A" -> DEPT_A;
            case "DEPT-B" -> DEPT_B;
            default -> throw new IllegalArgumentException("未知部门：" + label);
        };
    }

    private static FixtureIds ids(int index) {
        long base = 920_000L + index * 10L;
        return new FixtureIds(base, base + 1, base + 2, base + 3, base + 4, base + 5);
    }

    private record FixtureIds(long callerId, long submitterId, long assigneeId, long secondHandlerId,
                              long ghostAssigneeId, long peerSubmitterId) {
    }

    private record Row(String scheme, int rep, String id, String expectedTerminal, String actualTerminal,
                       String expectedType, String actualType, int toolCalls, long millis,
                       boolean terminalMatch, boolean typeMatch, boolean factsOk,
                       long coveredFacts, long mustCoverTotal, String detail, List<String> fixtureProblems) {
        boolean contractPass() {
            return fixtureProblems.isEmpty() && terminalMatch && typeMatch && factsOk;
        }
    }
}
