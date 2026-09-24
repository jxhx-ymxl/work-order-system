package com.workorder.scheduler;

import com.workorder.config.RabbitOutboxConfig;
import com.workorder.entity.EventOutbox;
import com.workorder.mapper.EventOutboxMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 投递任务的判定逻辑单测（不连 broker、不连库）。
 *
 * <p><b>为什么不写成 Spring 集成测试</b>：本类要验的是"分支判定"——ack/nack/超时/连接故障分别
 * 落到哪个状态、x-delay 怎么算。这些判定与 broker 无关；用 Mockito 造出四种 confirm 结果，
 * 比拉起一个 broker 更快也更确定（也满足"本地不启 broker 时 mvn test 必须全绿"）。
 *
 * <p><b>SQL 语义（抢占/回收/回写守卫）不在这里验</b>——那是数据库行为，用
 * {@code EventOutboxClaimReclaimTest} 在独立测试库上验，两边分工不重叠。
 */
class OutboxDispatchTaskTest {

    private EventOutboxMapper mapper;
    private RabbitTemplate rabbitTemplate;

    @BeforeEach
    void setUp() {
        mapper = mock(EventOutboxMapper.class);
        rabbitTemplate = mock(RabbitTemplate.class);
    }

    @Test
    @DisplayName("开关关闭：不碰库、不碰 broker、不改状态（本地与 CI 的默认态）")
    void disabled_touchesNothing() {
        task(false, 5_000L, 15_000L, 30L, 20).dispatch();

        verifyNoInteractions(mapper, rabbitTemplate);
    }

    @Test
    @DisplayName("只有 publisher-confirm 返回 ack 才标 SENT")
    void ack_marksSent() {
        givenClaimed(row(9L, "order:9:v1:ORDER_RELEASE_CHECK", LocalDateTime.now()));
        stubSend(new CorrelationData.Confirm(true, null));

        task(true, 5_000L, 15_000L, 30L, 20).dispatch();

        verify(mapper).markSent(9L);
        verify(mapper, never()).markAttemptFailed(anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("broker nack：不标 SENT，回写 PENDING 且 next_retry_at 推到退避之后")
    void nack_keepsPendingWithBackoff() {
        givenClaimed(row(9L, "order:9:v1:ORDER_RELEASE_CHECK", LocalDateTime.now()));
        stubSend(new CorrelationData.Confirm(false, "broker 拒绝"));

        task(true, 5_000L, 15_000L, 30L, 20).dispatch();

        verify(mapper, never()).markSent(anyLong());
        ArgumentCaptor<LocalDateTime> nextRetryAt = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(mapper).markAttemptFailed(eq(9L), eq("PENDING"), nextRetryAt.capture());
        assertTrue(nextRetryAt.getValue().isAfter(LocalDateTime.now().plusSeconds(25)),
                "nack 之后必须退避（30s），实际 next_retry_at=" + nextRetryAt.getValue());
    }

    @Test
    @DisplayName("confirm 超时：不标 SENT——send 不抛异常不等于 broker 收下了")
    void confirmTimeout_keepsPending() {
        givenClaimed(row(9L, "order:9:v1:ORDER_RELEASE_CHECK", LocalDateTime.now()));
        // 不完成 confirm future：等价于 broker 始终没回 ack

        task(true, 200L, 1_000L, 30L, 20).dispatch();

        verify(mapper, never()).markSent(anyLong());
        verify(mapper).markAttemptFailed(eq(9L), eq("PENDING"), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("broker 不可达：不标 SENT、本轮提前收尾，未尝试的记录退回 PENDING")
    void brokerDown_marksFailedAttemptAndAbortsBatch() {
        givenClaimed(row(9L, "order:9:v1:ORDER_RELEASE_CHECK", LocalDateTime.now()),
                row(10L, "order:10:v1:ORDER_RELEASE_CHECK", LocalDateTime.now()));
        doThrow(new AmqpConnectException(new java.net.ConnectException("Connection refused")))
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Message.class),
                        any(CorrelationData.class));

        task(true, 5_000L, 15_000L, 30L, 20).dispatch();

        verify(mapper, never()).markSent(anyLong());
        verify(mapper).markAttemptFailed(eq(9L), eq("PENDING"), any(LocalDateTime.class));
        // 第二条根本没被尝试：退回 PENDING（且不增加 retry_count，所以走 releaseClaim 而不是 markAttemptFailed）
        verify(mapper).releaseClaim(10L);
        verify(rabbitTemplate, times(1)).convertAndSend(anyString(), anyString(), any(Message.class),
                any(CorrelationData.class));
    }

    @Test
    @DisplayName("失败次数达上限：转 FAILED，不再无限重试")
    void maxAttemptsExceeded_marksFailed() {
        EventOutbox r = row(9L, "order:9:v1:ORDER_RELEASE_CHECK", LocalDateTime.now());
        r.setRetryCount(19); // 本次是第 20 次尝试
        givenClaimed(r);
        stubSend(new CorrelationData.Confirm(false, "nack"));

        task(true, 5_000L, 15_000L, 30L, 20).dispatch();

        verify(mapper).markAttemptFailed(eq(9L), eq("FAILED"), isNull());
    }

    @Test
    @DisplayName("x-delay = deliver_at 与当前时间之差；已过期或为空则按 0（立即投递）")
    void delayHeaderReflectsDeliverAt() {
        LocalDateTime deliverAt = LocalDateTime.now().plusSeconds(90);
        givenClaimed(row(9L, "order:9:v1:ORDER_RELEASE_CHECK", deliverAt));
        stubSend(new CorrelationData.Confirm(true, null));

        OutboxDispatchTask task = task(true, 5_000L, 15_000L, 30L, 20);
        task.dispatch();

        ArgumentCaptor<Message> sent = ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate).convertAndSend(eq(RabbitOutboxConfig.DELAY_EXCHANGE),
                eq(RabbitOutboxConfig.RELEASE_ROUTING_KEY), sent.capture(), any(CorrelationData.class));
        Object delay = sent.getValue().getMessageProperties().getHeader(RabbitOutboxConfig.HEADER_DELAY);
        assertInstanceOf(Long.class, delay, "x-delay 必须是整数毫秒");
        assertTrue((Long) delay > 88_000 && (Long) delay <= 90_000,
                "x-delay 应约等于 deliver_at - now，实际=" + delay);
        assertEquals("order:9:v1:ORDER_RELEASE_CHECK",
                sent.getValue().getMessageProperties().getHeader(RabbitOutboxConfig.HEADER_EVENT_ID),
                "消息头必须带事件维度幂等键，消费端才能不解析消息体就去重");

        assertEquals(0L, task.delayMillis(LocalDateTime.now().minusMinutes(5)),
                "deliver_at 已过期必须按 0 处理（立即投递），不能发成负数延迟");
        assertEquals(0L, task.delayMillis(null));
        assertEquals(OutboxDispatchTask.MAX_DELAY_MS, task.delayMillis(LocalDateTime.now().plusDays(30)),
                "超过 int32 上限的延迟必须夹住，否则延迟插件读到的是溢出值");
    }

    // ────────────── helpers ──────────────

    private OutboxDispatchTask task(boolean enabled, long confirmTimeoutMs, long budgetMs,
                                    long backoffSeconds, int maxAttempts) {
        return new OutboxDispatchTask(mapper, rabbitTemplate, enabled, 100, 5,
                confirmTimeoutMs, budgetMs, backoffSeconds, maxAttempts);
    }

    private void givenClaimed(EventOutbox... rows) {
        when(mapper.claimPending(anyString(), anyInt())).thenReturn(rows.length);
        when(mapper.selectClaimedBy(anyString())).thenReturn(List.of(rows));
    }

    /** 模拟 broker：发送时用给定结果完成 confirm。 */
    private void stubSend(CorrelationData.Confirm confirm) {
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(3);
            correlationData.getFuture().complete(confirm);
            return null;
        }).when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Message.class),
                any(CorrelationData.class));
    }

    private EventOutbox row(Long id, String eventId, LocalDateTime deliverAt) {
        EventOutbox row = new EventOutbox();
        row.setId(id);
        row.setEventId(eventId);
        row.setEventType("ORDER_RELEASE_CHECK");
        row.setAggregateId(1L);
        row.setAggregateVersion(1);
        row.setPayload("{\"orderId\":1}");
        row.setDeliverAt(deliverAt);
        row.setStatus("SENDING");
        row.setRetryCount(0);
        return row;
    }
}
