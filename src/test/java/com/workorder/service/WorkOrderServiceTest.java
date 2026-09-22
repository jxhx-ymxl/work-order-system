package com.workorder.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.workorder.common.PageResult;
import com.workorder.common.dto.PageQuery;
import com.workorder.common.dto.SubmitOrderReq;
import com.workorder.common.vo.StatsVO;
import com.workorder.common.vo.WorkOrderVO;
import com.workorder.entity.Role;
import com.workorder.entity.SlaConfig;
import com.workorder.entity.User;
import com.workorder.entity.UserRole;
import com.workorder.entity.WorkOrder;
import com.workorder.entity.WorkOrderLog;
import com.workorder.mapper.RoleMapper;
import com.workorder.mapper.SlaConfigMapper;
import com.workorder.mapper.UserMapper;
import com.workorder.mapper.UserRoleMapper;
import com.workorder.mapper.WorkOrderLogMapper;
import com.workorder.mapper.WorkOrderMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class WorkOrderServiceTest {

    @Autowired
    private WorkOrderService workOrderService;

    @Autowired
    private WorkOrderMapper workOrderMapper;

    @Autowired
    private WorkOrderLogMapper workOrderLogMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private RoleMapper roleMapper;

    @Autowired
    private UserRoleMapper userRoleMapper;

    @Autowired
    private SlaConfigMapper slaConfigMapper;

    @Test
    @DisplayName("提交工单——主表和日志表同时出现数据")
    void testSubmitOrder_bothTablesInserted() {
        SubmitOrderReq req = buildReq("空调报修", "3楼空调不制冷", "NETWORK", 0);

        WorkOrder order = workOrderService.submitOrder(req, 1L);

        assertNotNull(order.getId());
        assertTrue(order.getOrderNo().startsWith("WO-"));
        assertEquals("PENDING", order.getStatus());
        assertEquals(0, order.getVersion());

        List<WorkOrderLog> logs = workOrderLogMapper.selectList(
                new LambdaQueryWrapper<WorkOrderLog>()
                        .eq(WorkOrderLog::getOrderId, order.getId()));
        assertEquals(1, logs.size());
        WorkOrderLog log = logs.get(0);
        assertEquals("SUBMIT", log.getAction());
        assertNull(log.getOldStatus());
        assertEquals("PENDING", log.getNewStatus());
        assertEquals(order.getOrderNo(), log.getOrderNo());
        assertEquals(1L, log.getOperatorId());
    }

    @Test
    @DisplayName("提交工单——SLA配置存在时计算deadline")
    void testSubmitOrder_slaDeadlineCalculated() {
        SubmitOrderReq req = buildReq("紧急报修", "服务器宕机", "NETWORK", 1);
        WorkOrder order = workOrderService.submitOrder(req, 1L);

        assertNotNull(order.getSlaDeadline());
        assertTrue(order.getSlaDeadline().isAfter(java.time.LocalDateTime.now()));
    }

    @Test
    @DisplayName("提交工单——SLA 配置缺失时走兜底配置（不再是 null）")
    void testSubmitOrder_configMissing_usesFallbackConfig() {
        // 触发方式说明（C3 要求注明选择理由）：
        //   NETWORK 是 R4 新类型集合中的合法值，但当前 t_sla_config 仍是旧集合
        //   （REPAIR/LEAVE/REIMBURSE/OTHER），因此 NETWORK/0 必然查不到配置，
        //   正好走到 B2 的兜底分支。选它而不是 Mockito 造 null 的理由：本类已在真实
        //   Spring 上下文里跑，用真实数据触发最贴近生产路径。
        //   ⚠ P0b 完成类型枚举替换后，NETWORK/0 会变成有配置，本用例必须改为
        //     用 Mockito 让 SlaConfigMapper 返回 null（见 WorkOrderSubmitFallbackTest 的同类断言）。
        SubmitOrderReq req = buildReq("特殊工单", "无匹配SLA", "NETWORK", 0);

        LocalDateTime before = LocalDateTime.now();
        WorkOrder order = workOrderService.submitOrder(req, 1L);
        LocalDateTime after = LocalDateTime.now();

        assertNotNull(order.getSlaDeadline(), "配置缺失时必须走兜底配置，不得再落 null");

        // 兜底值来自 OTHER + 普通 的 finish_minutes —— 从配置表读，不硬编码分钟数
        SlaConfig fallback = slaConfigMapper.selectOne(new LambdaQueryWrapper<SlaConfig>()
                .eq(SlaConfig::getType, "OTHER")
                .eq(SlaConfig::getPriority, 0));
        assertNotNull(fallback, "兜底组合 OTHER/0 必须存在，否则应产生 error 日志（见 SlaConfigStartupCheck）");
        assertNotNull(fallback.getFinishMinutes(), "兜底组合的 finish_minutes 不得为空");

        LocalDateTime earliest = before.plusMinutes(fallback.getFinishMinutes());
        LocalDateTime latest = after.plusMinutes(fallback.getFinishMinutes());
        assertFalse(order.getSlaDeadline().isBefore(earliest),
                "兜底 deadline 应等于 提交时刻 + OTHER/0.finish_minutes(" + fallback.getFinishMinutes() + ")");
        assertFalse(order.getSlaDeadline().isAfter(latest),
                "兜底 deadline 应等于 提交时刻 + OTHER/0.finish_minutes(" + fallback.getFinishMinutes() + ")");
    }

    @Test
    @DisplayName("提交工单——priority为null时默认0")
    void testSubmitOrder_defaultPriority() {
        SubmitOrderReq req = buildReq("默认优先级", "测试内容", "NETWORK", null);
        WorkOrder order = workOrderService.submitOrder(req, 1L);

        assertEquals(0, order.getPriority());
    }

    @Test
    @DisplayName("提交工单——编号格式校验 WO-YYYYMMDD-XXXXX")
    void testSubmitOrder_orderNoFormat() {
        SubmitOrderReq req = buildReq("格式测试", "校验编号", "NETWORK", 0);
        WorkOrder order = workOrderService.submitOrder(req, 1L);

        String today = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
        assertTrue(order.getOrderNo().matches("WO-" + today + "-\\d{5}"));
    }

    // ────────────── Issue #26: RBAC 数据过滤 ──────────────

    @Test
    @DisplayName("RBAC: SUBMITTER角色仅看到自己提交的工单")
    void testListOrders_submitterOnlySeesOwn() {
        User s1 = createUser("rbac_submitter", 1L);
        User s2 = createUser("rbac_submitter2", 1L);
        assignRole(s1.getId(), "SUBMITTER");
        assignRole(s2.getId(), "SUBMITTER");

        workOrderService.submitOrder(buildReq("S1工单", "s1", "NETWORK", 0), s1.getId());
        workOrderService.submitOrder(buildReq("S2工单", "s2", "NETWORK", 0), s2.getId());

        PageQuery query = new PageQuery();
        query.setPage(1);
        query.setSize(20);
        PageResult<WorkOrderVO> result = workOrderService.listOrders(query, s1.getId());

        for (WorkOrderVO vo : result.getRecords()) {
            assertEquals(s1.getId(), vo.getSubmitterId(),
                    "SUBMITTER不应看到其他人的工单");
        }
    }

    @Test
    @DisplayName("RBAC: HANDLER角色看到自己接的单+PENDING池")
    void testListOrders_handlerSeesOwnAndPendingPool() {
        User submitter = createUser("rbac_submitter3", 1L);
        User handler = createUser("rbac_handler", 1L);
        assignRole(submitter.getId(), "SUBMITTER");
        assignRole(handler.getId(), "HANDLER");

        // 提交3条工单
        WorkOrder pending1 = workOrderService.submitOrder(buildReq("待抢1", "池", "NETWORK", 0), submitter.getId());
        WorkOrder pending2 = workOrderService.submitOrder(buildReq("待抢2", "池", "NETWORK", 0), submitter.getId());
        WorkOrder assigned = workOrderService.submitOrder(buildReq("已接", "已分配", "NETWORK", 0), submitter.getId());

        // 手动将一条分给handler（模拟抢单）
        assigned.setAssigneeId(handler.getId());
        assigned.setStatus("ACCEPTED");
        workOrderMapper.updateById(assigned);

        PageQuery query = new PageQuery();
        query.setPage(1);
        query.setSize(20);
        PageResult<WorkOrderVO> result = workOrderService.listOrders(query, handler.getId());

        List<Long> visibleIds = result.getRecords().stream().map(WorkOrderVO::getId).toList();
        assertTrue(visibleIds.contains(pending1.getId()), "HANDLER应看到PENDING池");
        assertTrue(visibleIds.contains(pending2.getId()), "HANDLER应看到PENDING池");
        assertTrue(visibleIds.contains(assigned.getId()), "HANDLER应看到自己接的单");
    }

    @Test
    @DisplayName("RBAC: SYS_ADMIN角色看到全部工单")
    void testListOrders_adminSeesAll() {
        User submitter = createUser("rbac_submitter4", 1L);
        assignRole(submitter.getId(), "SUBMITTER");
        workOrderService.submitOrder(buildReq("全量测试", "admin应看到", "NETWORK", 0), submitter.getId());

        User admin = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, "admin"));

        PageQuery query = new PageQuery();
        query.setPage(1);
        query.setSize(100);
        PageResult<WorkOrderVO> result = workOrderService.listOrders(query, admin.getId());

        assertTrue(result.getTotal() >= 1);
    }

    @Test
    @DisplayName("统计: scope=DEPT 返回部门级统计")
    void testGetStats_deptScope() {
        User deptUser = createUser("rbac_dept_user", 100L);
        workOrderService.submitOrder(buildReq("部门工单", "部门统计", "NETWORK", 0), deptUser.getId());

        List<StatsVO> stats = workOrderService.getStats("DEPT", deptUser.getId());
        assertNotNull(stats);
        assertFalse(stats.isEmpty());
        // 验证至少包含PENDING状态
        assertTrue(stats.stream().anyMatch(s -> "PENDING".equals(s.getStatus())));
    }

    @Test
    @DisplayName("统计: scope=ALL 返回全局统计")
    void testGetStats_allScope() {
        // 确保有数据：在当前事务内提交一条工单
        workOrderService.submitOrder(buildReq("全局统计测试", "ALL scope", "NETWORK", 0), 1L);

        List<StatsVO> stats = workOrderService.getStats("ALL", 1L);
        assertNotNull(stats);
        assertFalse(stats.isEmpty());
        long total = stats.stream().mapToLong(StatsVO::getCount).sum();
        assertTrue(total > 0, "全局统计应至少有工单数据");
    }

    // ────────────── helpers ──────────────

    private User createUser(String username, Long deptId) {
        User existing = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (existing != null) return existing;

        User user = new User();
        user.setUsername(username);
        user.setPassword("$2a$encoded");
        user.setDeptId(deptId);
        user.setStatus(1);
        userMapper.insert(user);
        return user;
    }

    private void assignRole(Long userId, String roleCode) {
        Role role = roleMapper.selectOne(
                new LambdaQueryWrapper<Role>().eq(Role::getRoleCode, roleCode));
        if (role == null) return;
        boolean exists = userRoleMapper.exists(
                new LambdaQueryWrapper<UserRole>()
                        .eq(UserRole::getUserId, userId)
                        .eq(UserRole::getRoleId, role.getId()));
        if (!exists) {
            UserRole ur = new UserRole();
            ur.setUserId(userId);
            ur.setRoleId(role.getId());
            userRoleMapper.insert(ur);
        }
    }

    private SubmitOrderReq buildReq(String title, String content, String type, Integer priority) {
        SubmitOrderReq req = new SubmitOrderReq();
        req.setTitle(title);
        req.setContent(content);
        req.setType(type);
        req.setPriority(priority);
        return req;
    }
}
