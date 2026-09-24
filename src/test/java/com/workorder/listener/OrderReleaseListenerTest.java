package com.workorder.listener;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.rabbitmq.client.Channel;
import com.workorder.config.RabbitOutboxConfig;
import com.workorder.service.impl.ReleaseCheckConsumeService;
import com.workorder.service.impl.ReleaseCheckConsumeService.Outcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 消费者 ACK/NACK 契约的单测（不起 broker）。
 *
 * <p>验"消费结果 → ACK 判定"这一条决策，以及四条必须成立的边界：
 * ① 任何路径都不许让异常逃出监听方法；② 本轮一次 {@code basicNack} 都不许出现（没有 DLX）；
 * ③ 缺 {@code x-event-id} 时传给消费编排的是 {@code null}，不是字符串 {@code "null"}；
 * ④ 失败（RETRY_SCHEDULED）与"无法重试"（NOT_RETRYABLE）必须都留痕——**ACK 掉但不留痕等于静默丢弃**。
 */
class OrderReleaseListenerTest {

    private static final long DELIVERY_TAG = 7L;
    private static final String EVENT_ID = "order:42:v1:ORDER_RELEASE_CHECK";
    private static final String PAYLOAD = "{\"orderId\":42}";

    private ReleaseCheckConsumeService releaseCheckConsumeService;
    private Channel channel;
    private OrderReleaseListener listener;

    @BeforeEach
    void setUp() {
        releaseCheckConsumeService = mock(ReleaseCheckConsumeService.class);
        channel = mock(Channel.class);
        listener = new OrderReleaseListener(releaseCheckConsumeService);
    }

    @Test
    @DisplayName("RELEASED → ACK（记 INFO）")
    void released_acks() throws Exception {
        givenOutcome(Outcome.RELEASED);

        listener.onReleaseCheck(message(PAYLOAD), channel);

        verify(releaseCheckConsumeService).consume(EVENT_ID, 42L, PAYLOAD);
        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("SKIPPED → 也 ACK（状态守卫未命中是正常结论，不是失败）")
    void skipped_acks() throws Exception {
        givenOutcome(Outcome.SKIPPED);

        listener.onReleaseCheck(message(PAYLOAD), channel);

        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("DUPLICATE（去重命中，含阶梯重投的第二次）→ 不执行业务、ACK、记 DEBUG")
    void duplicate_acks() throws Exception {
        givenOutcome(Outcome.DUPLICATE);

        listener.onReleaseCheck(message(PAYLOAD), channel);

        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("RETRY_SCHEDULED（失败已落重试账本）→ 本轮也 ACK，且必须 WARN 留痕并写明 P4 步骤 3 改 NACK")
    void retryScheduled_acksWithWarning() throws Exception {
        givenOutcome(Outcome.RETRY_SCHEDULED);

        List<ILoggingEvent> logs = captureLogs(() -> listener.onReleaseCheck(message(PAYLOAD), channel));

        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
        assertTrue(logs.stream().anyMatch(e -> e.getLevel() == Level.WARN
                        && e.getFormattedMessage().contains("P4 步骤 3 接入死信后改为 NACK")),
                "失败重投路径必须留 WARN 日志并写明后续会改 NACK");
    }

    @Test
    @DisplayName("NOT_RETRYABLE（缺事件键，无法落账）→ ACK 但必须 ERROR 留痕")
    void notRetryable_acksWithErrorLog() throws Exception {
        givenOutcome(Outcome.NOT_RETRYABLE);

        List<ILoggingEvent> logs = captureLogs(() -> listener.onReleaseCheck(message(PAYLOAD), channel));

        verify(channel).basicAck(DELIVERY_TAG, false);
        assertTrue(logs.stream().anyMatch(e -> e.getLevel() == Level.ERROR),
                "无法重试的失败必须留 ERROR（否则等于静默丢弃）");
    }

    @Test
    @DisplayName("消费编排抛异常（兜底路径）→ 不逃出监听方法，ACCEPT ACK 并留痕")
    void serviceThrows_acksAndSwallows() throws Exception {
        when(releaseCheckConsumeService.consume(any(), anyLong(), anyString()))
                .thenThrow(new RuntimeException("DB 连接断了"));

        listener.onReleaseCheck(message(PAYLOAD), channel);

        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("payload 缺 orderId → 不调编排、ACK + ERROR 留痕")
    void missingOrderId_acksWithoutCallingService() throws Exception {
        listener.onReleaseCheck(message("{\"somethingElse\":1}"), channel);

        verifyNoInteractions(releaseCheckConsumeService);
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    @Test
    @DisplayName("payload 不是合法 JSON → 不调编排、ACK + ERROR 留痕")
    void brokenPayload_acksWithoutCallingService() throws Exception {
        listener.onReleaseCheck(message("not-a-json"), channel);

        verifyNoInteractions(releaseCheckConsumeService);
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    @Test
    @DisplayName("缺 x-event-id：必须把 null 传给编排（不能变成字符串 \"null\"）")
    void missingEventIdHeader_passesNull() throws Exception {
        givenOutcome(Outcome.RELEASED);
        MessageProperties props = new MessageProperties();
        props.setDeliveryTag(DELIVERY_TAG);
        Message withoutHeader = new Message(PAYLOAD.getBytes(StandardCharsets.UTF_8), props);

        listener.onReleaseCheck(withoutHeader, channel);

        verify(releaseCheckConsumeService).consume(null, 42L, PAYLOAD);
        verify(releaseCheckConsumeService, never()).consume(eq("null"), anyLong(), anyString());
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    @Test
    @DisplayName("【留痕】SKIPPED 与 DUPLICATE 都是 DEBUG（正常结论），不是 ERROR")
    void skippedAndDuplicateAreDebug() throws Exception {
        givenOutcome(Outcome.SKIPPED);
        List<ILoggingEvent> skipped = captureLogs(() -> listener.onReleaseCheck(message(PAYLOAD), channel));
        assertTrue(skipped.stream().anyMatch(e -> e.getLevel() == Level.DEBUG
                && e.getFormattedMessage().contains("跳过")), "SKIPPED 应是 DEBUG");
        assertTrue(skipped.stream().noneMatch(e -> e.getLevel() == Level.ERROR), "SKIPPED 不该有 ERROR 日志");

        reset(releaseCheckConsumeService);
        givenOutcome(Outcome.DUPLICATE);
        List<ILoggingEvent> duplicated = captureLogs(() -> listener.onReleaseCheck(message(PAYLOAD), channel));
        assertTrue(duplicated.stream().anyMatch(e -> e.getLevel() == Level.DEBUG
                && e.getFormattedMessage().contains("重复投递")), "重复投递应是 DEBUG");
        assertTrue(duplicated.stream().noneMatch(e -> e.getLevel() == Level.ERROR), "重复投递不该有 ERROR 日志");
    }

    // ────────────── helpers ──────────────

    private void givenOutcome(Outcome outcome) {
        when(releaseCheckConsumeService.consume(any(), anyLong(), anyString())).thenReturn(outcome);
    }

    private List<ILoggingEvent> captureLogs(ThrowingRunnable action) throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(OrderReleaseListener.class);
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        try {
            action.run();
            return List.copyOf(appender.list);
        } finally {
            logger.setLevel(previousLevel);
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private Message message(String body) {
        MessageProperties props = new MessageProperties();
        props.setDeliveryTag(DELIVERY_TAG);
        props.setHeader(RabbitOutboxConfig.HEADER_EVENT_ID, EVENT_ID);
        return new Message(body.getBytes(StandardCharsets.UTF_8), props);
    }
}
