package com.workorder.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.workorder.entity.ConsumeRecord;
import com.workorder.entity.MessageRetry;
import com.workorder.entity.Notification;
import com.workorder.entity.WorkOrder;
import com.workorder.mapper.ConsumeRecordMapper;
import com.workorder.mapper.MessageRetryMapper;
import com.workorder.mapper.NotificationMapper;
import com.workorder.mapper.WorkOrderMapper;
import com.workorder.service.impl.ConsumeRecordService;
import com.workorder.service.impl.OrderSubmittedConsumeService;
import com.workorder.service.impl.OrderSubmittedConsumeService.Outcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 提交通知链路的**失败方向**（P5 步骤 2）：站内信写不进去时，两张表的事务要求相反这件事必须同时成立。
 *
 * <p>本类存在的理由只有一条——**同时断言两个方向**：
 * <ul>
 *   <li>{@code t_consume_record} **0 行**：去重记录跟着业务回滚（否则重投时 INSERT 命中 UNIQUE →
 *       被判"已消费"而永久跳过 = 处理人永远收不到通知）；</li>
 *   <li>{@code t_message_retry} **1 行**：重试账本必须**在业务事务之外**落库（原因是业务失败恰恰是要重试的理由，
 *       账本跟着回滚就没得重试了）。</li>
 * </ul>
 * 用 {@code @MockBean} 让 {@code sendToRoleOnce} 抛异常来造"写站内信失败"（表锁 / 连接超时这一类的真实形态）。
 */
@SpringBootTest
@ActiveProfiles("test")
class OrderSubmittedNotifyFailureTest {

    @MockBean private NotificationService notificationService;

    @Autowired private OrderSubmittedConsumeService orderSubmittedConsumeService;
    @Autowired private WorkOrderMapper workOrderMapper;
    @Autowired private ConsumeRecordMapper consumeRecordMapper;
    @Autowired private MessageRetryMapper messageRetryMapper;
    @Autowired private NotificationMapper notificationMapper;
    @Autowired private TransactionTemplate transactionTemplate;

    private Long orderWatermark;
    private Long consumeWatermark;
    private Long retryWatermark;
    private Long notificationWatermark;

    @BeforeEach
    void setUp() {
        orderWatermark = maxOrderId();
        consumeWatermark = maxConsumeId();
        retryWatermark = maxRetryId();
        notificationWatermark = maxNotificationId();
    }

    @AfterEach
    void cleanUp() {
        notificationMapper.delete(new LambdaQueryWrapper<Notification>().gt(Notification::getId, notificationWatermark));
        messageRetryMapper.delete(new LambdaQueryWrapper<MessageRetry>().gt(MessageRetry::getId, retryWatermark));
        consumeRecordMapper.delete(new LambdaQueryWrapper<ConsumeRecord>().gt(ConsumeRecord::getId, consumeWatermark));
        workOrderMapper.delete(new LambdaQueryWrapper<WorkOrder>().gt(WorkOrder::getId, orderWatermark));
    }

    @Test
    @DisplayName("【核心】站内信写入失败 → 去重记录 0 行 且 重试账本 1 行（两张表的事务要求相反）")
    void notificationFailure_rollsBackConsumeRecord_butKeepsRetryLedger() {
        when(notificationService.sendToRoleOnce(anyString(), anyString(), anyString(),
                anyString(), anyString(), any()))
                .thenThrow(new RuntimeException("模拟：站内信写入失败（表锁 / 连接超时）"));

        Long orderId = insertOrder();
        String eventId = "order:" + orderId + ":v0:ORDER_SUBMITTED";

        Outcome outcome = orderSubmittedConsumeService.consume(eventId, orderId, "{\"orderId\":" + orderId + "}");

        assertEquals(Outcome.RETRY_SCHEDULED, outcome, "失败必须落重试账本，交给 P4 的阶梯重投");
        assertEquals(0, countConsume(eventId),
                "去重记录必须跟着业务回滚——留着它会让重投被判'已消费'，处理人永远收不到通知");
        MessageRetry retry = findRetry(eventId);
        assertNotNull(retry, "重试账本必须在业务事务之外落库（REQUIRES_NEW，见 D53）");
        assertEquals(ConsumeRecordService.CONSUMER_ORDER_SUBMITTED, retry.getConsumer());
        assertEquals("PENDING", retry.getStatus());
        assertEquals(1, retry.getAttempt().intValue());
        assertTrue(retry.getLastError().contains("站内信写入失败"), "last_error 要留下失败原因：" + retry.getLastError());
        assertEquals(0, notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
                .gt(Notification::getId, notificationWatermark)), "失败时不得留下半截站内信");
    }

    @Test
    @DisplayName("对照：站内信写入正常时，去重记录 1 行、重试账本 0 行（证明上一条的'0 行'来自失败，而不是别的原因）")
    void control_successWritesConsumeRecordOnly() {
        when(notificationService.sendToRoleOnce(anyString(), anyString(), anyString(),
                anyString(), anyString(), any())).thenReturn(0);

        Long orderId = insertOrder();
        String eventId = "order:" + orderId + ":v0:ORDER_SUBMITTED";

        Outcome outcome = orderSubmittedConsumeService.consume(eventId, orderId, "{\"orderId\":" + orderId + "}");

        assertEquals(Outcome.NOTIFIED, outcome, "inserted=0 表示没有新增接收人（角色下无用户或已被唯一键挡住），不是失败");
        assertEquals(1, countConsume(eventId));
        assertNull(findRetry(eventId), "成功不写重试账本");
    }

    // ────────────── helpers ──────────────

    private Long insertOrder() {
        WorkOrder[] holder = new WorkOrder[1];
        transactionTemplate.execute(tx -> {
            WorkOrder o = new WorkOrder();
            o.setOrderNo("TST-" + UUID.randomUUID().toString().replace("-", "").substring(0, 18));
            o.setTitle("提交通知失败测试");
            o.setContent("x");
            o.setType("NETWORK");
            o.setPriority(0);
            o.setStatus("PENDING");
            o.setSubmitterId(1L);
            o.setRejectCount(0);
            o.setMaxReject(3);
            o.setTriageStatus("DONE");
            o.setSlaDeadline(LocalDateTime.now().plusMinutes(480));
            o.setVersion(0);
            o.setCreatedAt(LocalDateTime.now());
            o.setUpdatedAt(LocalDateTime.now());
            workOrderMapper.insert(o);
            holder[0] = o;
            return null;
        });
        return holder[0].getId();
    }

    private long countConsume(String eventId) {
        return consumeRecordMapper.selectCount(new LambdaQueryWrapper<ConsumeRecord>()
                .eq(ConsumeRecord::getEventId, eventId));
    }

    private MessageRetry findRetry(String eventId) {
        return messageRetryMapper.selectOne(new LambdaQueryWrapper<MessageRetry>()
                .eq(MessageRetry::getEventId, eventId));
    }

    private Long maxOrderId() {
        WorkOrder o = workOrderMapper.selectOne(new LambdaQueryWrapper<WorkOrder>()
                .select(WorkOrder::getId).orderByDesc(WorkOrder::getId).last("LIMIT 1"));
        return o == null || o.getId() == null ? 0L : o.getId();
    }

    private Long maxConsumeId() {
        ConsumeRecord r = consumeRecordMapper.selectOne(new LambdaQueryWrapper<ConsumeRecord>()
                .select(ConsumeRecord::getId).orderByDesc(ConsumeRecord::getId).last("LIMIT 1"));
        return r == null || r.getId() == null ? 0L : r.getId();
    }

    private Long maxRetryId() {
        MessageRetry r = messageRetryMapper.selectOne(new LambdaQueryWrapper<MessageRetry>()
                .select(MessageRetry::getId).orderByDesc(MessageRetry::getId).last("LIMIT 1"));
        return r == null || r.getId() == null ? 0L : r.getId();
    }

    private Long maxNotificationId() {
        Notification n = notificationMapper.selectOne(new LambdaQueryWrapper<Notification>()
                .select(Notification::getId).orderByDesc(Notification::getId).last("LIMIT 1"));
        return n == null || n.getId() == null ? 0L : n.getId();
    }
}
