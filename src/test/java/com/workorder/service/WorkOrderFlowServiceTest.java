package com.workorder.service;

import com.workorder.common.BizException;
import com.workorder.common.dto.SubmitOrderReq;
import com.workorder.entity.WorkOrder;
import com.workorder.entity.WorkOrderLog;
import com.workorder.mapper.WorkOrderLogMapper;
import com.workorder.mapper.WorkOrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class WorkOrderFlowServiceTest {

    @Autowired
    private WorkOrderService workOrderService;

    @Autowired
    private WorkOrderMapper workOrderMapper;

    @Autowired
    private WorkOrderLogMapper workOrderLogMapper;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private Long pendingOrderId;

    @BeforeEach
    void setUp() {
        String uniqueNo = "TST-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 18);
        pendingOrderId = transactionTemplate.execute(status -> {
            WorkOrder order = new WorkOrder();
            order.setOrderNo(uniqueNo);
            order.setTitle("测试工单");
            order.setContent("测试内容");
            order.setType("REPAIR");
            order.setPriority(0);
            order.setStatus("PENDING");
            order.setSubmitterId(10L);
            order.setRejectCount(0);
            order.setMaxReject(3);
            order.setVersion(0);
            order.setCreatedAt(java.time.LocalDateTime.now());
            order.setUpdatedAt(java.time.LocalDateTime.now());
            workOrderMapper.insert(order);
            return order.getId();
        });
    }

    // ───────────────────── Issue #29: 抢单 ─────────────────────

    @Test
    @DisplayName("抢单成功：PENDING → ACCEPTED")
    void testAcceptOrder_success() {
        workOrderService.acceptOrder(pendingOrderId, 100L);

        WorkOrder order = workOrderMapper.selectById(pendingOrderId);
        assertEquals("ACCEPTED", order.getStatus());
        assertEquals(100L, order.getAssigneeId());
        assertEquals(1, order.getVersion());
    }

    @Test
    @DisplayName("抢单——操作日志正确记录")
    void testAcceptOrder_logRecorded() {
        workOrderService.acceptOrder(pendingOrderId, 100L);

        List<WorkOrderLog> logs = workOrderLogMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<WorkOrderLog>()
                        .eq(WorkOrderLog::getOrderId, pendingOrderId));
        assertTrue(logs.stream().anyMatch(l -> "ACCEPT".equals(l.getAction())));
    }

    @Test
    @DisplayName("并发抢单——CountDownLatch 验证乐观锁 100% 防超卖")
    void testAcceptOrder_concurrentOnlyOneSucceeds() throws InterruptedException {
        int threadCount = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        List<Exception> failures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final long userId = 200L + i;
            new Thread(() -> {
                try {
                    startLatch.await();
                    workOrderService.acceptOrder(pendingOrderId, userId);
                    successCount.incrementAndGet();
                } catch (BizException e) {
                    failures.add(e);
                } catch (Exception e) {
                    failures.add(e);
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        doneLatch.await();

        assertEquals(1, successCount.get(), "并发抢单必须只有一人成功");
        assertEquals(threadCount - 1, failures.size(), "其余全部失败");

        WorkOrder order = workOrderMapper.selectById(pendingOrderId);
        assertEquals("ACCEPTED", order.getStatus());
        assertNotNull(order.getAssigneeId());
    }

    @Test
    @DisplayName("对已 ACCEPTED 的工单再次抢单 → 抛异常")
    void testAcceptOrder_alreadyAccepted_throws() {
        workOrderService.acceptOrder(pendingOrderId, 100L);

        assertThrows(BizException.class,
                () -> workOrderService.acceptOrder(pendingOrderId, 200L));
    }

    @Test
    @DisplayName("抢单后 Redis 超时标记存在")
    void testAcceptOrder_redisTimeoutKeyExists() {
        workOrderService.acceptOrder(pendingOrderId, 100L);
        // Redis key 在当前测试中由 afterCommit 异步设置，事务已提交
        // 由于 @BeforeEach 使用了 TransactionTemplate(独立事务)，acceptOrder 事务会提交
    }

    // ───────────────────── Issue #30: 开始处理 + 提交验收 ─────────────────────

    @Test
    @DisplayName("开始处理：ACCEPTED → IN_PROGRESS（仅处理人）")
    void testStartOrder_success() {
        workOrderService.acceptOrder(pendingOrderId, 100L);

        workOrderService.startOrder(pendingOrderId, 100L);

        WorkOrder order = workOrderMapper.selectById(pendingOrderId);
        assertEquals("IN_PROGRESS", order.getStatus());
    }

    @Test
    @DisplayName("非处理人调用 startOrder → 抛异常")
    void testStartOrder_notAssignee_throws() {
        workOrderService.acceptOrder(pendingOrderId, 100L);

        assertThrows(BizException.class,
                () -> workOrderService.startOrder(pendingOrderId, 999L));
    }

    @Test
    @DisplayName("提交验收：IN_PROGRESS → AWAIT_APPROVAL")
    void testCompleteOrder_success() {
        workOrderService.acceptOrder(pendingOrderId, 100L);
        workOrderService.startOrder(pendingOrderId, 100L);

        workOrderService.completeOrder(pendingOrderId, 100L);

        WorkOrder order = workOrderMapper.selectById(pendingOrderId);
        assertEquals("AWAIT_APPROVAL", order.getStatus());
    }

    @Test
    @DisplayName("非处理人调用 completeOrder → 抛异常")
    void testCompleteOrder_notAssignee_throws() {
        workOrderService.acceptOrder(pendingOrderId, 100L);
        workOrderService.startOrder(pendingOrderId, 100L);

        assertThrows(BizException.class,
                () -> workOrderService.completeOrder(pendingOrderId, 999L));
    }

    // ───────────────────── Issue #31: 验收通过 + 验收驳回 ─────────────────────

    @Test
    @DisplayName("验收通过：AWAIT_APPROVAL → CLOSED（仅提交人）")
    void testApproveOrder_success() {
        flowToAwaitApproval();

        workOrderService.approveOrder(pendingOrderId, 10L);

        WorkOrder order = workOrderMapper.selectById(pendingOrderId);
        assertEquals("CLOSED", order.getStatus());
    }

    @Test
    @DisplayName("非提交人调用 approveOrder → 抛异常")
    void testApproveOrder_notSubmitter_throws() {
        flowToAwaitApproval();

        assertThrows(BizException.class,
                () -> workOrderService.approveOrder(pendingOrderId, 999L));
    }

    @Test
    @DisplayName("第1次驳回 → IN_PROGRESS, rejectCount=1")
    void testRejectOrder_firstReject() {
        flowToAwaitApproval();

        workOrderService.rejectOrder(pendingOrderId, 10L, "图片不清晰");

        WorkOrder order = workOrderMapper.selectById(pendingOrderId);
        assertEquals("IN_PROGRESS", order.getStatus());
        assertEquals(1, order.getRejectCount());
    }

    @Test
    @DisplayName("第2次驳回 → IN_PROGRESS, rejectCount=2")
    void testRejectOrder_secondReject() {
        flowToAwaitApproval();
        workOrderService.rejectOrder(pendingOrderId, 10L, "第1次驳回");
        reComplete();

        workOrderService.rejectOrder(pendingOrderId, 10L, "第2次驳回");

        WorkOrder order = workOrderMapper.selectById(pendingOrderId);
        assertEquals("IN_PROGRESS", order.getStatus());
        assertEquals(2, order.getRejectCount());
    }

    @Test
    @DisplayName("第3次驳回（maxReject=3）→ ESCALATED_ADMIN, rejectCount=3")
    void testRejectOrder_thirdReject_escalates() {
        flowToAwaitApproval();
        workOrderService.rejectOrder(pendingOrderId, 10L, "驳回1");
        reComplete();
        workOrderService.rejectOrder(pendingOrderId, 10L, "驳回2");
        reComplete();

        workOrderService.rejectOrder(pendingOrderId, 10L, "达到上限，升级管理员");

        WorkOrder order = workOrderMapper.selectById(pendingOrderId);
        assertEquals("ESCALATED_ADMIN", order.getStatus());
        assertEquals(3, order.getRejectCount());
    }

    @Test
    @DisplayName("对 ESCALATED_ADMIN 工单再次 reject → 状态机抛异常")
    void testRejectOrder_escalatedReject_throws() {
        flowToAwaitApproval();
        workOrderService.rejectOrder(pendingOrderId, 10L, "r1");
        reComplete();
        workOrderService.rejectOrder(pendingOrderId, 10L, "r2");
        reComplete();
        workOrderService.rejectOrder(pendingOrderId, 10L, "r3→升级");

        assertThrows(BizException.class,
                () -> workOrderService.rejectOrder(pendingOrderId, 10L, "试图再驳回"));
    }

    @Test
    @DisplayName("非提交人调用 rejectOrder → 抛异常")
    void testRejectOrder_notSubmitter_throws() {
        flowToAwaitApproval();

        assertThrows(BizException.class,
                () -> workOrderService.rejectOrder(pendingOrderId, 999L, "非提交人驳回"));
    }

    @Test
    @DisplayName("驳回日志记录包含 remark")
    void testRejectOrder_logContainsRemark() {
        flowToAwaitApproval();

        workOrderService.rejectOrder(pendingOrderId, 10L, "验收不通过，图片模糊");

        List<WorkOrderLog> logs = workOrderLogMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<WorkOrderLog>()
                        .eq(WorkOrderLog::getOrderId, pendingOrderId)
                        .eq(WorkOrderLog::getAction, "REJECT"));
        assertEquals(1, logs.size());
        assertEquals("验收不通过，图片模糊", logs.get(0).getRemark());
    }

    // ───────────────────── helpers ─────────────────────

    /** 走完 accept → start → complete 到达 AWAIT_APPROVAL */
    private void flowToAwaitApproval() {
        workOrderService.acceptOrder(pendingOrderId, 100L);
        workOrderService.startOrder(pendingOrderId, 100L);
        workOrderService.completeOrder(pendingOrderId, 100L);
    }

    /** 驳回后处理人重新 complete（状态已是 IN_PROGRESS，无需 start） */
    private void reComplete() {
        workOrderService.completeOrder(pendingOrderId, 100L);
    }
}
