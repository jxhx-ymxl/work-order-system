package com.workorder.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.workorder.agent.support.StubModelServer;
import com.workorder.agent.tool.OrderFactsTool;
import com.workorder.entity.User;
import com.workorder.entity.UserRole;
import com.workorder.entity.WorkOrder;
import com.workorder.entity.WorkOrderLog;
import com.workorder.mapper.UserMapper;
import com.workorder.mapper.UserRoleMapper;
import com.workorder.mapper.WorkOrderLogMapper;
import com.workorder.mapper.WorkOrderMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 调查接口（同步版）的对外判据。
 *
 * <p>判据全部落在**业务 code** 上（`$.code`），不落在传输层：这个项目里"未登录 / 无权限"
 * 都是 **HTTP 200 + 业务码**（`GlobalExceptionHandler`），只看 HTTP 状态会把它们读成"成功"（CLAUDE §6 第 6 项）。
 *
 * <p>模型一律用**桩**（`StubModelServer`）——本用例**不烧真钱**。
 */
@SpringBootTest(properties = "agent.investigation.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("调查接口（同步）：鉴权 / 越权 / 正常链路")
class AgentInvestigationControllerTest {

    private static final StubModelServer MODEL = new StubModelServer();
    private static final long DEPT_A = 7101L;
    private static final long DEPT_B = 7102L;
    private static final long DEPT_ADMIN_ID = 9101L;
    private static final long SUBMITTER_ID = 9102L;
    private static final long OTHER_DEPT_USER_ID = 9103L;
    private static final long DEPT_ADMIN_ROLE_ID = 4L;
    private static final long SUBMITTER_ROLE_ID = 2L;
    private static final java.util.concurrent.atomic.AtomicInteger SEQ =
            new java.util.concurrent.atomic.AtomicInteger(91000);

    @DynamicPropertySource
    static void llm(DynamicPropertyRegistry registry) {
        registry.add("llm.api.url", MODEL::url);
        registry.add("llm.api.key", () -> "stub-key");
        registry.add("llm.api.model", () -> "stub-model");
    }

    @AfterAll
    static void closeModel() {
        MODEL.close();
    }

    @AfterEach
    void logout() {
        StpUtil.logout();
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private UserMapper userMapper;
    @Autowired private UserRoleMapper userRoleMapper;
    @Autowired private WorkOrderMapper workOrderMapper;
    @Autowired private WorkOrderLogMapper workOrderLogMapper;

    private static String body(String orderNo) {
        return "{\"orderNo\":\"" + orderNo + "\",\"question\":\"工单 " + orderNo + " 现在到哪一步了？\"}";
    }

    private String login(long userId) {
        StpUtil.login(userId);
        return StpUtil.getTokenValue();
    }

    @Test
    @DisplayName("未登录 → HTTP 200 + 业务码 401（本项目约定：不落传输层）")
    void unauthenticatedIsRejected() throws Exception {
        mockMvc.perform(post("/api/agent/investigations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("WO-20261006-81001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("已登录但不是 DEPT_ADMIN → 业务码 403，不产出报告")
    void nonDeptAdminIsForbidden() throws Exception {
        insertUser(SUBMITTER_ID, "ctrl-submitter", DEPT_A, SUBMITTER_ROLE_ID);
        WorkOrder order = insertOrder(SUBMITTER_ID, DEPT_A);

        mockMvc.perform(post("/api/agent/investigations")
                        .header("Authorization", login(SUBMITTER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(order.getOrderNo())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("DEPT_ADMIN + 桩模型 → 业务码 200，报告只含编号、渲染三段齐全")
    void deptAdminGetsRenderedReport() throws Exception {
        insertUser(DEPT_ADMIN_ID, "ctrl-dept-admin", DEPT_A, DEPT_ADMIN_ROLE_ID);
        WorkOrder order = insertOrder(DEPT_ADMIN_ID, DEPT_A);
        insertLog(order);

        MODEL.enqueue(StubModelServer.json(StubModelServer.toolCallTurn("call_1", OrderFactsTool.NAME,
                        "{\"orderNo\":\"" + order.getOrderNo() + "\"}")),
                StubModelServer.json(StubModelServer.finishTurn("ORDER_STATUS",
                        List.of("E1", "E2", "E3"), List.of("CONTACT_ASSIGNEE"))));

        mockMvc.perform(post("/api/agent/investigations")
                        .header("Authorization", login(DEPT_ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(order.getOrderNo())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.problemType").value("ORDER_STATUS"))
                .andExpect(jsonPath("$.data.evidenceIds").isArray())
                .andExpect(jsonPath("$.data.renderedText").value(org.hamcrest.Matchers.containsString("【已核实事实】")))
                .andExpect(jsonPath("$.data.renderedText").value(org.hamcrest.Matchers.containsString("【证据缺口】")))
                .andExpect(jsonPath("$.data.renderedText").value(org.hamcrest.Matchers.containsString("【下一步核实建议】")));
    }

    @Test
    @DisplayName("起点单跨部门 → 业务码 403，且不产出报告")
    void crossDepartmentIsRejected() throws Exception {
        insertUser(DEPT_ADMIN_ID, "ctrl-dept-admin", DEPT_A, DEPT_ADMIN_ROLE_ID);
        insertUser(OTHER_DEPT_USER_ID, "ctrl-other", DEPT_B, SUBMITTER_ROLE_ID);
        WorkOrder otherDeptOrder = insertOrder(OTHER_DEPT_USER_ID, DEPT_B);

        mockMvc.perform(post("/api/agent/investigations")
                        .header("Authorization", login(DEPT_ADMIN_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(otherDeptOrder.getOrderNo())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    // ---------- 夹具 ----------

    private void insertUser(long id, String username, long deptId, long roleId) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setPassword("$2a$10$1s93/XO7m.kI61bcmONyRutCPPMw9hqxd14syjk.8G/82JKi9HVIe");
        user.setDeptId(deptId);
        user.setStatus(1);
        userMapper.insert(user);
        UserRole binding = new UserRole();
        binding.setUserId(id);
        binding.setRoleId(roleId);
        userRoleMapper.insert(binding);
    }

    private WorkOrder insertOrder(long submitterId, long submitterDept) {
        LocalDateTime now = LocalDateTime.now();
        WorkOrder order = new WorkOrder();
        order.setOrderNo(String.format("WO-20261006-%05d", SEQ.incrementAndGet()));
        order.setTitle("控制器测试工单");
        order.setContent("同步调查接口");
        order.setType("NETWORK");
        order.setPriority(0);
        order.setStatus("IN_PROGRESS");
        order.setSubmitterId(submitterId);
        order.setRejectCount(0);
        order.setMaxReject(3);
        order.setTriageStatus("DONE");
        order.setVersion(0);
        order.setSlaDeadline(now.plusDays(1));
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
        workOrderMapper.insert(order);
        return order;
    }

    private void insertLog(WorkOrder order) {
        WorkOrderLog log = new WorkOrderLog();
        log.setOrderId(order.getId());
        log.setOrderNo(order.getOrderNo());
        log.setOperatorId(DEPT_ADMIN_ID);
        log.setAction("ACCEPT");
        log.setNewStatus("IN_PROGRESS");
        log.setCreatedAt(LocalDateTime.now());
        workOrderLogMapper.insert(log);
    }
}
