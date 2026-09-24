package com.workorder.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.workorder.entity.ConsumeRecord;
import com.workorder.entity.MessageRetry;
import com.workorder.entity.WorkOrder;
import com.workorder.mapper.ConsumeRecordMapper;
import com.workorder.mapper.MessageRetryMapper;
import com.workorder.mapper.WorkOrderMapper;
import com.workorder.service.impl.ConsumeRecordService;
import com.workorder.service.impl.MessageRetryService;
import com.workorder.service.impl.ReleaseCheckConsumeService;
import com.workorder.service.impl.ReleaseCheckConsumeService.Outcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

/**
 * P4 步骤 2 的核心验证：**失败落重试账本，且两张表的事务方向相反**。
 *
 * <p>本步最容易做错的地方是"顺手把两张表放进同一个事务"。下面的对照组把两个方向同时钉住：
 * <table border="1">
 *   <caption>两条必须同时成立的断言</caption>
 *   <tr><th>场景</th><th>t_consume_record</th><th>t_message_retry</th></tr>
 *   <tr><td>业务失败</td><td><b>0 行</b>（跟着回滚，否则重试被永久跳过）</td>
 *       <td><b>1 行 PENDING</b>（在业务事务之外提交，否则失败了没人记得要重试）</td></tr>
 *   <tr><td>业务成功 / SKIPPED</td><td>1 行</td><td>0 行（SKIPPED 不是失败，不写账本）</td></tr>
 * </table>
 * 阶梯也用真库验一遍：1m → 5m → 15m → 1h → 6h，第 6 次失败 → PARKED + ERROR 日志。
 */
@SpringBootTest
@ActiveProfiles("test")
class MessageRetryFlowTest {

    /** 只让"业务失败"可控：默认走真实实现 */
    @SpyBean
    private WorkOrderService workOrderService;

    @Autowired private ReleaseCheckConsumeService releaseCheckConsumeService;
    @Autowired private ConsumeRecordMapper consumeRecordMapper;
    @Autowired private MessageRetryMapper messageRetryMapper;
    @Autowired private WorkOrderMapper workOrderMapper;
    @Autowired private TransactionTemplate transactionTemplate;

    private Long orderWatermark;
    private Long consumeWatermark;
    private Long retryWatermark;

    /** 阶梯（写死在测试里，避免"和实现一起改"）：1m → 5m → 15m → 1h → 6h */
    private static final long[] LADDER_SECONDS = {60, 300, 900, 3600, 21600};

    @BeforeEach
    void setUp() {
        orderWatermark = maxOrderId();
        consumeWatermark = maxConsumeId();
        retryWatermark = maxRetryId();
    }

    @AfterEach
    void cleanUp() {
        messageRetryMapper.delete(new LambdaQueryWrapper<MessageRetry>().gt(MessageRetry::getId, retryWatermark));
        consumeRecordMapper.delete(new LambdaQueryWrapper<ConsumeRecord>().gt(ConsumeRecord::getId, consumeWatermark));
        workOrderMapper.delete(new LambdaQueryWrapper<WorkOrder>().gt(WorkOrder::getId, orderWatermark));
    }

    @Test
    @DisplayName("【灵魂】业务失败 → 去重记录 0 行 **且** 重试记录 1 行（两个断言必须同时成立）")
    void businessFailure_consumeRolledBack_retryWritten() {
        Long orderId = insertOrder("ACCEPTED");
        String eventId = eventId(orderId);
        doThrow(new RuntimeException("模拟业务失败")).when(workOrderService).releaseOrder(anyLong());

        Outcome outcome = releaseCheckConsumeService.consume(eventId, orderId, "{\"orderId\":" + orderId + "}");

        assertEquals(Outcome.RETRY_SCHEDULED, outcome);
        assertEquals(0, countConsume(eventId), "去重记录必须跟着业务回滚（否则重试被永久跳过）");
        MessageRetry retry = findRetry(eventId);
        assertNotNull(retry, "重试记录必须在业务事务之外落库（否则失败了没人记得要重试）");
        assertEquals("PENDING", retry.getStatus());
        assertEquals(1, retry.getAttempt().intValue());
        assertTrue(retry.getLastError().contains("模拟业务失败"), "last_error 必须留下失败原因，便于排障");
        assertDelayAbout(retry.getNextRetryAt(), LADDER_SECONDS[0], "首次失败应按阶梯 1m 后重投");
        assertEquals("ACCEPTED", workOrderMapper.selectById(orderId).getStatus(), "业务本身回滚");
    }

    @Test
    @DisplayName("业务成功 → 去重 1 行、重试 0 行（没失败就不该有账本）")
    void businessSuccess_noRetryRow() {
        Long orderId = insertOrder("ACCEPTED");
        String eventId = eventId(orderId);

        Outcome outcome = releaseCheckConsumeService.consume(eventId, orderId, "{\"orderId\":" + orderId + "}");

        assertEquals(Outcome.RELEASED, outcome);
        assertEquals(1, countConsume(eventId));
        assertNull(findRetry(eventId));
        assertEquals("RELEASED", workOrderMapper.selectById(orderId).getStatus());
    }

    @Test
    @DisplayName("SKIPPED 不是失败：去重 1 行、重试 0 行、attempt 不增（危险区自查项）")
    void skipped_isNotFailure() {
        Long orderId = insertOrder("IN_PROGRESS");   // 状态守卫必然未命中
        String eventId = eventId(orderId);

        Outcome outcome = releaseCheckConsumeService.consume(eventId, orderId, "{\"orderId\":" + orderId + "}");

        assertEquals(Outcome.SKIPPED, outcome);
        assertEquals(1, countConsume(eventId), "跳过也算'这条事件消费过'（否则会被反复投递）");
        assertNull(findRetry(eventId), "SKIPPED 绝不能写重试账本、不能计尝试次数");
        assertEquals("IN_PROGRESS", workOrderMapper.selectById(orderId).getStatus());
    }

    @Test
    @DisplayName("三态 ERROR（工单不存在）也算失败：去重记录同样回滚，只留重试账本")
    void errorResult_consumeRolledBack_retryWritten() {
        Long missingOrderId = 999_999_999L;
        String eventId = eventId(missingOrderId);

        Outcome outcome = releaseCheckConsumeService.consume(eventId, missingOrderId, "{\"orderId\":" + missingOrderId + "}");

        assertEquals(Outcome.RETRY_SCHEDULED, outcome);
        assertEquals(0, countConsume(eventId),
                "ERROR 也要把去重记录回滚：否则重投时命中 UNIQUE 被判'已消费'，重试空转、业务永不执行");
        assertNotNull(findRetry(eventId));
    }

    @Test
    @DisplayName("阶梯 1m/5m/15m/1h/6h：连续失败按阶梯推 next_retry_at；第 6 次 → PARKED + ERROR 日志")
    void ladderAndParking() {
        Long orderId = insertOrder("ACCEPTED");
        String eventId = eventId(orderId);
        doThrow(new RuntimeException("反复失败")).when(workOrderService).releaseOrder(anyLong());

        for (int i = 1; i <= 5; i++) {
            assertEquals(Outcome.RETRY_SCHEDULED,
                    releaseCheckConsumeService.consume(eventId, orderId, "{\"orderId\":" + orderId + "}"));
            MessageRetry row = findRetry(eventId);
            assertEquals(i, row.getAttempt().intValue(), "第 " + i + " 次失败：attempt 应为 " + i);
            assertEquals("PENDING", row.getStatus());
            assertDelayAbout(row.getNextRetryAt(), LADDER_SECONDS[i - 1], "第 " + i + " 次失败应按阶梯 " + LADDER_SECONDS[i - 1] + "s");
        }

        // 第 6 次失败：超过阶梯上限 → PARKED（不再自动重投），并必须留 ERROR 日志
        List<ILoggingEvent> logs = captureRetryLogs(() ->
                releaseCheckConsumeService.consume(eventId, orderId, "{\"orderId\":" + orderId + "}"));

        MessageRetry parked = findRetry(eventId);
        assertEquals("PARKED", parked.getStatus());
        assertNull(parked.getNextRetryAt(), "PARKED 必须把 next_retry_at 置空（否则会被误当成'到期可重投'）");
        assertEquals(6, parked.getAttempt().intValue());
        assertTrue(logs.stream().anyMatch(e -> e.getLevel() == Level.ERROR && e.getFormattedMessage().contains("PARKED")),
                "超上限停车必须留 ERROR 日志（人工介入入口）");
    }

    @Test
    @DisplayName("重投成功 → 账本转 SUCCEEDED，且业务真的执行了（去重记录此时才落库）")
    void retrySuccess_closesLedger() {
        Long orderId = insertOrder("ACCEPTED");
        String eventId = eventId(orderId);
        doThrow(new RuntimeException("第一次失败")).when(workOrderService).releaseOrder(anyLong());
        assertEquals(Outcome.RETRY_SCHEDULED,
                releaseCheckConsumeService.consume(eventId, orderId, "{\"orderId\":" + orderId + "}"));

        Mockito.reset(workOrderService);   // 模拟故障恢复后重投
        Outcome outcome = releaseCheckConsumeService.consume(eventId, orderId, "{\"orderId\":" + orderId + "}");

        assertEquals(Outcome.RELEASED, outcome, "重投必须真正执行业务（去重记录已随上次失败回滚）");
        assertEquals("RELEASED", workOrderMapper.selectById(orderId).getStatus());
        assertEquals("SUCCEEDED", findRetry(eventId).getStatus(), "成功后账本要关掉");
    }

    // ────────────── helpers ──────────────

    private String eventId(Long orderId) {
        return "order:" + orderId + ":v1:ORDER_RELEASE_CHECK";
    }

    private void assertDelayAbout(LocalDateTime nextRetryAt, long expectedSeconds, String message) {
        assertNotNull(nextRetryAt, message);
        long actual = Duration.between(LocalDateTime.now(), nextRetryAt).getSeconds();
        assertTrue(Math.abs(actual - expectedSeconds) <= 15,
                message + "（期望 ≈" + expectedSeconds + "s，实际 " + actual + "s）");
    }

    private Long insertOrder(String status) {
        WorkOrder[] holder = new WorkOrder[1];
        transactionTemplate.execute(tx -> {
            WorkOrder o = new WorkOrder();
            o.setOrderNo("TST-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 18));
            o.setTitle("重试账本测试");
            o.setContent("x");
            o.setType("NETWORK");
            o.setPriority(0);
            o.setStatus(status);
            o.setSubmitterId(1L);
            o.setAssigneeId(127L);
            o.setRejectCount(0);
            o.setMaxReject(3);
            o.setVersion(1);
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

    private List<ILoggingEvent> captureRetryLogs(ThrowingRunnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(MessageRetryService.class);
        Level previous = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        try {
            action.run();
            return List.copyOf(appender.list);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            logger.setLevel(previous);
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
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
}
