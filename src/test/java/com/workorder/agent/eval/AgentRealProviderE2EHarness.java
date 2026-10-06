package com.workorder.agent.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.workorder.agent.AgentInvestigationService;
import com.workorder.agent.AgentLimits;
import com.workorder.agent.AgentModel;
import com.workorder.agent.AgentModelException;
import com.workorder.agent.AgentReportRenderer;
import com.workorder.agent.AgentToolRegistry;
import com.workorder.agent.HttpAgentModel;
import com.workorder.agent.InvestigationAgent;
import com.workorder.agent.ModelTurn;
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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * **真供应商端到端**（本机 harness，类名不带 `Test` → 默认 `mvn test` 不会跑它）。
 *
 * <p>只做一件事：用**真 endpoint + 真 key**（`deepseek-flash`）把
 * 「受理层 → ToolContext → agent 循环 → 真工具（本机库）→ 校验 → 渲染」走一遍，
 * 如实记录第一手结果（含不顺的地方）。
 *
 * <p><b>key 从哪来</b>：优先环境变量 `LLM_API_URL` / `LLM_API_KEY` / `LLM_MODEL`；
 * 没有就读 `%TEMP%\wo-llm-probe.env`（仓库外）。**不打印、不写进任何受版本控制的文件**。
 *
 * <p><b>库</b>：用**专用库 `work_order_e2e`**（结构克隆自 `work_order_test` + `t_role` 参考行），
 * **不写** `work_order_test`、**不碰**业务库；跑完按 D19 最宽口径逐表统计后 `DROP DATABASE`。
 *
 * <p><b>怎么跑</b>：
 * <pre>
 * $env:MYSQL_PORT='3307'; $env:REDIS_PORT='6380'; $env:MYSQL_PASSWORD='&lt;deploy/.env&gt;'
 * mvn -o test "-Dtest=AgentRealProviderE2EHarness"
 * </pre>
 */
@SpringBootTest(properties = {
        "agent.investigation.enabled=true",
        "workorder.outbox.dispatch.enabled=false"
})
@ActiveProfiles("test")
@DisplayName("真供应商端到端（本机 harness）")
class AgentRealProviderE2EHarness {

    private static final String SOURCE_DB = "work_order_test";
    private static final String E2E_DB = "work_order_e2e";
    private static final String ORDER_NO = "WO-20261006-70001";
    private static final long DEPT = 7001L;
    private static final long CALLER_ID = 810001L;     // DEPT_ADMIN（受理层只放行它）
    private static final long SUBMITTER_ID = 810002L;
    private static final long ASSIGNEE_ID = 810003L;
    private static final long DEPT_ADMIN_ROLE_ID = 4L;
    private static final long HANDLER_ROLE_ID = 3L;
    private static final long SUBMITTER_ROLE_ID = 2L;

    private static final Path RESULTS = Path.of("docs", "agent-eval", "real-provider-e2e-20261006.md");
    private static final Map<String, Integer> ROW_COUNTS_BEFORE_DROP = new TreeMap<>();
    private static final List<String> TABLES = new ArrayList<>();

    @Autowired private HttpAgentModel wiredModel;
    @Autowired private AgentToolRegistry tools;
    @Autowired private AgentLimits limits;
    @Autowired private AgentReportRenderer renderer;
    @Autowired private WorkOrderService workOrderService;
    @Autowired private UserMapper userMapper;
    @Autowired private UserRoleMapper userRoleMapper;
    @Autowired private WorkOrderMapper workOrderMapper;
    @Autowired private WorkOrderLogMapper workOrderLogMapper;

    // ─────────────────────────── 专用库（上下文启动前） ───────────────────────────

    @DynamicPropertySource
    static void e2eDatabaseAndProvider(DynamicPropertyRegistry registry) throws Exception {
        prepareDatabase();
        registry.add("spring.datasource.url", AgentRealProviderE2EHarness::jdbcUrl);
        registry.add("llm.api.url", () -> providerValue("LLM_API_URL"));
        registry.add("llm.api.key", () -> providerValue("LLM_API_KEY"));
        registry.add("llm.api.model", () -> providerValue("LLM_MODEL"));
        registry.add("agent.investigation.mode", () -> "agent");
    }

    /** 环境变量优先，其次仓库外的临时 env 文件；**只返回值，不打印**。 */
    private static String providerValue(String name) {
        String fromEnv = System.getenv(name);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        Path envFile = Path.of(System.getProperty("java.io.tmpdir"), "wo-llm-probe.env");
        try {
            if (Files.exists(envFile)) {
                for (String line : Files.readAllLines(envFile, StandardCharsets.UTF_8)) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith(name + "=")) {
                        return trimmed.substring(name.length() + 1).trim();
                    }
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("读临时 env 文件失败：" + e.getMessage());
        }
        throw new IllegalStateException("缺 " + name + "（环境变量或 %TEMP%\\wo-llm-probe.env）");
    }

    private static void prepareDatabase() throws Exception {
        if (!E2E_DB.startsWith("work_order_e2e")) {
            throw new IllegalStateException("拒绝操作非 e2e 库：" + E2E_DB);
        }
        try (Connection conn = DriverManager.getConnection(serverJdbcUrl(), user(), password());
             Statement st = conn.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS `" + E2E_DB + "`");
            st.execute("CREATE DATABASE `" + E2E_DB + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            try (ResultSet rs = conn.createStatement().executeQuery(
                    "SELECT table_name FROM information_schema.tables WHERE table_schema='" + SOURCE_DB
                            + "' AND table_type='BASE TABLE' ORDER BY table_name")) {
                while (rs.next()) {
                    TABLES.add(rs.getString(1));
                }
            }
            for (String table : TABLES) {
                st.execute("CREATE TABLE `" + E2E_DB + "`.`" + table + "` LIKE `" + SOURCE_DB + "`.`" + table + "`");
            }
            st.execute("INSERT INTO `" + E2E_DB + "`.`t_role` SELECT * FROM `" + SOURCE_DB + "`.`t_role`");
            System.out.println("[e2e] 专用库 " + E2E_DB + " 就绪（克隆表 " + TABLES.size() + " 张 + t_role 参考行）");
        }
    }

    private static String user() {
        String v = System.getenv("MYSQL_USER");
        return v == null || v.isBlank() ? "root" : v;
    }

    private static String password() {
        String v = System.getenv("MYSQL_PASSWORD");
        return v == null ? "" : v;
    }

    private static String hostPort() {
        return System.getenv().getOrDefault("MYSQL_HOST", "localhost") + ":"
                + System.getenv().getOrDefault("MYSQL_PORT", "3306");
    }

    private static String serverJdbcUrl() {
        return "jdbc:mysql://" + hostPort()
                + "/?useUnicode=true&characterEncoding=utf8&connectionTimeZone=%2B08:00&useSSL=false&allowPublicKeyRetrieval=true";
    }

    private static String jdbcUrl() {
        return "jdbc:mysql://" + hostPort() + "/" + E2E_DB
                + "?useUnicode=true&characterEncoding=utf8&connectionCollation=utf8mb4_unicode_ci"
                + "&connectionTimeZone=%2B08:00&forceConnectionTimeZoneToSession=true&useSSL=false&allowPublicKeyRetrieval=true";
    }

    // ─────────────────────────── 主流程 ───────────────────────────

    @Test
    @DisplayName("真链路：受理层 → agent 循环 → 真工具 → 校验 → 渲染（如实记录）")
    void realProviderEndToEnd() throws Exception {
        seedFixture();

        RecordingModel model = new RecordingModel(wiredModel);
        InvestigationAgent agent = new InvestigationAgent(model, tools, limits);
        AgentInvestigationService service = new AgentInvestigationService(agent, null, renderer, "agent", workOrderService);

        String question = "工单 " + ORDER_NO + " 现在到哪一步了？之前是谁处理过？最近几条记录里没有那次转手，帮我看看更早的记录。";
        long started = System.nanoTime();
        AgentInvestigationService.Outcome outcome = service.investigate(CALLER_ID, ORDER_NO, question);
        long millis = (System.nanoTime() - started) / 1_000_000L;

        int evidenceCount = outcome.report() == null ? 0 : outcome.report().evidenceIds().size();
        List<String> sections = List.of("【已核实事实】", "【证据缺口】", "【下一步核实建议】");
        boolean threeSections = outcome.renderedText() != null
                && sections.stream().allMatch(outcome.renderedText()::contains);

        // 断言（缺一不可）
        assertEquals("COMPLETED", outcome.status(), () -> "失败码=" + outcome.failureCode() + " 模型调用=" + model.summary());
        assertNotNull(outcome.report(), "COMPLETED 必须有报告");
        assertTrue(evidenceCount > 0, "证据条数必须 > 0（说明模型确实调了工具）：" + model.summary());
        assertTrue(threeSections, "渲染文本必须三段齐全：\n" + outcome.renderedText());
        assertTrue(model.allHttpOk(), "本次端到端出现了 4xx/5xx：" + model.summary());

        collectRowCounts();   // D19：先把"每张表各多少行"统计进记录，再在 @AfterAll 里 DROP
        String record = render(model, outcome, evidenceCount, millis, question);
        Files.createDirectories(RESULTS.getParent());
        Files.writeString(RESULTS, record, StandardCharsets.UTF_8);
        System.out.println("[e2e] 结果已写入 " + RESULTS);
        System.out.println("[e2e] 终态=" + outcome.status() + " 证据=" + evidenceCount
                + " 模型调用=" + model.calls.size() + " 耗时=" + millis + "ms");
    }

    private void seedFixture() {
        user(CALLER_ID, "e2e-dept-admin", DEPT, DEPT_ADMIN_ROLE_ID);
        user(SUBMITTER_ID, "e2e-submitter", DEPT, SUBMITTER_ROLE_ID);
        user(ASSIGNEE_ID, "e2e-handler", DEPT, HANDLER_ROLE_ID);

        LocalDateTime now = LocalDateTime.now();
        WorkOrder order = new WorkOrder();
        order.setOrderNo(ORDER_NO);
        order.setTitle("e2e:真供应商端到端");
        order.setContent("真供应商端到端夹具");
        order.setType("NETWORK");
        order.setPriority(0);
        order.setStatus("IN_PROGRESS");
        order.setSubmitterId(SUBMITTER_ID);
        order.setAssigneeId(ASSIGNEE_ID);
        order.setRejectCount(0);
        order.setMaxReject(3);
        order.setTriageStatus("DONE");
        order.setVersion(0);
        order.setSlaDeadline(now.plusHours(4));
        order.setCreatedAt(now.minusDays(10));
        order.setUpdatedAt(now);
        workOrderMapper.insert(order);

        // 25 条处理类日志：既形成接单序列，也让 read_earlier_events 有"更早一页"可翻
        for (int i = 1; i <= 25; i++) {
            WorkOrderLog log = new WorkOrderLog();
            log.setOrderId(order.getId());
            log.setOrderNo(ORDER_NO);
            log.setAction(i % 5 == 0 ? "RELEASE" : (i % 2 == 0 ? "ACCEPT" : "ASSIGN"));
            log.setOperatorId(i % 5 == 0 ? 0L : ASSIGNEE_ID);
            log.setNewStatus("IN_PROGRESS");
            log.setCreatedAt(now.minusDays(9).plusHours(i));
            workOrderLogMapper.insert(log);
        }
    }

    private void user(long id, String username, long deptId, long roleId) {
        User u = new User();
        u.setId(id);
        u.setUsername(username);
        u.setPassword("$2a$10$1s93/XO7m.kI61bcmONyRutCPPMw9hqxd14syjk.8G/82JKi9HVIe");
        u.setDeptId(deptId);
        u.setStatus(1);
        userMapper.insert(u);
        UserRole binding = new UserRole();
        binding.setUserId(id);
        binding.setRoleId(roleId);
        userRoleMapper.insert(binding);
    }

    // ─────────────────────────── 记录输出 ───────────────────────────

    private String render(RecordingModel model, AgentInvestigationService.Outcome outcome,
                          int evidenceCount, long millis, String question) {
        StringBuilder out = new StringBuilder();
        out.append("# 真供应商端到端（本机第一手记录）\n\n");
        out.append("| 项 | 值 |\n| --- | --- |\n");
        out.append("| 日期 | ").append(LocalDate.now()).append(" |\n");
        out.append("| 模型 | `").append(providerValue("LLM_MODEL")).append("`（真 endpoint，非桩） |\n");
        out.append("| endpoint | `").append(providerValue("LLM_API_URL")).append("`（**key 不回显**） |\n");
        out.append("| 链路 | 受理层 `AgentInvestigationService.investigate(userId, orderNo, question)` → `mode=agent` → 真工具（本机库）→ 校验 → 渲染 |\n");
        out.append("| 专用库 | `").append(E2E_DB).append("`（结构克隆自 `").append(SOURCE_DB).append("`；跑完按 D19 最宽口径统计后 DROP） |\n");
        out.append("| 问题原文 | ").append(question).append(" |\n");
        out.append("| 终态 | **").append(outcome.status()).append("**").append(outcome.failureCode() == null ? "" : "（" + outcome.failureCode() + "）").append(" |\n");
        out.append("| 证据条数 | **").append(evidenceCount).append("** |\n");
        out.append("| 报告字段 | ").append(outcome.report() == null ? "（无报告）"
                : "problemType=`" + outcome.report().problemType() + "`、evidenceIds=" + outcome.report().evidenceIds()
                + "、suggestionIds=" + outcome.report().suggestionIds()).append(" |\n");
        out.append("| 渲染三段 | ").append(outcome.renderedText() == null ? "（无文本）"
                : (outcome.renderedText().contains("【已核实事实】") && outcome.renderedText().contains("【证据缺口】")
                && outcome.renderedText().contains("【下一步核实建议】") ? "齐全" : "**缺失**")).append(" |\n");
        out.append("| 模型调用次数 | **").append(model.calls.size()).append("** |\n");
        out.append("| 4xx/5xx | **").append(model.allHttpOk() ? "无" : "有（见下表）").append("** |\n");
        out.append("| 端到端耗时 | **").append(millis).append(" ms**（真实供应商 + 本机库；**不是**桩延迟，也不是生产环境数字） |\n\n");

        out.append("## 逐次模型调用（第一手）\n\n");
        out.append("| # | 结果 | 该轮 tool_calls 数 | 响应字节 | 耗时(ms) | 失败码 |\n| --- | --- | --- | --- | --- | --- |\n");
        int i = 1;
        for (RecordingModel.Call call : model.calls) {
            out.append("| ").append(i++).append(" | ").append(call.ok ? "OK" : "**FAIL**")
                    .append(" | ").append(call.toolCalls).append(" | ").append(call.bytes)
                    .append(" | ").append(call.millis).append(" | ").append(call.errorCode == null ? "—" : call.errorCode)
                    .append(" |\n");
        }

        out.append("\n## 渲染文本（原文照录）\n\n```\n").append(outcome.renderedText()).append("\n```\n\n");
        out.append("## D19 清理留痕（先按最宽口径统计再 DROP）\n\n");
        out.append("统计口径 = **专用库里每张表都数一遍**：\n\n| 表 | 行数 |\n| --- | --- |\n");
        ROW_COUNTS_BEFORE_DROP.forEach((table, count) -> out.append("| ").append(table).append(" | ").append(count).append(" |\n"));
        out.append("\n- 清理动作：`DROP DATABASE `").append(E2E_DB)
                .append("`（只作用于本轮自建的、名字带 `work_order_e2e` 前缀的库）\n");
        out.append("- 共享的 `").append(SOURCE_DB).append("` 与业务库**未被写入**（只读了表结构 + `t_role`）\n");
        return out.toString();
    }

    // ─────────────────────────── 记录型模型包装 ───────────────────────────

    /** 只在外面加一层"记账"，**不改变**协议行为：非 2xx 仍然是 `HttpAgentModel` 抛出的异常。 */
    private static final class RecordingModel implements AgentModel {

        private final AgentModel delegate;
        private final List<Call> calls = new ArrayList<>();

        private RecordingModel(AgentModel delegate) {
            this.delegate = delegate;
        }

        @Override
        public ModelTurn respond(List<JsonNode> transcript, Duration readTimeout) {
            long started = System.nanoTime();
            try {
                ModelTurn turn = delegate.respond(transcript, readTimeout);
                calls.add(new Call(true, (System.nanoTime() - started) / 1_000_000L,
                        turn.toolCalls().size(), turn.rawBytes(), null));
                return turn;
            } catch (AgentModelException e) {
                calls.add(new Call(false, (System.nanoTime() - started) / 1_000_000L, 0, 0, e.code()));
                throw e;
            }
        }

        private boolean allHttpOk() {
            return calls.stream().allMatch(call -> call.ok);
        }

        private String summary() {
            return "calls=" + calls.size() + " " + calls;
        }

        private record Call(boolean ok, long millis, int toolCalls, int bytes, String errorCode) {
        }
    }

    // ─────────────────────────── 收尾 ───────────────────────────

    private static void collectRowCounts() throws Exception {
        try (Connection conn = DriverManager.getConnection(serverJdbcUrl(), user(), password());
             Statement st = conn.createStatement()) {
            for (String table : TABLES) {
                try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM `" + E2E_DB + "`.`" + table + "`")) {
                    ROW_COUNTS_BEFORE_DROP.put(table, rs.next() ? rs.getInt(1) : 0);
                }
            }
        }
    }

    @AfterAll
    static void dropE2eDatabase() {
        if (!E2E_DB.startsWith("work_order_e2e")) {
            throw new IllegalStateException("拒绝删除非 e2e 库：" + E2E_DB);
        }
        try {
            collectRowCounts();
            try (Connection conn = DriverManager.getConnection(serverJdbcUrl(), user(), password());
                 Statement st = conn.createStatement()) {
                st.execute("DROP DATABASE IF EXISTS `" + E2E_DB + "`");
                System.out.println("[e2e] 已 DROP 专用库 " + E2E_DB);
            }
        } catch (Exception e) {
            System.out.println("[e2e] 清理失败（需人工确认 " + E2E_DB + "）：" + e.getMessage());
        }
    }
}
