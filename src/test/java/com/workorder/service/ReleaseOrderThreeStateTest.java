package com.workorder.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.workorder.common.enums.ReleaseResult;
import com.workorder.entity.WorkOrder;
import com.workorder.entity.WorkOrderLog;
import com.workorder.mapper.WorkOrderLogMapper;
import com.workorder.mapper.WorkOrderMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code releaseOrder} 三态返回值的库级验证（P1 步骤 4 的核心之一）。
 *
 * <p>改造前：工单不存在 → 抛异常；状态守卫未命中 → 静默 return。消费者因此无法区分
 * "真释放了 / 状态已变 / 内部出错"这三种情况，ACK/NACK 契约也就无从谈起。
 *
 * <p>隔离：独立测试库 + id 水位线清理（沿用 outbox 相关测试的做法，需要真实提交）。
 */
@SpringBootTest
@ActiveProfiles("test")
class ReleaseOrderThreeStateTest {

    @Autowired private WorkOrderService workOrderService;
    @Autowired private WorkOrderMapper workOrderMapper;
    @Autowired private WorkOrderLogMapper workOrderLogMapper;
    @Autowired private TransactionTemplate transactionTemplate;

    private Long orderWatermark;
    private Long logWatermark;

    @BeforeEach
    void setUp() {
        orderWatermark = maxOrderId();
        logWatermark = maxLogId();
    }

    @AfterEach
    void cleanUp() {
        workOrderLogMapper.delete(new LambdaQueryWrapper<WorkOrderLog>().gt(WorkOrderLog::getId, logWatermark));
        workOrderMapper.delete(new LambdaQueryWrapper<WorkOrder>().gt(WorkOrder::getId, orderWatermark));
    }

    @Test
    @DisplayName("RELEASED：ACCEPTED 工单被真释放（状态 RELEASED、assignee 清空、version+1）")
    void acceptedOrder_released() {
        Long id = insertOrder("ACCEPTED", 127L);

        assertEquals(ReleaseResult.RELEASED, workOrderService.releaseOrder(id));

        WorkOrder after = workOrderMapper.selectById(id);
        assertEquals("RELEASED", after.getStatus());
        assertNull(after.getAssigneeId(), "释放后必须清空 assignee（工单回到可抢状态的前提）");
        assertEquals(1, after.getVersion().intValue(), "释放会 version+1");
    }

    @Test
    @DisplayName("SKIPPED：状态守卫未命中（已被 START 改成 IN_PROGRESS）→ 不报错、不改状态")
    void notAcceptedOrder_skipped() {
        Long id = insertOrder("IN_PROGRESS", 127L);

        assertEquals(ReleaseResult.SKIPPED, workOrderService.releaseOrder(id));

        WorkOrder after = workOrderMapper.selectById(id);
        assertEquals("IN_PROGRESS", after.getStatus(), "跳过必须不改任何状态");
        assertEquals(127L, after.getAssigneeId().longValue(), "跳过不得清空 assignee");
    }

    @Test
    @DisplayName("SKIPPED：重复释放同一条（天然幂等——第二次影响 0 行）")
    void secondRelease_skipped() {
        Long id = insertOrder("ACCEPTED", 127L);

        assertEquals(ReleaseResult.RELEASED, workOrderService.releaseOrder(id));
        assertEquals(ReleaseResult.SKIPPED, workOrderService.releaseOrder(id),
                "同一工单第二次释放应跳过——这就是当前消费端幂等的来源（状态守卫，而非去重表）");
    }

    @Test
    @DisplayName("ERROR：工单不存在 → 返回 ERROR（不再抛异常，消费者可据此记 ERROR 并 ACK）")
    void missingOrder_error() {
        assertEquals(ReleaseResult.ERROR, workOrderService.releaseOrder(999_999_999L));
    }

    // ────────────── helpers ──────────────

    private Long insertOrder(String status, Long assigneeId) {
        WorkOrder[] holder = new WorkOrder[1];
        transactionTemplate.execute(tx -> {
            WorkOrder o = new WorkOrder();
            o.setOrderNo("TST-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 18));
            o.setTitle("三态释放测试");
            o.setContent("x");
            o.setType("NETWORK");
            o.setPriority(0);
            o.setStatus(status);
            o.setSubmitterId(1L);
            o.setAssigneeId(assigneeId);
            o.setRejectCount(0);
            o.setMaxReject(3);
            o.setVersion(0);
            o.setCreatedAt(LocalDateTime.now());
            o.setUpdatedAt(LocalDateTime.now());
            workOrderMapper.insert(o);
            holder[0] = o;
            return null;
        });
        return holder[0].getId();
    }

    private Long maxOrderId() {
        WorkOrder o = workOrderMapper.selectOne(new LambdaQueryWrapper<WorkOrder>()
                .select(WorkOrder::getId).orderByDesc(WorkOrder::getId).last("LIMIT 1"));
        return o == null || o.getId() == null ? 0L : o.getId();
    }

    private Long maxLogId() {
        WorkOrderLog l = workOrderLogMapper.selectOne(new LambdaQueryWrapper<WorkOrderLog>()
                .select(WorkOrderLog::getId).orderByDesc(WorkOrderLog::getId).last("LIMIT 1"));
        return l == null || l.getId() == null ? 0L : l.getId();
    }
}
