package com.workorder.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.workorder.common.event.OrderEvent;
import com.workorder.entity.EventOutbox;
import com.workorder.entity.SlaConfig;
import com.workorder.entity.User;
import com.workorder.entity.WorkOrder;
import com.workorder.mapper.EventOutboxMapper;
import com.workorder.mapper.SlaConfigMapper;
import com.workorder.mapper.UserMapper;
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
 * P1 步骤 2 的核心验证：**outbox 写路径必须跟随业务事务**。
 *
 * <p>本类存在的理由只有一条——**断言业务事务回滚时 outbox 无记录**。这是"用 outbox 取代
 * afterCommit 直发"的全部价值所在；如果这条断言不成立，整个 P1 就是白做。
 *
 * <p>隔离：沿用 {@code WorkOrderFlowServiceTest} 的做法——指向独立测试库（profile test），
 * 并在用例前后用 id 水位线清理自己写入的行（测试库不能跨轮次累积）。
 */
@SpringBootTest
@ActiveProfiles("test")
class OutboxWritePathTest {

    @Autowired private WorkOrderService workOrderService;
    @Autowired private WorkOrderMapper workOrderMapper;
    @Autowired private EventOutboxMapper eventOutboxMapper;
    @Autowired private SlaConfigMapper slaConfigMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private TransactionTemplate transactionTemplate;

    private Long orderId;
    private Long handlerId;
    private Long orderWatermark;
    private Long outboxWatermark;
    private Long userWatermark;

    private static final String TYPE = "NETWORK";
    private static final int PRIORITY = 0;

    @BeforeEach
    void setUp() {
        orderWatermark = maxOrderId();
        outboxWatermark = maxOutboxId();
        userWatermark = maxUserId();

        // 造一个启用的处理人（assignOrder 会校验该用户存在且 status=1）
        User handler = new User();
        handler.setUsername("outbox-test-handler-" + System.nanoTime());
        handler.setPassword("x");
        handler.setStatus(1);
        userMapper.insert(handler);
        handlerId = handler.getId();

        // 造一张 PENDING 工单（提交式写入，供后续 acceptOrder 在另一个事务里看到）
        transactionTemplate.execute(status -> {
            WorkOrder o = new WorkOrder();
            o.setOrderNo("TST-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 18));
            o.setTitle("outbox 写路径测试");
            o.setContent("x");
            o.setType(TYPE);
            o.setPriority(PRIORITY);
            o.setStatus("PENDING");
            o.setSubmitterId(1L);
            o.setRejectCount(0);
            o.setMaxReject(3);
            o.setVersion(0);
            o.setCreatedAt(LocalDateTime.now());
            o.setUpdatedAt(LocalDateTime.now());
            workOrderMapper.insert(o);
            orderId = o.getId();
            return null;
        });
    }

    @AfterEach
    void cleanUp() {
        eventOutboxMapper.delete(new LambdaQueryWrapper<EventOutbox>().gt(EventOutbox::getId, outboxWatermark));
        workOrderMapper.delete(new LambdaQueryWrapper<WorkOrder>().gt(WorkOrder::getId, orderWatermark));
        userMapper.delete(new LambdaQueryWrapper<User>().gt(User::getId, userWatermark));
    }

    @Test
    @DisplayName("【核心】业务事务回滚 → outbox 无记录（这就是取代 afterCommit 的意义）")
    void rollback_leavesNoOutboxRow() {
        // 造"业务失败"的方式：在同一个事务里调用 acceptOrder 后显式 setRollbackOnly()。
        // 选它而不是抛异常的理由：语义最干净——事务已开始、业务已执行、随后整体回滚，
        // 与真实场景中"接单成功但同事务的后续步骤失败"完全一致；且不依赖任何业务分支恰好抛错。
        transactionTemplate.execute(status -> {
            workOrderService.acceptOrder(orderId, handlerId);
            status.setRollbackOnly();
            return null;
        });

        assertEquals(0, countOutboxForOrder(),
                "业务事务回滚后 outbox 仍有记录——写路径没有跟随事务（outbox 的意义被破坏）");
        WorkOrder after = workOrderMapper.selectById(orderId);
        assertEquals("PENDING", after.getStatus(), "业务回滚后工单状态应仍是 PENDING");
    }

    @Test
    @DisplayName("业务提交 → 恰有一条 PENDING，event_id 与 deliver_at 符合约定")
    void commit_writesExactlyOnePendingRow() {
        workOrderService.acceptOrder(orderId, handlerId);

        assertEquals(1, countOutboxForOrder(), "提交后应恰有一条 outbox 记录");
        EventOutbox row = onlyOutboxForOrder();
        assertEquals("PENDING", row.getStatus());
        assertEquals("order:" + orderId + ":v1:ORDER_RELEASE_CHECK", row.getEventId(),
                "event_id 必须带 version（这里是接单后 version=1）");
        assertEquals("ORDER_RELEASE_CHECK", row.getEventType());
        assertTrue(row.getPayload().contains(String.valueOf(orderId)), "瘦消息 payload 应含 orderId");

        SlaConfig cfg = slaConfigMapper.selectOne(new LambdaQueryWrapper<SlaConfig>()
                .eq(SlaConfig::getType, TYPE).eq(SlaConfig::getPriority, PRIORITY));
        assertNotNull(cfg, "测试库应已迁移到新类型集合（NETWORK/0 存在）");
        long minutes = java.time.Duration.between(row.getOccurredAt(), row.getDeliverAt()).toMinutes();
        assertEquals(cfg.getAcceptMinutes(), (int) minutes,
                "deliver_at 必须等于 occurred_at + 该工单的 accept_minutes（而不是硬编码 30）");
    }

    @Test
    @DisplayName("同一 event_id 重复 publish → 仍只有一条记录（UNIQUE + 幂等跳过）")
    void duplicateEventId_writesOnlyOneRow() {
        LocalDateTime now = LocalDateTime.now();
        OrderEvent e = OrderEvent.orderReleaseCheck(orderId, 1, now, now.plusMinutes(10));
        // 直接经 outbox 写两遍（模拟投递侧重放或调用方重试）
        com.workorder.service.MessagePublisher publisher = publisher();
        publisher.publish(e);
        publisher.publish(e);

        assertEquals(1, countOutboxForOrder(), "重复 event_id 被 UNIQUE 拦住并跳过，应只有一条");
    }

    @Test
    @DisplayName("同一工单不同 version → 两条不同记录（同一工单的两次合法接单不是同一条消息）")
    void differentVersions_writeTwoDifferentRows() {
        LocalDateTime now = LocalDateTime.now();
        com.workorder.service.MessagePublisher publisher = publisher();
        publisher.publish(OrderEvent.orderReleaseCheck(orderId, 1, now, now.plusMinutes(10)));
        publisher.publish(OrderEvent.orderReleaseCheck(orderId, 5, now.plusMinutes(40), now.plusMinutes(50)));

        assertEquals(2, countOutboxForOrder(), "两次合法事件必须留下两条记录，否则第二次会被当重复吞掉");
    }

    @Test
    @DisplayName("assignOrder 同样走 outbox（管理员指派也是接单成功的一种）")
    void assignOrder_writesOutboxRow() {
        workOrderService.assignOrder(orderId, handlerId, 1L);
        assertEquals(1, countOutboxForOrder(), "指派同样应写 outbox");
        assertEquals("order:" + orderId + ":v1:ORDER_RELEASE_CHECK", onlyOutboxForOrder().getEventId());
    }

    // ────────────── helpers ──────────────

    private com.workorder.service.MessagePublisher publisher;

    private com.workorder.service.MessagePublisher publisher() {
        if (publisher == null) {
            publisher = new com.workorder.service.impl.OutboxMessagePublisher(eventOutboxMapper);
        }
        return publisher;
    }

    private long countOutboxForOrder() {
        return eventOutboxMapper.selectCount(
                new LambdaQueryWrapper<EventOutbox>().eq(EventOutbox::getAggregateId, orderId));
    }

    private EventOutbox onlyOutboxForOrder() {
        return eventOutboxMapper.selectOne(
                new LambdaQueryWrapper<EventOutbox>().eq(EventOutbox::getAggregateId, orderId));
    }

    private Long maxOrderId() {
        WorkOrder o = workOrderMapper.selectOne(new LambdaQueryWrapper<WorkOrder>()
                .select(WorkOrder::getId).orderByDesc(WorkOrder::getId).last("LIMIT 1"));
        return o == null || o.getId() == null ? 0L : o.getId();
    }

    private Long maxOutboxId() {
        EventOutbox o = eventOutboxMapper.selectOne(new LambdaQueryWrapper<EventOutbox>()
                .select(EventOutbox::getId).orderByDesc(EventOutbox::getId).last("LIMIT 1"));
        return o == null || o.getId() == null ? 0L : o.getId();
    }

    private Long maxUserId() {
        User u = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .select(User::getId).orderByDesc(User::getId).last("LIMIT 1"));
        return u == null || u.getId() == null ? 0L : u.getId();
    }
}
