package com.workorder.listener;

import com.rabbitmq.client.Channel;
import com.workorder.common.enums.ReleaseResult;
import com.workorder.config.RabbitOutboxConfig;
import com.workorder.service.WorkOrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.nio.charset.StandardCharsets;

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

    private Message message(String body) {
        MessageProperties props = new MessageProperties();
        props.setDeliveryTag(DELIVERY_TAG);
        props.setHeader(RabbitOutboxConfig.HEADER_EVENT_ID, EVENT_ID);
        return new Message(body.getBytes(StandardCharsets.UTF_8), props);
    }
}
