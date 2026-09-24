package com.workorder.scheduler;

import com.workorder.config.RabbitOutboxConfig;
import com.workorder.entity.MessageRetry;
import com.workorder.mapper.MessageRetryMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 重投任务的单测（不起 broker）：只验三条最容易做错的边界。
 *
 * <ol>
 *   <li><b>重投的消息必须带原 {@code x-event-id}</b>——丢了它消费端去重失效，业务会被重复执行；</li>
 *   <li>{@code x-delay} 必须是 0（延迟语义已由 {@code next_retry_at} 决定，重投＝现在投）；</li>
 *   <li>抢占必须是"逐行 CAS"：没抢到（影响 0 行）的行**不许投**，否则多实例会重复投递。</li>
 * </ol>
 */
class MessageRetryDispatchTaskTest {

    private MessageRetryMapper mapper;
    private RabbitTemplate rabbitTemplate;

    @BeforeEach
    void setUp() {
        mapper = mock(MessageRetryMapper.class);
        rabbitTemplate = mock(RabbitTemplate.class);
    }

    @Test
    @DisplayName("开关关闭：不碰库、不碰 broker")
    void disabled_touchesNothing() {
        task(false).dispatch();

        verifyNoInteractions(mapper, rabbitTemplate);
    }

    @Test
    @DisplayName("重投的消息：带原 x-event-id、x-delay=0、原 payload，且走延迟交换机的路由键")
    void republishedMessage_keepsEventIdAndZeroDelay() {
        String eventId = "order:42:v1:ORDER_RELEASE_CHECK";
        when(mapper.selectPendingIds(anyInt())).thenReturn(List.of(7L));
        when(mapper.claimById(eq(7L), anyInt())).thenReturn(1);
        when(mapper.selectById(7L)).thenReturn(row(7L, eventId, "{\"orderId\":42}", 2));
        stubPublishAck();

        task(true).dispatch();

        ArgumentCaptor<Message> captured = ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate).convertAndSend(eq(RabbitOutboxConfig.DELAY_EXCHANGE),
                eq(RabbitOutboxConfig.RELEASE_ROUTING_KEY), captured.capture(), any(CorrelationData.class));
        Message sent = captured.getValue();
        assertEquals(eventId, sent.getMessageProperties().getHeader(RabbitOutboxConfig.HEADER_EVENT_ID),
                "重投必须带原事件键，否则消费端去重失效、业务会被重复执行");
        Object delayHeader = sent.getMessageProperties().getHeader(RabbitOutboxConfig.HEADER_DELAY);
        assertNotNull(delayHeader, "必须显式带 x-delay（0 = 立即投）");
        assertEquals(0L, ((Number) delayHeader).longValue(), "x-delay 必须为 0：延迟已由 next_retry_at 决定");
        assertEquals("{\"orderId\":42}", new String(sent.getBody(), java.nio.charset.StandardCharsets.UTF_8),
                "payload 必须原样投出");
    }

    @Test
    @DisplayName("抢占失败（影响 0 行）的行不许投：多实例下只有抢到的人投")
    void claimFailed_notPublished() {
        when(mapper.selectPendingIds(anyInt())).thenReturn(List.of(7L));
        when(mapper.claimById(eq(7L), anyInt())).thenReturn(0);

        task(true).dispatch();

        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        verify(mapper, never()).selectById(anyLong());
    }

    @Test
    @DisplayName("投递失败（broker 不可用）→ 不改状态、不计 attempt，等租约到期自动再试")
    void publishFailure_leavesStateUntouched() {
        when(mapper.selectPendingIds(anyInt())).thenReturn(List.of(7L));
        when(mapper.claimById(eq(7L), anyInt())).thenReturn(1);
        when(mapper.selectById(7L)).thenReturn(row(7L, "order:42:v1:ORDER_RELEASE_CHECK", "{\"orderId\":42}", 1));
        doThrow(new RuntimeException("broker 挂了")).when(rabbitTemplate)
                .convertAndSend(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        assertDoesNotThrow(() -> task(true).dispatch());

        // 任务在投递失败时**不做任何状态变更**（attempt 只能由业务失败推进）
        verify(mapper, never()).markPendingAgain(anyLong(), anyInt(), any(), anyString());
        verify(mapper, never()).markParked(anyLong(), anyInt(), anyString());
        verify(mapper, never()).markSucceeded(anyString(), anyString());
    }

    @Test
    @DisplayName("批量上限传给取数（与 idx_retry_dispatch 一一对应的那个查询）")
    void batchSizePassedToQuery() {
        when(mapper.selectPendingIds(anyInt())).thenReturn(List.of());

        task(true).dispatch();

        verify(mapper).selectPendingIds(50);
    }

    // ────────────── helpers ──────────────

    private MessageRetryDispatchTask task(boolean enabled) {
        return new MessageRetryDispatchTask(mapper, rabbitTemplate, enabled, 50, 300, 5000);
    }

    private void stubPublishAck() {
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(3);
            correlationData.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }

    private MessageRetry row(Long id, String eventId, String payload, int attempt) {
        MessageRetry row = new MessageRetry();
        row.setId(id);
        row.setEventId(eventId);
        row.setConsumer("order-release-listener");
        row.setPayload(payload);
        row.setAttempt(attempt);
        row.setStatus("PENDING");
        row.setNextRetryAt(LocalDateTime.now().minusSeconds(5));
        return row;
    }
}
