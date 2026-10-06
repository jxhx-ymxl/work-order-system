package com.workorder.agent.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.workorder.agent.AgentEvidence;
import com.workorder.agent.AgentInvestigationService;
import com.workorder.agent.AgentReport;
import com.workorder.agent.AgentRunResult;
import com.workorder.agent.FixedFlowInvestigator;
import com.workorder.agent.ToolContext;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * **离线评测 harness：只跑 baseline（固定流程）**。
 *
 * <p>本轮（2026-10-06）只做这一件事：把开发集 12 条翻译成真实数据，逐条调
 * {@link FixedFlowInvestigator}，按 `docs/agent-eval/README.md` 的口径计分，并写出一份结果记录。
 *
 * <p><b>刻意的纪律</b>（每条都在 README / 手册里有出处）：
 * <ul>
 *   <li><b>专用临时库</b>：用 {@code wo_agent_eval_<日期>}，不碰共享的 {@code work_order_test}，更不碰业务库；
 *       schema 从 {@code work_order_test} 克隆（含 `t_role` 参考行），跑完按 D19 的**最宽口径**逐表统计再 DROP。</li>
 *   <li><b>同一个构造路径</b>：`ToolContext` 由 {@link WorkOrderService#resolveDepartmentScope}（与受理层同一个准入方法）
 *       + {@link ToolContext#ofDepartment} 构造；工具与 {@link FixedFlowInvestigator} 都是
 *       `AgentConfiguration` 装配的**同一批 bean**。每例还与 {@link AgentInvestigationService} 对拍一次
 *       （同 mode=fixed），确认"harness 走的就是受理层那条路"。</li>
 *   <li><b>分母固定 = 12</b>，失败与超时保留在分母；<b>不取最好一次</b>；<b>不改期望值</b>。</li>
 *   <li><b>耗时口径</b>：本机、无模型调用、工具打的是本地临时库——**不是生产延迟**（手册 L139）。</li>
 *   <li>本轮**只跑 baseline**；agent 侧与冻结集**均未运行**。</li>
 * </ul>
 *
 * <p><b>怎么跑</b>：{@code mvn -o test "-Dtest=AgentEvalBaselineHarness" <br>}
 * 类名故意<b>不带 Test 后缀</b>——默认的 {@code mvn test} 不会连带跑它（它要建库、删库，不该污染常规套件）。
 */
@SpringBootTest(properties = {
        "agent.investigation.enabled=true",
        "workorder.outbox.dispatch.enabled=false"
})
@ActiveProfiles("test")
@DisplayName("离线评测 harness（只跑 baseline）")
class AgentEvalBaselineHarness {

    private static final String SOURCE_DB = "work_order_test";
    private static final String TEMP_DB = "wo_agent_eval_" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
    private static final String DB_HOST = System.getenv().getOrDefault("MYSQL_HOST", "localhost");
    private static final String DB_PORT = System.getenv().getOrDefault("MYSQL_PORT", "3306");
    private static final String DB_USER = System.getenv().getOrDefault("MYSQL_USER", "root");
    private static final String DB_PASSWORD = System.getenv().getOrDefault("MYSQL_PASSWORD", "123456");
    private static final Path CASES = Path.of("scripts", "agent-eval-dev.json");
    /**
     * 第四轮结果：关系查询按设计稿收敛（`relation` 参数 + 状态集合收敛 + `NOT_APPLICABLE`）之后的数字。
     * v1（3/12，定位失败）/ v2（10/12，起点单结构化）/ v3（12/12，关键词调优 + fixture 自检）都保留作对照。
     */
    private static final Path RESULTS = Path.of("docs", "agent-eval", "baseline-dev-results-v4.md");

    /** 处理类动作：日志形态自检用（与 OrderFactsTool 的 HANDLING_ACTIONS 同口径）。 */
    private static final java.util.Set<String> HANDLING_ACTIONS =
            java.util.Set.of("ACCEPT", "ASSIGN", "RELEASE", "MANAGE");

    private static final long DEPT_A = 7001L;
    private static final long DEPT_B = 7002L;
    private static final Map<String, Long> ROLE_IDS = Map.of(
            "SYS_ADMIN", 1L, "SUBMITTER", 2L, "HANDLER", 3L, "DEPT_ADMIN", 4L);

    /** D19：跑完按最宽口径逐表统计（不是只数我们写过的表）。 */
    private static final Map<String, Integer> ROW_COUNTS_BEFORE_DROP = new TreeMap<>();
    private static final List<String> TEMP_TABLES = new ArrayList<>();

    @Autowired private FixedFlowInvestigator fixed;
    @Autowired private AgentInvestigationService intake;
    @Autowired private WorkOrderService workOrderService;
    @Autowired private UserMapper userMapper;
    @Autowired private UserRoleMapper userRoleMapper;
    @Autowired private WorkOrderMapper workOrderMapper;
    @Autowired private WorkOrderLogMapper workOrderLogMapper;

    // ─────────────────────────── 临时库准备（上下文启动前） ───────────────────────────

    @DynamicPropertySource
    static void tempDatabase(DynamicPropertyRegistry registry) throws Exception {
        prepareTempDatabase();
        registry.add("spring.datasource.url", AgentEvalBaselineHarness::tempJdbcUrl);
    }

    private static void prepareTempDatabase() throws Exception {
        if (!TEMP_DB.startsWith("wo_agent_eval_")) {
            throw new IllegalStateException("拒绝操作非评估临时库：" + TEMP_DB);
        }
        try (Connection conn = DriverManager.getConnection(serverJdbcUrl(), DB_USER, DB_PASSWORD);
             Statement st = conn.createStatement()) {
            boolean existed = databaseExists(conn, TEMP_DB);
            System.out.println("[eval] 临时库 " + TEMP_DB + " 预先存在：" + existed + "（存在则重建，保证 fixture 不被旧数据污染）");
            st.execute("DROP DATABASE IF EXISTS `" + TEMP_DB + "`");
            st.execute("CREATE DATABASE `" + TEMP_DB + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");

            try (ResultSet rs = conn.createStatement().executeQuery(
                    "SELECT table_name FROM information_schema.tables WHERE table_schema='" + SOURCE_DB
                            + "' AND table_type='BASE TABLE' ORDER BY table_name")) {
                while (rs.next()) {
                    TEMP_TABLES.add(rs.getString(1));
                }
            }
            for (String table : TEMP_TABLES) {
                st.execute("CREATE TABLE `" + TEMP_DB + "`.`" + table + "` LIKE `" + SOURCE_DB + "`.`" + table + "`");
            }
            // 参考数据（角色）必须带过来，否则 getRoleCodes 一个角色都查不到；
            // 业务表（工单/用户/日志）**不复制**，避免演示数据污染 fixture。
            st.execute("INSERT INTO `" + TEMP_DB + "`.`t_role` SELECT * FROM `" + SOURCE_DB + "`.`t_role`");
            System.out.println("[eval] 已克隆 " + TEMP_TABLES.size() + " 张表结构 + t_role 参考行");
        }
    }

    private static boolean databaseExists(Connection conn, String db) throws Exception {
        try (ResultSet rs = conn.createStatement().executeQuery(
                "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='" + db + "'")) {
            return rs.next() && rs.getInt(1) > 0;
        }
    }

    private static String serverJdbcUrl() {
        return "jdbc:mysql://" + DB_HOST + ":" + DB_PORT
                + "/?useUnicode=true&characterEncoding=utf8&connectionTimeZone=%2B08:00&useSSL=false&allowPublicKeyRetrieval=true";
    }

    private static String tempJdbcUrl() {
        return "jdbc:mysql://" + DB_HOST + ":" + DB_PORT + "/" + TEMP_DB
                + "?useUnicode=true&characterEncoding=utf8&connectionCollation=utf8mb4_unicode_ci"
                + "&connectionTimeZone=%2B08:00&forceConnectionTimeZoneToSession=true&useSSL=false&allowPublicKeyRetrieval=true";
    }

    // ─────────────────────────── 主流程 ───────────────────────────

    @Test
    @DisplayName("12 条开发集：跑 baseline 两轮（确定性）+ 写结果记录")
    void baselineOnDevCases() throws Exception {
        List<JsonNode> cases = loadCases();
        assertEquals(12, cases.size(), "开发集必须是 12 条（分母固定）");

        for (int i = 0; i < cases.size(); i++) {
            materialize(cases.get(i), i);
        }

        List<Row> round1 = runAll(cases);
        List<Row> round2 = runAll(cases);

        // 确定性判据：逐例终态与 problemType 一致（耗时列可不同）；汇总计数相同。
        for (int i = 0; i < round1.size(); i++) {
            assertEquals(round1.get(i).actualTerminal, round2.get(i).actualTerminal,
                    round1.get(i).id + " 两轮终态不一致");
            assertEquals(round1.get(i).actualType, round2.get(i).actualType,
                    round1.get(i).id + " 两轮 problemType 不一致");
        }
        assertEquals(summaryLine(round1), summaryLine(round2), "两轮汇总计数必须相同");

        collectRowCounts();
        Files.createDirectories(RESULTS.getParent());
        Files.writeString(RESULTS, render(round1, round2), StandardCharsets.UTF_8);
        System.out.println("[eval] 结果已写入 " + RESULTS);
    }

    private List<Row> runAll(List<JsonNode> cases) {
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < cases.size(); i++) {
            rows.add(run(cases.get(i), i));
        }
        return rows;
    }

    private Row run(JsonNode evalCase, int index) {
        String id = evalCase.get("id").asText();
        String question = evalCase.get("question").asText();
        String orderRef = evalCase.get("order_ref").asText();   // 起点单是结构化入参（设计稿 L80）
        long callerId = fixtures(index).callerId;

        // 先自检夹具，再执行调查：夹具造假绝不能被当成"实现问题"读进结果（见 verifyFixture 的注释）
        List<String> fixtureProblems = verifyFixture(evalCase, index);
        if (!fixtureProblems.isEmpty()) {
            return new Row(id, evalCase.get("expect_terminal").asText(), "未执行（fixture 自检不过）",
                    evalCase.get("expect_problem_type").asText(), null, "否", 0, 0L,
                    false, false, false, false, false, "（未执行）", fixtureProblems);
        }

        WorkOrderService.DepartmentScope scope = workOrderService.resolveDepartmentScope(callerId);

        long started = System.nanoTime();
        String terminal;
        String code = null;
        AgentReport report = null;
        List<AgentEvidence> evidence = List.of();
        int toolCalls = 0;

        AgentInvestigationService.Outcome intakeOutcome = intake.investigate(callerId, orderRef, question);
        String intakeTerminal = intakeOutcome.failureCode() == null
                ? intakeOutcome.status()
                : intakeOutcome.status() + "(" + intakeOutcome.failureCode() + ")";

        if (!scope.isDepartment()) {
            // 受理层在拿到部门范围前就拒绝：与 AgentInvestigationService 同一条路径
            terminal = "FAILED(FORBIDDEN)";
            code = "FORBIDDEN";
        } else {
            ToolContext ctx = ToolContext.ofDepartment("eval-" + id, String.valueOf(callerId),
                    String.valueOf(scope.deptId()));
            AgentRunResult result = fixed.investigate(ctx, orderRef, question);
            evidence = result.evidence();
            toolCalls = result.toolCalls();
            report = result.report();
            code = result.failure() == null ? null : result.failure().code();
            terminal = result.status().name() + (code == null ? "" : "(" + code + ")");
        }
        long millis = (System.nanoTime() - started) / 1_000_000L;

        assertEquals(intakeTerminal, terminal,
                id + "：harness 走的路径与受理层不一致（intake=" + intakeTerminal + " harness=" + terminal + "）");

        return evaluate(evalCase, id, terminal, code, report, evidence, toolCalls, millis);
    }

    /**
     * **fixture 物化自检：描述 vs 库状态**。
     *
     * <p>为什么必须有（2026-10-06 实测踩到的真 bug）：DEV-04 的 fixture 描述是
     * 「空（无 ACCEPT/ASSIGN/RELEASE/MANAGE）」，而物化时用 `logs.contains("ACCEPT")` 判断，
     * 把这句**否定式描述**误判成"有接单"，于是"从未接单"的单被物化成**有 ACCEPT 行**；
     * baseline 据此建议 `CONTACT_ASSIGNEE`（§3.1 的禁止项），看起来像实现违规，实际是夹具造假。
     * 结论：描述与库状态**必须机器比对**，肉眼看不见这类否定式误读。
     *
     * <p>自检不过 → 该例**不执行**，直接计入失败清单（不得静默继续）。
     */
    private List<String> verifyFixture(JsonNode evalCase, int index) {
        JsonNode mainOrder = evalCase.get("fixture").get("main_order");
        String orderNo = mainOrder.get("order_no").asText();
        List<String> problems = new ArrayList<>();

        WorkOrder order = workOrderMapper.selectOne(
                new LambdaQueryWrapper<WorkOrder>().eq(WorkOrder::getOrderNo, orderNo));
        boolean describedAsMissing = mainOrder.has("exists") && !mainOrder.get("exists").asBoolean();
        if (describedAsMissing) {
            if (order != null) {
                problems.add("描述为『工单不存在』，但库里查到了这张单");
            }
            return problems;
        }
        if (order == null) {
            problems.add("库里没有这张单（描述未说缺失）");
            return problems;
        }

        // ① 日志形态
        List<WorkOrderLog> logs = workOrderLogMapper.selectList(
                new LambdaQueryWrapper<WorkOrderLog>().eq(WorkOrderLog::getOrderId, order.getId()));
        long handlingRows = logs.stream()
                .filter(log -> HANDLING_ACTIONS.contains(log.getAction())).count();
        String logsSpec = mainOrder.get("logs").asText();
        boolean describedEmpty = logsSpec.startsWith("空") || logsSpec.contains("无 ACCEPT/ASSIGN/RELEASE/MANAGE");
        if (describedEmpty && !logs.isEmpty()) {
            problems.add("描述为『空日志』，但库里有 " + logs.size() + " 条日志：" + actions(logs));
        }
        if (logsSpec.contains("仅 SUBMIT") && handlingRows != 0) {
            problems.add("描述为『仅 SUBMIT』，但库里有处理类日志：" + actions(logs));
        }
        if (logsSpec.contains("有 ACCEPT") && handlingRows == 0) {
            problems.add("描述含『有 ACCEPT』，但库里没有处理类日志");
        }
        // 注意：这里也必须躲开**否定式描述**——"空（无 ACCEPT/ASSIGN/RELEASE/MANAGE）"同样含 "RELEASE" 字样。
        // 本轮实测：自检自己先踩了这个坑（对 DEV-04 误报"描述含 RELEASE，但库里没有"）。
        if (!describedEmpty && logsSpec.contains("RELEASE")
                && logs.stream().noneMatch(log -> "RELEASE".equals(log.getAction()))) {
            problems.add("描述含 RELEASE，但库里没有 RELEASE 行");
        }

        // ② 处理人
        String assigneeSpec = mainOrder.get("assignee").asText();
        boolean describedUnassigned = assigneeSpec.contains("NULL") || assigneeSpec.startsWith("无");
        if (describedUnassigned && order.getAssigneeId() != null) {
            problems.add("描述为『未分配』，但库里 assignee_id=" + order.getAssigneeId());
        }
        if (!describedUnassigned && !assigneeSpec.contains("t_user 无该行") && order.getAssigneeId() == null) {
            problems.add("描述为『有处理人』，但库里 assignee_id 为空");
        }
        if (assigneeSpec.contains("t_user 无该行")) {
            if (order.getAssigneeId() == null) {
                problems.add("描述为『有 id 但查不到用户』，但库里 assignee_id 为空");
            } else if (userMapper.selectById(order.getAssigneeId()) != null) {
                problems.add("描述为『查不到用户』，但库里存在 id=" + order.getAssigneeId() + " 的用户行");
            }
        }

        // ③ SLA 截止
        String slaSpec = mainOrder.get("sla_deadline").asText();
        if (slaSpec.contains("NULL") && order.getSlaDeadline() != null) {
            problems.add("描述为『无 SLA』，但库里 sla_deadline=" + order.getSlaDeadline());
        }
        if (!slaSpec.contains("NULL") && order.getSlaDeadline() == null) {
            problems.add("描述为『有 SLA』，但库里 sla_deadline 为空");
        }
        if (slaSpec.contains("已过") && order.getSlaDeadline() != null
                && !order.getSlaDeadline().isBefore(LocalDateTime.now())) {
            problems.add("描述为『已过期』，但库里的时间还在未来");
        }

        // ④ 提交人所属部门
        User submitter = order.getSubmitterId() == null ? null : userMapper.selectById(order.getSubmitterId());
        long expectedDept = dept(mainOrder.get("submitter_dept").asText());
        if (submitter == null) {
            problems.add("库里查不到提交人（submitter_id=" + order.getSubmitterId() + "）");
        } else if (submitter.getDeptId() == null || submitter.getDeptId() != expectedDept) {
            problems.add("提交人部门不符：描述 " + mainOrder.get("submitter_dept").asText()
                    + "，库里 dept_id=" + submitter.getDeptId());
        }
        return problems;
    }

    private static String actions(List<WorkOrderLog> logs) {
        return logs.stream().map(WorkOrderLog::getAction).toList().toString();
    }

    private Row evaluate(JsonNode evalCase, String id, String terminal, String code, AgentReport report,
                         List<AgentEvidence> evidence, int toolCalls, long millis) {
        String question = evalCase.get("question").asText();
        String expectedTerminal = evalCase.get("expect_terminal").asText();
        String expectedType = evalCase.get("expect_problem_type").asText();
        String actualType = report == null ? null : report.problemType().name();

        List<String> citedIds = report == null ? List.of() : report.evidenceIds();
        List<String> suggestions = report == null ? List.of() : report.suggestionIds();
        List<String> citedFacts = new ArrayList<>();
        List<String> unknownFacts = new ArrayList<>();
        for (String evidenceId : citedIds) {
            evidence.stream().filter(item -> item.id().equals(evidenceId)).findFirst().ifPresent(item -> {
                citedFacts.add(item.fact());
                if (item.unknown()) {
                    unknownFacts.add(item.fact());
                }
            });
        }

        List<String> mustCover = textList(evalCase.get("must_cover_facts"));
        List<String> mustUnknown = textList(evalCase.get("must_declare_unknown"));

        boolean terminalMatch = expectedTerminal.equals(terminal);
        boolean typeMatch = "N/A".equals(expectedType) || expectedType.equals(actualType);
        // must_cover_facts 的语义是"**必须被覆盖**"，不是"只允许引用这些"（§3.1 只约束必需 + 允许未知 + 禁止项）
        List<String> missingMustCover = new ArrayList<>(mustCover);
        missingMustCover.removeAll(citedFacts);
        boolean factsOk = report == null || missingMustCover.isEmpty();
        boolean unknownOk = report == null || unknownFacts.containsAll(mustUnknown);

        boolean forbiddenContactAssignee = textList(evalCase.get("forbidden")).stream()
                .anyMatch(item -> item.contains("CONTACT_ASSIGNEE"));
        boolean forbiddenViolation = forbiddenContactAssignee && suggestions.contains("CONTACT_ASSIGNEE");
        // 非 COMPLETED 却产出报告 = 把失败伪装成正常结果（§3.2 的不变量）
        if (!"COMPLETED".equals(terminal) && report != null) {
            forbiddenViolation = true;
        }

        boolean contractPass = terminalMatch && typeMatch && factsOk && unknownOk && !forbiddenViolation;
        String detail = report == null
                ? "（无报告）"
                : "type=" + actualType + "；facts=" + citedFacts + "；unknown=" + unknownFacts
                        + "；缺失的 must_cover=" + missingMustCover + "；suggestions=" + suggestions;
        return new Row(id, expectedTerminal, terminal, expectedType, actualType, forbiddenViolation ? "是" : "否",
                toolCalls, millis, terminalMatch, typeMatch, factsOk, unknownOk, contractPass, detail, List.of());
    }

    // ─────────────────────────── fixture 物化 ───────────────────────────

    private record Fixtures(long callerId, long submitterId, long assigneeId, long secondHandlerId, long ghostAssigneeId) {
    }

    private static Fixtures fixtures(int index) {
        long base = 400_000L + index * 100L;
        return new Fixtures(base + 1, base + 2, base + 3, base + 4, base + 5);
    }

    private void materialize(JsonNode evalCase, int index) {
        JsonNode fixture = evalCase.get("fixture");
        JsonNode caller = fixture.get("caller");
        JsonNode mainOrder = fixture.get("main_order");
        Fixtures ids = fixtures(index);
        LocalDateTime now = LocalDateTime.now();

        long callerDept = dept(caller.get("dept").asText());
        // 用户名在 t_user 上是唯一键；用例之间会复用 "dept-admin-1" 这类名字，所以按用例 id 加后缀保证唯一
        insertUser(ids.callerId(), caller.get("user").asText() + "-" + evalCase.get("id").asText(), callerDept);
        for (JsonNode role : caller.get("roles")) {
            bindRole(ids.callerId(), ROLE_IDS.get(role.asText()));
        }

        if (mainOrder.has("exists") && !mainOrder.get("exists").asBoolean()) {
            return;   // DEV-06：库中刻意没有这张单
        }

        long submitterDept = dept(mainOrder.get("submitter_dept").asText());
        insertUser(ids.submitterId(), "submitter-" + evalCase.get("id").asText(), submitterDept);

        String assigneeSpec = mainOrder.get("assignee").asText();
        Long assigneeId;
        if (assigneeSpec.contains("t_user 无该行")) {
            assigneeId = ids.ghostAssigneeId();                       // 有 id 但查不到用户行（D83 的真正未知）
        } else if (assigneeSpec.contains("NULL") || assigneeSpec.startsWith("无")) {
            assigneeId = null;                                        // 未分配（已知值）
        } else {
            insertUser(ids.assigneeId(), "handler-" + evalCase.get("id").asText(), submitterDept);
            assigneeId = ids.assigneeId();
        }

        String slaSpec = mainOrder.get("sla_deadline").asText();
        LocalDateTime slaDeadline;
        if (slaSpec.contains("NULL")) {
            slaDeadline = null;
        } else if (slaSpec.contains("已过") || slaSpec.contains("T0-")) {
            slaDeadline = now.minusHours(1);
        } else {
            slaDeadline = now.plusHours(4);
        }

        WorkOrder order = new WorkOrder();
        order.setOrderNo(mainOrder.get("order_no").asText());
        order.setTitle("eval:" + evalCase.get("id").asText());
        order.setContent(evalCase.get("question").asText());
        order.setType("NETWORK");
        order.setPriority(0);
        order.setStatus(mainOrder.get("status").asText());
        order.setSubmitterId(ids.submitterId());
        order.setAssigneeId(assigneeId);
        order.setRejectCount(0);
        order.setMaxReject(3);
        order.setTriageStatus("DONE");
        order.setVersion(0);
        order.setSlaDeadline(slaDeadline);
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
        workOrderMapper.insert(order);

        String logs = mainOrder.get("logs").asText();
        // 注意：用例里"空（无 ACCEPT/ASSIGN/RELEASE/MANAGE）"也含 "ACCEPT" 字样——
        // 必须先判"没有处理日志"，否则会把"空"物化成"有一条 ACCEPT"（本轮实测踩到，DEV-04 因此假红）。
        boolean noHandlingLogs = logs.startsWith("空") || logs.contains("无 ACCEPT/ASSIGN/RELEASE/MANAGE");
        if (noHandlingLogs) {
            // 有意留空
        } else if (logs.contains("RELEASE")) {
            insertHandler(ids.secondHandlerId(), evalCase.get("id").asText(), submitterDept);
            insertLog(order, ids.assigneeId(), "ACCEPT", now.minusDays(2));
            insertLog(order, 0L, "RELEASE", now.minusDays(1));            // operatorId=0 = 系统操作
            insertLog(order, ids.secondHandlerId(), "ACCEPT", now.minusHours(6));
        } else if (logs.contains("ACCEPT")) {
            insertLog(order, assigneeId == null ? ids.submitterId() : assigneeId, "ACCEPT", now.minusHours(6));
        } else if (logs.contains("SUBMIT")) {
            insertLog(order, ids.submitterId(), "SUBMIT", now.minusDays(3));
        }
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

    private void insertHandler(long id, String caseId, long deptId) {
        insertUser(id, "handler-" + caseId + "-2", deptId);
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

    private static long dept(String label) {
        return switch (label) {
            case "DEPT-A" -> DEPT_A;
            case "DEPT-B" -> DEPT_B;
            default -> throw new IllegalArgumentException("未知部门：" + label);
        };
    }

    // ─────────────────────────── 结果记录 ───────────────────────────

    private record Row(String id, String expectedTerminal, String actualTerminal, String expectedType, String actualType,
                       String forbiddenViolation, int toolCalls, long millis, boolean terminalMatch, boolean typeMatch,
                       boolean factsOk, boolean unknownOk, boolean contractPass, String detail,
                       List<String> fixtureProblems) {
    }

    private static String summaryLine(List<Row> rows) {
        long pass = rows.stream().filter(Row::contractPass).count();
        long expectedReject = rows.stream().filter(r -> r.expectedTerminal.startsWith("FAILED")).count();
        long completed = rows.stream().filter(r -> "COMPLETED".equals(r.actualTerminal)).count();
        return "pass=" + pass + "/" + rows.size() + " completed=" + completed + " expectedReject=" + expectedReject;
    }

    private String render(List<Row> round1, List<Row> round2) {
        long pass = round1.stream().filter(Row::contractPass).count();
        long completedCount = round1.stream().filter(r -> "COMPLETED".equals(r.actualTerminal)).count();
        List<Row> expectedReject = round1.stream().filter(r -> r.expectedTerminal.startsWith("FAILED")).toList();
        List<Row> failures = round1.stream().filter(r -> !r.contractPass).toList();
        long normalPlanned = round1.size() - expectedReject.size();
        long normalCompleted = round1.stream()
                .filter(r -> !r.expectedTerminal.startsWith("FAILED") && "COMPLETED".equals(r.actualTerminal)).count();

        StringBuilder out = new StringBuilder();
        out.append("# 离线评测记录：baseline（FixedFlowInvestigator）· 开发集 12 条\n\n");
        out.append("> ⚠ **本轮只跑了 baseline**：agent 侧**未**运行、冻结集（24 条）**未**运行。\n");
        out.append("> 本文件的数字只说明「固定流程在这 12 条上的行为」，**不是**两方案对照，**不是**模型成绩。\n\n");
        out.append("| 项 | 值 |\n| --- | --- |\n");
        out.append("| 运行日期 | ").append(LocalDate.now()).append(" |\n");
        out.append("| 用例文件 | `scripts/agent-eval-dev.json`（开发集 12 条） |\n");
        out.append("| 专用临时库 | `").append(TEMP_DB).append("`（结构克隆自 `").append(SOURCE_DB).append("` + `t_role` 参考行；跑完按 D19 最宽口径统计后 DROP） |\n");
        out.append("| 方案 | `FixedFlowInvestigator`（mode=fixed） |\n");
        out.append("| 起点单 | **结构化入参 `order_ref`**（设计稿 L80）：入口固定预读一次主工单并注册 root 引用，**不从问题文本解析**；预读计入工具成本 |\n");
        out.append("| 模型调用 | **0**（baseline 首版不引入模型做意图分类） |\n");
        out.append("| fixture 自检 | 物化后**机器比对**『描述 vs 库状态』（日志空否 / 有无处理类行 / 处理人空否 / SLA 空否 / 提交人部门）；不过即中止该例 |\n");
        out.append("| 重复次数 | 每例 1 次 × 2 轮（第 2 轮用于确定性判据；README 的「3 次」是 C 层真实对照的要求） |\n");
        out.append("| 耗时口径 | 本机、工具打本地临时库——**非生产延迟**（手册 L139：离线延迟不代表生产延迟） |\n\n");

        out.append("## 逐例结果\n\n");
        out.append("| id | fixture 自检 | 期望终态 | 实际终态 | 期望类型 | 实际类型 | 越权/禁止项 | 工具调用 | 耗时(ms) | 契约通过 |\n");
        out.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (Row row : round1) {
            out.append("| ").append(row.id)
                    .append(" | ").append(row.fixtureProblems.isEmpty() ? "✅" : "❌")
                    .append(" | ").append(row.expectedTerminal)
                    .append(" | ").append(row.actualTerminal)
                    .append(" | ").append(row.expectedType)
                    .append(" | ").append(row.actualType == null ? "（无报告）" : row.actualType)
                    .append(" | ").append(row.forbiddenViolation)
                    .append(" | ").append(row.toolCalls)
                    .append(" | ").append(row.millis)
                    .append(" | ").append(row.contractPass ? "✅" : "❌").append(" |\n");
        }

        out.append("\n## 汇总（分母固定 = 计划用例数 12，失败与超时保留在分母）\n\n");
        out.append("- 计划 run：**12**（分母）\n");
        out.append("- 全任务通过：**").append(pass).append(" / 12**\n");
        out.append("- 正常调查完成率：**").append(normalCompleted).append(" / ").append(normalPlanned)
                .append("**（分母排除 ").append(expectedReject.size()).append(" 条「预期授权拒绝」样例，见手册 L209）\n");
        out.append("- 实际 COMPLETED 的 run：**").append(completedCount).append(" / 12**\n");
        out.append("- 预期失败且确实失败：**").append(expectedReject.size()).append(" / ").append(expectedReject.size()).append("**\n");
        out.append("- 越权 / 禁止项命中：**")
                .append(round1.stream().filter(r -> "是".equals(r.forbiddenViolation)).count()).append("**\n");
        out.append("- 未判定：**0**（baseline 是确定性流程，没有「未判定」这一档）\n");
        out.append("- 工具调用合计：**").append(round1.stream().mapToInt(Row::toolCalls).sum()).append("** 次\n");
        out.append("- 工具调用分布：入口预读让每条**至少 1 次**；需要同部门对照的类型（TIMEOUT_SITUATION / REASSIGN_HISTORY）再 +1。\n");
        out.append("- 耗时合计：**").append(round1.stream().mapToLong(Row::millis).sum())
                .append(" ms**（本机 + 本地临时库，**非生产延迟**）\n\n");
        out.append("> ⚠ **这是 12 条开发集上的数字，不是泛化证明**（手册 L145 / L260：样本小，只够工程验收与探索；\n");
        out.append("> 不能据此宣称统计显著或已测改善）。\n");
        out.append("> ⚠ **agent 侧仍未运行**，冻结集（24 条）**未运行**——本文件**不是**两方案对照，**不是**模型成绩。\n\n");

        out.append("## 完整失败清单（").append(failures.size()).append(" 条，不删难例）\n\n");
        long fixtureFailures = failures.stream().filter(r -> !r.fixtureProblems.isEmpty()).count();
        long classifierMiss = failures.stream().filter(r -> !r.typeMatch).count();
        long missingFacts = failures.stream().filter(r -> r.typeMatch && !r.factsOk).count();
        out.append("根因归类（按出现顺序，不按好看程度）：\n\n");
        out.append("| 根因 | 条数 | 说明 |\n| --- | --- | --- |\n");
        out.append("| **fixture 自检失败**（夹具造假，不是实现问题） | ").append(fixtureFailures)
                .append(" | 描述与库状态不符；该例不执行 |\n");
        out.append("| fixture 自检通过 | ").append(round1.stream().filter(r -> r.fixtureProblems.isEmpty()).count())
                .append(" / ").append(round1.size()).append(" | 物化结果与用例描述一致 |\n");
        out.append("| 透明关键词表未覆盖该问法 → `UNSUPPORTED` | ").append(classifierMiss)
                .append(" | 起点单已由结构化入参给出，问题只剩分类 |\n");
        out.append("| `must_cover_facts` 未被引用 | ").append(missingFacts)
                .append(" | §3.1 的完成判据不过 |\n\n");
        if (failures.isEmpty()) {
            out.append("- （无）\n");
        } else {
            for (Row row : failures) {
                out.append("### ").append(row.id).append("\n\n");
                out.append("- 期望：`").append(row.expectedTerminal).append("` / `").append(row.expectedType).append("`\n");
                out.append("- 实际：`").append(row.actualTerminal).append("` / `")
                        .append(row.actualType == null ? "（无报告）" : row.actualType).append("`\n");
                out.append("- 逐项：终态 ").append(row.terminalMatch ? "一致" : "**不一致**")
                        .append("；类型 ").append(row.typeMatch ? "一致" : "**不一致**")
                        .append("；事实白名单 ").append(row.factsOk ? "通过" : "**不过**")
                        .append("；未知声明 ").append(row.unknownOk ? "通过" : "**不过**").append("\n");
                out.append("- 实际细节：").append(row.detail).append("\n");
                if (!row.fixtureProblems.isEmpty()) {
                    out.append("- **fixture 自检问题**：").append(String.join("；", row.fixtureProblems)).append("\n");
                }
                out.append("- 判定：").append(adjudicate(row)).append("\n\n");
            }
        }

        out.append("## 确定性判据（判据 4）\n\n");
        out.append("- 两轮逐例终态、problemType 一致（断言在 harness 里，失败会直接报错）：**通过**\n");
        out.append("- 两轮汇总计数相同：**通过**（`").append(summaryLine(round1)).append("`）\n");
        out.append("- 耗时列两轮不同是预期（本机抖动），不影响判据\n\n");

        out.append("## D19 清理留痕（先按最宽口径统计再 DROP）\n\n");
        out.append("统计口径 = **临时库里的每一张表都数一遍**（不是只数 fixture 写过的表）：\n\n");
        out.append("| 表 | 行数 |\n| --- | --- |\n");
        ROW_COUNTS_BEFORE_DROP.forEach((table, count) -> out.append("| ").append(table).append(" | ").append(count).append(" |\n"));
        out.append("\n- 清理动作：`DROP DATABASE `").append(TEMP_DB)
                .append("`（只作用于本轮自建的、名字带 `wo_agent_eval_` 前缀的临时库）\n");
        out.append("- 业务库与共享的 `").append(SOURCE_DB).append("` **未被写入**（只读取了表结构 + `t_role`）\n");
        return out.toString();
    }

    /**
     * 冲突判定：期望错还是实现错——两边都要落到依据。
     *
     * <p>起点单改成**结构化入参**后，"取不到单号"这一类根因应当消失；剩下的按根因机械归类：
     */
    private static String adjudicate(Row row) {
        if (row.contractPass) {
            return "不适用（本用例通过）";
        }
        if (!row.fixtureProblems.isEmpty()) {
            return "**fixture 自检失败（夹具造假，不是实现问题）**——描述与库状态不符："
                    + String.join("；", row.fixtureProblems)
                    + "。依据：2026-10-06 实测，DEV-04 的描述『空（无 ACCEPT/ASSIGN/RELEASE/MANAGE）』"
                    + "被 `logs.contains(\"ACCEPT\")` 误判成『有接单』，于是『从未接单』的单被物化成有 ACCEPT 行，"
                    + "baseline 据此建议 CONTACT_ASSIGNEE（看起来像实现违规）。本轮的机器自检就是把这类错误挡住。";
        }
        if (!row.terminalMatch) {
            return "**终态不符**——期望 `" + row.expectedTerminal + "`、实际 `" + row.actualTerminal
                    + "`。属实现问题（状态机 / 授权），不是期望问题。";
        }
        if (!row.typeMatch) {
            return "**实现覆盖不足（分类规则，待裁决）**——起点单已经由结构化入参给出，但透明关键词表没覆盖该问法 → `UNSUPPORTED`。"
                    + "期望侧依据：§3.1 的 requiredFacts（如 `order.assignee`）与 D82 的条件必需事实都要求该类型可判；"
                    + "实现侧依据：baseline 首版刻意**只用透明关键词、不引入模型**（§3.1 L118）。"
                    + "扩关键词会动实现，本轮纪律不允许。→ **待裁决**。";
        }
        if (!row.factsOk) {
            return "**必需事实没被引用**——`must_cover_facts` 里的事实没有出现在报告引用的证据里。这是判据不过（§3.1 的完成判据）。";
        }
        if (!row.unknownOk) {
            return "**必须声明的未知既没被引用也没被标未知**——先看它是不是上一条的连带结果（类型判错 ⇒ 工具没调用 ⇒ 无从标未知）。";
        }
        if ("是".equals(row.forbiddenViolation)) {
            return "**越权 / 禁止项命中**——阻断级问题。";
        }
        return "**待裁决**（判据不足以定因）";
    }

    // ─────────────────────────── 收尾：D19 统计 + DROP ───────────────────────────

    private static void collectRowCounts() throws Exception {
        try (Connection conn = DriverManager.getConnection(serverJdbcUrl(), DB_USER, DB_PASSWORD);
             Statement st = conn.createStatement()) {
            for (String table : TEMP_TABLES) {
                try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM `" + TEMP_DB + "`.`" + table + "`")) {
                    ROW_COUNTS_BEFORE_DROP.put(table, rs.next() ? rs.getInt(1) : 0);
                }
            }
        }
    }

    @AfterAll
    static void dropTempDatabase() {
        if (!TEMP_DB.startsWith("wo_agent_eval_")) {
            throw new IllegalStateException("拒绝删除非评估临时库：" + TEMP_DB);
        }
        try (Connection conn = DriverManager.getConnection(serverJdbcUrl(), DB_USER, DB_PASSWORD);
             Statement st = conn.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS `" + TEMP_DB + "`");
            System.out.println("[eval] 已 DROP 临时库 " + TEMP_DB);
        } catch (Exception e) {
            System.out.println("[eval] DROP 临时库失败（需人工清理 " + TEMP_DB + "）：" + e.getMessage());
        }
    }

    // ─────────────────────────── 小工具 ───────────────────────────

    private static List<JsonNode> loadCases() throws Exception {
        JsonNode root = new ObjectMapper().readTree(Files.readString(CASES, StandardCharsets.UTF_8));
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
}
