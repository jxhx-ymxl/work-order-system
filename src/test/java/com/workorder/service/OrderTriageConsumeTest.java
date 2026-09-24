package com.workorder.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.workorder.common.dto.TriageResult;
import com.workorder.entity.ConsumeRecord;
import com.workorder.entity.MessageRetry;
import com.workorder.entity.Notification;
import com.workorder.entity.WorkOrder;
import com.workorder.entity.WorkOrderLog;
import com.workorder.mapper.ConsumeRecordMapper;
import com.workorder.mapper.MessageRetryMapper;
import com.workorder.mapper.NotificationMapper;
import com.workorder.mapper.WorkOrderLogMapper;
import com.workorder.mapper.WorkOrderMapper;
import com.workorder.service.impl.ConsumeRecordService;
import com.workorder.service.impl.OrderTriageConsumeService;
import com.workorder.service.impl.OrderTriageConsumeService.Outcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * P5 步骤 1 的核心验证：**异步分诊的写回、防覆盖、失败重试、H4 重算与立即告警**（真库）。
 *
 * <p>四条必须成立的事：
 * <ol>
 *   <li>写回后 type / priority / sla_deadline **三者自洽**：{@code sla_deadline = created_at + 新 finish_minutes}（H4，基准是 created_at 不是 now）；</li>
 *   <li>**字段级规则**：只写回提交时为空（payload 的 {@code missingFields}）的字段，用户手工填过的一律不覆盖；</li>
 *   <li>**状态守卫**：迟到的分诊结果不得覆盖人工修改（{@code triage_status != 'PENDING'} → SKIPPED）；</li>
 *   <li>**失败复用 P4 的重试账本**（consumer=order-triage-listener），且去重记录随之回滚。</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("test")
class OrderTriageConsumeTest {

    @MockBean private OrderTriageService orderTriageService;

    @Autowired private OrderTriageConsumeService orderTriageConsumeService;
    @Autowired private WorkOrderMapper workOrderMapper;
    @Autowired private WorkOrderLogMapper workOrderLogMapper;
    @Autowired private ConsumeRecordMapper consumeRecordMapper;
    @Autowired private MessageRetryMapper messageRetryMapper;
    @Autowired private NotificationMapper notificationMapper;
    @Autowired private TransactionTemplate transactionTemplate;

    private Long orderWatermark;
    private Long logWatermark;
    private Long consumeWatermark;
    private Long retryWatermark;
    private Long notificationWatermark;

    @BeforeEach
    void setUp() {
        orderWatermark = maxOrderId();
        logWatermark = maxLogId();
        consumeWatermark = maxConsumeId();
        retryWatermark = maxRetryId();
        notificationWatermark = maxNotificationId();
    }

    @AfterEach
    void cleanUp() {
        notificationMapper.delete(new LambdaQueryWrapper<Notification>().gt(Notification::getId, notificationWatermark));
        messageRetryMapper.delete(new LambdaQueryWrapper<MessageRetry>().gt(MessageRetry::getId, retryWatermark));
        consumeRecordMapper.delete(new LambdaQueryWrapper<ConsumeRecord>().gt(ConsumeRecord::getId, consumeWatermark));
        workOrderLogMapper.delete(new LambdaQueryWrapper<WorkOrderLog>().gt(WorkOrderLog::getId, logWatermark));
        workOrderMapper.delete(new LambdaQueryWrapper<WorkOrder>().gt(WorkOrder::getId, orderWatermark));
    }

    @Test
    @DisplayName("写回成功：type/priority 更新，sla_deadline = created_at + 新 finish_minutes（H4 基准是 created_at）")
    void applied_recomputesDeadlineFromCreatedAt() {
        // 注意：MySQL DATETIME 默认 0 位小数，纳秒会被截断——测试里先抹掉纳秒，否则等值断言会假失败
        LocalDateTime createdAt = LocalDateTime.now().minusMinutes(5).withNano(0);
        Long orderId = insertOrder(createdAt, "PENDING", "OTHER", 0, "type,priority");
        when(orderTriageService.triage(anyString(), anyString())).thenReturn(new TriageResult("NETWORK", 1));
        String eventId = "order:" + orderId + ":v0:ORDER_TRIAGE";

        Outcome outcome = orderTriageConsumeService.consume(eventId, orderId,
                "{\"orderId\":" + orderId + ",\"missingFields\":[\"type\",\"priority\"]}");

        assertEquals(Outcome.APPLIED, outcome);
        WorkOrder after = workOrderMapper.selectById(orderId);
        assertEquals("NETWORK", after.getType());
        assertEquals(1, after.getPriority().intValue());
        assertEquals("DONE", after.getTriageStatus(), "写回后收口为 DONE");
        // NETWORK/1 的 finish_minutes = 60（sql/init.sql）→ 基准必须是 created_at
        assertEquals(createdAt.plusMinutes(60), after.getSlaDeadline(),
                "H4：sla_deadline 必须等于 created_at + 新 finish_minutes（不是 now + finish_minutes）");
        assertEquals(1, countConsume(eventId), "去重记录已落库");
        assertNotNull(findLog(orderId), "必须留下 TRIAGE 修正日志（操作日志时间线可见）");
        assertNull(findRetry(eventId), "成功不写重试账本");
    }

    @Test
    @DisplayName("字段级规则：只写回提交时为空的字段——用户手工填过的 type 不被 LLM 覆盖")
    void onlyMissingFieldsAreWrittenBack() {
        Long orderId = insertOrder(LocalDateTime.now(), "PENDING", "NETWORK", 0, "priority");
        when(orderTriageService.triage(anyString(), anyString())).thenReturn(new TriageResult("DORM", 1));

        Outcome outcome = orderTriageConsumeService.consume("order:" + orderId + ":v0:ORDER_TRIAGE", orderId,
                "{\"orderId\":" + orderId + ",\"missingFields\":[\"priority\"]}");

        assertEquals(Outcome.APPLIED, outcome);
        WorkOrder after = workOrderMapper.selectById(orderId);
        assertEquals("NETWORK", after.getType(), "用户填过的 type 不得被 LLM 改写");
        assertEquals(1, after.getPriority().intValue(), "缺的 priority 由分诊补上");
    }

    @Test
    @DisplayName("状态守卫：迟到消息不覆盖人工修改（triage_status 已不是 PENDING → SKIPPED，且不调 LLM）")
    void lateMessage_doesNotOverwriteManualEdit() {
        Long orderId = insertOrder(LocalDateTime.now(), "DONE", "NETWORK", 1, "type,priority");

        Outcome outcome = orderTriageConsumeService.consume("order:" + orderId + ":v0:ORDER_TRIAGE", orderId,
                "{\"orderId\":" + orderId + "}");

        assertEquals(Outcome.SKIPPED, outcome);
        WorkOrder after = workOrderMapper.selectById(orderId);
        assertEquals("NETWORK", after.getType(), "人工改过的值必须保持不变");
        assertEquals(1, after.getPriority().intValue());
        verify(orderTriageService, never()).triage(anyString(), anyString());
    }

    @Test
    @DisplayName("重复投递同一分诊结果：第二次 DUPLICATE，LLM 只被调用一次，业务只执行一次")
    void duplicate_doesNotCallLlmTwice() {
        Long orderId = insertOrder(LocalDateTime.now(), "PENDING", "OTHER", 0, "type,priority");
        when(orderTriageService.triage(anyString(), anyString())).thenReturn(new TriageResult("UTILITY", 0));
        String eventId = "order:" + orderId + ":v0:ORDER_TRIAGE";

        assertEquals(Outcome.APPLIED, orderTriageConsumeService.consume(eventId, orderId, "{\"orderId\":" + orderId + "}"));
        assertEquals(Outcome.DUPLICATE, orderTriageConsumeService.consume(eventId, orderId, "{\"orderId\":" + orderId + "}"));

        verify(orderTriageService, times(1)).triage(anyString(), anyString());
        assertEquals(1, countConsume(eventId));
    }

    @Test
    @DisplayName("LLM 不可用：分诊失败 → 重试账本 1 行、去重记录 0 行、工单保持 PENDING（提交不受影响）")
    void llmUnavailable_fallsBackToRetryLedger() {
        Long orderId = insertOrder(LocalDateTime.now(), "PENDING", "OTHER", 0, "type,priority");
        when(orderTriageService.triage(anyString(), anyString())).thenThrow(new RuntimeException("LLM 超时"));
        String eventId = "order:" + orderId + ":v0:ORDER_TRIAGE";

        Outcome outcome = orderTriageConsumeService.consume(eventId, orderId, "{\"orderId\":" + orderId + "}");

        assertEquals(Outcome.RETRY_SCHEDULED, outcome);
        assertEquals(0, countConsume(eventId), "失败时去重记录必须回滚（否则重投被判已消费、空转）");
        MessageRetry retry = findRetry(eventId);
        assertNotNull(retry, "失败必须落重试账本（复用 P4 的阶梯，不另建一套）");
        assertEquals(ConsumeRecordService.CONSUMER_ORDER_TRIAGE, retry.getConsumer());
        assertEquals("PENDING", retry.getStatus());
        assertEquals(1, retry.getAttempt().intValue());
        assertTrue(retry.getLastError().contains("LLM 超时"), "last_error 要留下失败原因");
        WorkOrder after = workOrderMapper.selectById(orderId);
        assertEquals("PENDING", after.getTriageStatus(), "分诊未完成：状态保持 PENDING");
        assertEquals("OTHER", after.getType(), "兜底值保持不动");
    }

    @Test
    @DisplayName("H4 b-1：重算后已过期 → 立即告警（计为首次告警）")
    void recomputedDeadlineAlreadyPassed_alertsImmediately() {
        // created_at 在 8 小时前，新 finish_minutes=60 → 重算后的 deadline 早就过了
        Long orderId = insertOrder(LocalDateTime.now().minusHours(8).withNano(0), "PENDING", "OTHER", 0, "type,priority");
        when(orderTriageService.triage(anyString(), anyString())).thenReturn(new TriageResult("NETWORK", 1));

        Outcome outcome = orderTriageConsumeService.consume("order:" + orderId + ":v0:ORDER_TRIAGE", orderId,
                "{\"orderId\":" + orderId + "}");

        assertEquals(Outcome.APPLIED, outcome);
        WorkOrder after = workOrderMapper.selectById(orderId);
        assertTrue(after.getSlaDeadline().isBefore(LocalDateTime.now()), "前提：重算后确实已过期");
        String orderNo = after.getOrderNo();
        // 注意：单号在**标题**里（"工单 WO-... SLA 超时（分诊后立即触发）"），内容里放的是类型/优先级/截止时间——
        // 第一版断言匹配 content 得到 0 行，是测试自己写错了列（不是告警没发）
        long notifications = notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
                .gt(Notification::getId, notificationWatermark)
                .like(Notification::getTitle, orderNo));
        assertEquals(1, notifications, "H4 b-1：重算后已过期必须立即告警（写给 SYS_ADMIN 的站内信）");
    }

    // ────────────── helpers ──────────────

    private Long insertOrder(LocalDateTime createdAt, String triageStatus, String type, int priority, String missing) {
        WorkOrder[] holder = new WorkOrder[1];
        transactionTemplate.execute(tx -> {
            WorkOrder o = new WorkOrder();
            o.setOrderNo("TST-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 18));
            o.setTitle("分诊测试");
            o.setContent("x");
            o.setType(type);
            o.setPriority(priority);
            o.setStatus("PENDING");
            o.setSubmitterId(1L);
            o.setRejectCount(0);
            o.setMaxReject(3);
            o.setTriageStatus(triageStatus);
            o.setSlaDeadline(createdAt.plusMinutes(480));
            o.setVersion(0);
            o.setCreatedAt(createdAt);
            o.setUpdatedAt(createdAt);
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

    private WorkOrderLog findLog(Long orderId) {
        return workOrderLogMapper.selectOne(new LambdaQueryWrapper<WorkOrderLog>()
                .eq(WorkOrderLog::getOrderId, orderId).eq(WorkOrderLog::getAction, "TRIAGE").last("LIMIT 1"));
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
