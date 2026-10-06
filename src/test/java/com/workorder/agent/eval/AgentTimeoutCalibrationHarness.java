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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * **超时链校准采样**（§4.1 / D89）：同一套夹具、两种问题类型、每类 3 次，共 **6 次真调用**。
 *
 * <p>为什么要采样而不是看单次：一次 18.9s 不能拿来定超时；要的是**单次调用**与**端到端**各自的
 * min / median / max。样本小（6 次），**不是分布结论**，只够把"初值"改成"有数字依据的初值"。
 *
 * <p>key 从环境变量或 `%TEMP%\wo-llm-probe.env`（仓库外）读；专用库 `work_order_e2e`；跑完 DROP。
 * 类名不带 `Test` → 默认 `mvn test` 不会跑它。
 */
@SpringBootTest(properties = {
        "agent.investigation.enabled=true",
        "workorder.outbox.dispatch.enabled=false"
})
@ActiveProfiles("test")
@DisplayName("超时链校准采样（真供应商）")
class AgentTimeoutCalibrationHarness {

    private static final String SOURCE_DB = "work_order_test";
    private static final String E2E_DB = "work_order_e2e";
    private static final String ORDER_NO = "WO-20261006-70001";
    private static final long DEPT = 7001L;
    private static final long CALLER_ID = 810001L;
    private static final long SUBMITTER_ID = 810002L;
    private static final long ASSIGNEE_ID = 810003L;

    /** 两种问题类型：一次状态类、一次超时类（用同一张单，类型由问题决定）。 */
    private static final List<String> QUESTIONS = List.of(
            "工单 " + ORDER_NO + " 现在到哪一步了？之前是谁处理过？最近的记录不全就往前翻。",
            "工单 " + ORDER_NO + " 为什么还没处理完？现有记录能确认什么、还缺什么？");
    private static final int RUNS_PER_QUESTION = 3;

    private static final Path RESULTS = Path.of("docs", "agent-eval", "timeout-calibration-20261006.md");
    private static final Map<String, Integer> ROW_COUNTS = new TreeMap<>();
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

    @DynamicPropertySource
    static void databaseAndProvider(DynamicPropertyRegistry registry) throws Exception {
        prepareDatabase();
        registry.add("spring.datasource.url", AgentTimeoutCalibrationHarness::jdbcUrl);
        registry.add("llm.api.url", () -> providerValue("LLM_API_URL"));
        registry.add("llm.api.key", () -> providerValue("LLM_API_KEY"));
        registry.add("llm.api.model", () -> providerValue("LLM_MODEL"));
        registry.add("agent.investigation.mode", () -> "agent");
    }

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
        throw new IllegalStateException("缺 " + name);
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

    private static String mysqlUser() {
        String v = System.getenv("MYSQL_USER");
        return v == null || v.isBlank() ? "root" : v;
    }

    private static String mysqlPassword() {
        String v = System.getenv("MYSQL_PASSWORD");
        return v == null ? "" : v;
    }

    private static void prepareDatabase() throws Exception {
        if (!E2E_DB.startsWith("work_order_e2e")) {
            throw new IllegalStateException("拒绝操作非 e2e 库：" + E2E_DB);
        }
        try (Connection conn = DriverManager.getConnection(serverJdbcUrl(), mysqlUser(), mysqlPassword());
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
        }
    }

    // ─────────────────────────── 采样 ───────────────────────────

    @Test
    @DisplayName("两种问题类型各 3 次：逐次记录模型调用耗时与端到端耗时")
    void sampleSixRuns() throws Exception {
        seedFixture();

        List<Run> runs = new ArrayList<>();
        for (int q = 0; q < QUESTIONS.size(); q++) {
            for (int i = 1; i <= RUNS_PER_QUESTION; i++) {
                runs.add(runOnce("Q" + (q + 1) + "-" + i, QUESTIONS.get(q)));
                System.out.println("[calib] " + runs.get(runs.size() - 1).summary());
            }
        }

        collectRowCounts();
        Files.createDirectories(RESULTS.getParent());
        Files.writeString(RESULTS, render(runs), StandardCharsets.UTF_8);
        System.out.println("[calib] 结果已写入 " + RESULTS);
        System.out.println("[calib] 模型调用总数 = " + runs.stream().mapToInt(r -> r.callMillis.size()).sum());
    }

    private Run runOnce(String id, String question) {
        RecordingModel model = new RecordingModel(wiredModel);
        InvestigationAgent agent = new InvestigationAgent(model, tools, limits);
        AgentInvestigationService service = new AgentInvestigationService(agent, null, renderer, "agent", workOrderService);

        long started = System.nanoTime();
        AgentInvestigationService.Outcome outcome = service.investigate(CALLER_ID, ORDER_NO, question);
        long millis = (System.nanoTime() - started) / 1_000_000L;

        String text = outcome.renderedText() == null ? "" : outcome.renderedText();
        return new Run(id, question, outcome.status(), outcome.failureCode(),
                model.calls.stream().mapToLong(c -> c.millis).boxed().toList(),
                millis, text.contains("order.logs_page"), text.contains("dept."),
                outcome.report() == null ? 0 : outcome.report().evidenceIds().size(),
                outcome.report() == null ? "（无）" : outcome.report().problemType().name(),
                model.allHttpOk());
    }

    private record Run(String id, String question, String status, String failureCode, List<Long> callMillis,
                       long e2eMillis, boolean paged, boolean peerCompared, int evidenceCount,
                       String problemType, boolean allHttpOk) {
        String summary() {
            return id + " " + status + " type=" + problemType + " calls=" + callMillis.size()
                    + " e2e=" + e2eMillis + "ms perCall=" + callMillis;
        }
    }

    // ─────────────────────────── 记录 ───────────────────────────

    private String render(List<Run> runs) {
        List<Long> singleCalls = runs.stream().flatMap(r -> r.callMillis.stream()).toList();
        List<Long> e2e = runs.stream().map(Run::e2eMillis).toList();

        StringBuilder out = new StringBuilder();
        out.append("# 超时链校准采样（真供应商，2026-10-06）\n\n");
        out.append("> **真实供应商（").append(providerValue("LLM_MODEL")).append("）+ 本机库**；样本 **")
                .append(runs.size()).append("** 次（两种问题类型各 ").append(RUNS_PER_QUESTION)
                .append(" 次），**样本小，不是分布结论**——只够把 §4.1 的「待测初值」改成「有数字依据的初值」。\n\n");
        out.append("| 项 | 值 |\n| --- | --- |\n");
        out.append("| 专用库 | `").append(E2E_DB).append("`（结构克隆自 `").append(SOURCE_DB).append("`；跑完 DROP） |\n");
        out.append("| 模型 | `").append(providerValue("LLM_MODEL")).append("` |\n");
        out.append("| 采样次数 | **").append(runs.size()).append("** 次调查 |\n");
        out.append("| 模型调用总数 | **").append(singleCalls.size()).append("** 次（真实计费） |\n");
        out.append("| 4xx/5xx | **").append(runs.stream().allMatch(Run::allHttpOk) ? "无" : "有").append("** |\n\n");

        out.append("## 逐次结果\n\n");
        out.append("| # | 问题 | 终态 | problemType | 模型调用 | 每次调用耗时(ms) | 端到端(ms) | 触发翻页 | 触发对照 | 证据数 |\n");
        out.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (Run run : runs) {
            out.append("| ").append(run.id).append(" | ").append(run.question.contains("为什么") ? "为什么没处理完" : "到哪一步")
                    .append(" | ").append(run.status).append(run.failureCode == null ? "" : "(" + run.failureCode + ")")
                    .append(" | ").append(run.problemType)
                    .append(" | ").append(run.callMillis.size())
                    .append(" | ").append(run.callMillis)
                    .append(" | ").append(run.e2eMillis)
                    .append(" | ").append(run.paged ? "是" : "否")
                    .append(" | ").append(run.peerCompared ? "是" : "否")
                    .append(" | ").append(run.evidenceCount).append(" |\n");
        }

        out.append("\n## 分布（样本 ").append(runs.size()).append(" 次调查 / ")
                .append(singleCalls.size()).append(" 次模型调用）\n\n");
        out.append("| 口径 | n | min | median | max |\n| --- | --- | --- | --- | --- |\n");
        out.append("| **单次模型调用**耗时(ms) | ").append(singleCalls.size()).append(" | ")
                .append(min(singleCalls)).append(" | ").append(median(singleCalls)).append(" | ").append(max(singleCalls)).append(" |\n");
        out.append("| **端到端**耗时(ms) | ").append(e2e.size()).append(" | ")
                .append(min(e2e)).append(" | ").append(median(e2e)).append(" | ").append(max(e2e)).append(" |\n");
        out.append("| 每次调查的模型调用数 | ").append(runs.size()).append(" | ")
                .append(min(runs.stream().map(r -> (long) r.callMillis.size()).toList())).append(" | ")
                .append(median(runs.stream().map(r -> (long) r.callMillis.size()).toList())).append(" | ")
                .append(max(runs.stream().map(r -> (long) r.callMillis.size()).toList())).append(" |\n");

        out.append("\n## D19 清理留痕（先按最宽口径统计再 DROP）\n\n| 表 | 行数 |\n| --- | --- |\n");
        ROW_COUNTS.forEach((t, c) -> out.append("| ").append(t).append(" | ").append(c).append(" |\n"));
        out.append("\n- 清理动作：`DROP DATABASE `").append(E2E_DB).append("`；共享的 `")
                .append(SOURCE_DB).append("` 与业务库未被写入（只读结构 + `t_role`）\n");
        return out.toString();
    }

    private static long min(List<Long> values) {
        return values.stream().mapToLong(Long::longValue).min().orElse(0);
    }

    private static long max(List<Long> values) {
        return values.stream().mapToLong(Long::longValue).max().orElse(0);
    }

    /** 偶数样本取中间两个的**平均**（写下来，避免不同人算法不一致）。 */
    private static long median(List<Long> values) {
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        int n = sorted.size();
        if (n == 0) {
            return 0;
        }
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
    }

    private void seedFixture() {
        user(CALLER_ID, "calib-dept-admin", 4L);
        user(SUBMITTER_ID, "calib-submitter", 2L);
        user(ASSIGNEE_ID, "calib-handler", 3L);

        LocalDateTime now = LocalDateTime.now();
        WorkOrder order = new WorkOrder();
        order.setOrderNo(ORDER_NO);
        order.setTitle("calib:超时链采样");
        order.setContent("超时链校准夹具");
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

    private void user(long id, String username, long roleId) {
        User u = new User();
        u.setId(id);
        u.setUsername(username);
        u.setPassword("$2a$10$1s93/XO7m.kI61bcmONyRutCPPMw9hqxd14syjk.8G/82JKi9HVIe");
        u.setDeptId(DEPT);
        u.setStatus(1);
        userMapper.insert(u);
        UserRole binding = new UserRole();
        binding.setUserId(id);
        binding.setRoleId(roleId);
        userRoleMapper.insert(binding);
    }

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
                calls.add(new Call(true, (System.nanoTime() - started) / 1_000_000L));
                return turn;
            } catch (AgentModelException e) {
                calls.add(new Call(false, (System.nanoTime() - started) / 1_000_000L));
                throw e;
            }
        }

        private boolean allHttpOk() {
            return calls.stream().allMatch(c -> c.ok);
        }

        private record Call(boolean ok, long millis) {
        }
    }

    private static void collectRowCounts() throws Exception {
        try (Connection conn = DriverManager.getConnection(serverJdbcUrl(), mysqlUser(), mysqlPassword());
             Statement st = conn.createStatement()) {
            for (String table : TABLES) {
                try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM `" + E2E_DB + "`.`" + table + "`")) {
                    ROW_COUNTS.put(table, rs.next() ? rs.getInt(1) : 0);
                }
            }
        }
    }

    @AfterAll
    static void dropE2eDatabase() {
        try (Connection conn = DriverManager.getConnection(serverJdbcUrl(), mysqlUser(), mysqlPassword());
             Statement st = conn.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS `" + E2E_DB + "`");
            System.out.println("[calib] 已 DROP 专用库 " + E2E_DB);
        } catch (Exception e) {
            System.out.println("[calib] 清理失败（需人工确认 " + E2E_DB + "）：" + e.getMessage());
        }
    }
}
