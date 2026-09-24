package com.workorder.listener;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.rabbitmq.client.Channel;
import com.workorder.common.enums.ReleaseResult;
import com.workorder.config.RabbitOutboxConfig;
import com.workorder.service.WorkOrderService;
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
 * <p>本类只验"三态 → ACK 判定"这一条决策，以及两条必须成立的边界：
 * ① **任何路径都不许让异常逃出监听方法**（逃出去的消息在 {@code default-requeue-rejected=false} 下会被直接丢弃，
 * 且没有任何业务日志）；② **本轮一次 basicNack 都不许出现**（没有 DLX，NACK 等于静默丢消息）。
 *
 * <p>真实 broker 下的行为（消息真的被消费、队列深度回到 0）在交付报告里给的是手测输出，不在单测里模拟。
 */
class OrderReleaseListenerTest {

    private static final long DELIVERY_TAG = 7L;
    private static final String EVENT_ID = "order:42:v1:ORDER_RELEASE_CHECK";

    private WorkOrderService workOrderService;
    private Channel channel;
    private OrderReleaseListener listener;

    @BeforeEach
    void setUp() {
        workOrderService = mock(WorkOrderService.class);
        channel = mock(Channel.class);
        listener = new OrderReleaseListener(workOrderService);
    }

    @Test
    @DisplayName("RELEASED → ACK（记 INFO）")
    void released_acks() throws Exception {
        when(workOrderService.releaseOrder(42L)).thenReturn(ReleaseResult.RELEASED);

        listener.onReleaseCheck(message("{\"orderId\":42}"), channel);

        verify(workOrderService).releaseOrder(42L);
        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("SKIPPED → 也 ACK（状态守卫未命中是正常结论，不是失败）")
    void skipped_acks() throws Exception {
        when(workOrderService.releaseOrder(42L)).thenReturn(ReleaseResult.SKIPPED);

        listener.onReleaseCheck(message("{\"orderId\":42}"), channel);

        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("ERROR → 本轮也 ACK（无 DLX，NACK 会静默丢消息；P4 改 NACK）")
    void error_stillAcks() throws Exception {
        when(workOrderService.releaseOrder(42L)).thenReturn(ReleaseResult.ERROR);

        listener.onReleaseCheck(message("{\"orderId\":42}"), channel);

        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("服务抛异常 → 不逃出监听方法，且必须 ACK 并留痕（不允许静默丢弃）")
    void serviceThrows_acksAndSwallows() throws Exception {
        when(workOrderService.releaseOrder(42L)).thenThrow(new RuntimeException("DB 连接断了"));

        listener.onReleaseCheck(message("{\"orderId\":42}"), channel);

        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("payload 缺 orderId → 不调服务、ACK + ERROR 留痕")
    void missingOrderId_acksWithoutCallingService() throws Exception {
        listener.onReleaseCheck(message("{\"somethingElse\":1}"), channel);

        verifyNoInteractions(workOrderService);
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    @Test
    @DisplayName("payload 不是合法 JSON → 不调服务、ACK + ERROR 留痕")
    void brokenPayload_acksWithoutCallingService() throws Exception {
        listener.onReleaseCheck(message("not-a-json"), channel);

        verifyNoInteractions(workOrderService);
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    @Test
    @DisplayName("【留痕】ERROR 分支必须打 ERROR 日志——ACK 掉但不留痕就等于静默丢弃")
    void errorBranch_logsErrorWithP4Note() throws Exception {
        when(workOrderService.releaseOrder(42L)).thenReturn(ReleaseResult.ERROR);

        List<ILoggingEvent> logs = captureLogs(() -> listener.onReleaseCheck(message("{\"orderId\":42}"), channel));

        assertTrue(logs.stream().anyMatch(e -> e.getLevel() == Level.ERROR
                        && e.getFormattedMessage().contains("P4 接入死信与退避后改为 NACK")),
                "ERROR 分支必须留 ERROR 日志且写明 P4 会改成 NACK，否则后来者会以为这里漏了 NACK");
    }

    @Test
    @DisplayName("【留痕】SKIPPED 分支打 DEBUG（正常结论，不是失败）；服务异常分支打 ERROR")
    void skippedIsDebugAndExceptionIsError() throws Exception {
        when(workOrderService.releaseOrder(42L)).thenReturn(ReleaseResult.SKIPPED);
        List<ILoggingEvent> skipped = captureLogs(() -> listener.onReleaseCheck(message("{\"orderId\":42}"), channel));
        assertTrue(skipped.stream().anyMatch(e -> e.getLevel() == Level.DEBUG
                && e.getFormattedMessage().contains("跳过")), "SKIPPED 应是 DEBUG 且说明原因");

        reset(workOrderService);
        when(workOrderService.releaseOrder(42L)).thenThrow(new RuntimeException("DB 故障"));
        List<ILoggingEvent> thrown = captureLogs(() -> listener.onReleaseCheck(message("{\"orderId\":42}"), channel));
        assertTrue(thrown.stream().anyMatch(e -> e.getLevel() == Level.ERROR), "未预期异常必须留 ERROR 日志");
    }

    /**
     * 把 listener 的 logger 挂到内存 appender 上，跑完再摘掉（不影响其他用例的日志配置）。
     *
     * <p>同时**显式把该 logger 的级别设为 DEBUG 再恢复**：本测试要断言 DEBUG 级别的那条
     * "跳过"日志，如果依赖外部日志配置（application.yml 的 `com.workorder: debug`），
     * 一旦有人为压噪音把级别调高，这条断言就会以"断言失败"的形式表现出来——
     * 那属于测试自身脆弱，而不是被测行为变了。所以这里自己控制级别。
     */
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
