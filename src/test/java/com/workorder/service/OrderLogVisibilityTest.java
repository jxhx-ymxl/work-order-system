package com.workorder.service;

import com.workorder.common.BizException;
import com.workorder.common.ErrorCode;
import com.workorder.common.vo.WorkOrderLogVO;
import com.workorder.entity.User;
import com.workorder.entity.UserRole;
import com.workorder.entity.WorkOrder;
import com.workorder.entity.WorkOrderLog;
import com.workorder.mapper.UserMapper;
import com.workorder.mapper.UserRoleMapper;
import com.workorder.mapper.WorkOrderLogMapper;
import com.workorder.mapper.WorkOrderMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 「已有日志授权缺口」的判据（`docs/AGENT-PLAN.md` §2.3 第 3 项）。
 *
 * <p>缺口（已复现，2026-10-06）：`GET /api/orders/{id}/logs` 既不读调用者身份、也没有数据级越权校验，
 * 而 `WorkOrderLogMapper.selectByOrderId` 的 SQL 里只有 `WHERE l.order_id = #{orderId}`——
 * 于是任何登录用户都能读到任意工单的操作日志（含操作人姓名与备注），
 * 而同一 viewer 走详情路径 `getOrderDetail` 是会被 `FORBIDDEN` 拒的。
 *
 * <p>本轮判据：**日志可见性必须与详情同源**（`WorkOrderServiceImpl.requireVisibleOrder`），
 * 拒绝落在**业务 code**（`ErrorCode.FORBIDDEN`）上，不允许用"返回 200 但列表为空"来伪装拦截。
 */
@SpringBootTest
@Transactional
class OrderLogVisibilityTest {

    private static final long OWNER_ID = 1L;      // admin，SYS_ADMIN，工单 A 的提交人
    private static final long OUTSIDER_ID = 2L;   // 新建的 SUBMITTER，与工单 A 无任何关系
    private static final long SUBMITTER_ROLE_ID = 2L;

    @Autowired
    private WorkOrderService workOrderService;

    @Autowired
    private WorkOrderLogService workOrderLogService;

    @Autowired
    private WorkOrderMapper workOrderMapper;

    @Autowired
    private WorkOrderLogMapper workOrderLogMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private UserRoleMapper userRoleMapper;

    @Test
    @DisplayName("非可见者读日志：与详情同源的 FORBIDDEN（不是空列表）")
    void outsiderCannotReadLogs() {
        insertOutsiderUser();
        WorkOrder orderA = insertOrder(OWNER_ID, "IN_PROGRESS", OWNER_ID);
        insertLog(orderA.getId(), orderA.getOrderNo(), "SUBMIT", "PENDING", "日志越权复现");

        // 对照基准：详情路径本来就拒绝（既有行为）
        BizException detailVerdict = assertThrows(BizException.class,
                () -> workOrderService.getOrderDetail(orderA.getId(), OUTSIDER_ID));
        assertEquals(ErrorCode.FORBIDDEN, detailVerdict.getErrorCode());

        // 日志路径必须给出同一个判定：抛 FORBIDDEN，而不是"200 + 空列表"
        BizException logsVerdict = assertThrows(BizException.class,
                () -> workOrderService.getOrderLogs(orderA.getId(), OUTSIDER_ID),
                "日志可见性必须与详情同源：越权者应当被拒，而不是拿到日志或拿到空列表");
        assertEquals(ErrorCode.FORBIDDEN, logsVerdict.getErrorCode(),
                "拦截要落在业务 code 上");
    }

    @Test
    @DisplayName("提交人读自己工单的日志：放行，且与日志服务返回一致")
    void ownerCanReadOwnLogs() {
        insertOutsiderUser();
        WorkOrder ownOrder = insertOrder(OUTSIDER_ID, "PENDING", null);
        insertLog(ownOrder.getId(), ownOrder.getOrderNo(), "SUBMIT", "PENDING", "自己的单");

        List<WorkOrderLogVO> logs = workOrderService.getOrderLogs(ownOrder.getId(), OUTSIDER_ID);

        assertEquals(1, logs.size());
        assertEquals("SUBMIT", logs.get(0).getAction());
        assertEquals("admin", logs.get(0).getOperatorName());
    }

    @Test
    @DisplayName("升级单：日志判定与详情同源（提交人也不行，管理员才行）")
    void escalatedOrderLogsMatchDetailVerdict() {
        insertOutsiderUser();
        // 升级单的提交人是 admin；按规则只有 DEPT_ADMIN / SYS_ADMIN 可见（防信息泄露）
        WorkOrder escalated = insertOrder(OWNER_ID, "ESCALATED_ADMIN", null);
        insertLog(escalated.getId(), escalated.getOrderNo(), "ESCALATE", "ESCALATED_ADMIN", "升级");

        assertEquals(ErrorCode.FORBIDDEN, assertThrows(BizException.class,
                () -> workOrderService.getOrderDetail(escalated.getId(), OUTSIDER_ID)).getErrorCode());
        assertEquals(ErrorCode.FORBIDDEN, assertThrows(BizException.class,
                () -> workOrderService.getOrderLogs(escalated.getId(), OUTSIDER_ID)).getErrorCode(),
                "升级单：日志路径也必须拒绝非管理员");

        // SYS_ADMIN 两条路径都放行
        assertNotNull(workOrderService.getOrderDetail(escalated.getId(), OWNER_ID));
        assertEquals(1, workOrderService.getOrderLogs(escalated.getId(), OWNER_ID).size());
    }

    private void insertOutsiderUser() {
        User user = new User();
        user.setId(OUTSIDER_ID);
        user.setUsername("tst-log-outsider");
        user.setPassword("$2a$10$1s93/XO7m.kI61bcmONyRutCPPMw9hqxd14syjk.8G/82JKi9HVIe");
        user.setStatus(1);
        userMapper.insert(user);

        UserRole binding = new UserRole();
        binding.setUserId(OUTSIDER_ID);
        binding.setRoleId(SUBMITTER_ROLE_ID);
        userRoleMapper.insert(binding);
    }

    private WorkOrder insertOrder(long submitterId, String status, Long assigneeId) {
        WorkOrder order = new WorkOrder();
        // order_no 是 VARCHAR(22)：前缀 4 + 16 位十六进制 = 20，够用
        order.setOrderNo("TST-" + Long.toHexString(System.nanoTime()));
        order.setTitle("日志可见性测试工单");
        order.setContent("仅用于「日志授权缺口」的复现与判据");
        order.setType("NETWORK");
        order.setPriority(0);
        order.setStatus(status);
        order.setSubmitterId(submitterId);
        order.setAssigneeId(assigneeId);
        order.setRejectCount(0);
        order.setMaxReject(3);
        order.setTriageStatus("DONE");
        order.setSlaDeadline(LocalDateTime.now().plusDays(1));
        order.setVersion(0);
        order.setCreatedAt(LocalDateTime.now());
        order.setUpdatedAt(LocalDateTime.now());
        workOrderMapper.insert(order);
        return order;
    }

    private void insertLog(Long orderId, String orderNo, String action, String newStatus, String remark) {
        WorkOrderLog log = new WorkOrderLog();
        log.setOrderId(orderId);
        log.setOrderNo(orderNo);
        log.setOperatorId(OWNER_ID);
        log.setAction(action);
        log.setOldStatus(null);
        log.setNewStatus(newStatus);
        log.setRemark(remark);
        log.setCreatedAt(LocalDateTime.now());
        workOrderLogMapper.insert(log);
    }
}
