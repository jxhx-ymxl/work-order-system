package com.workorder.listener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.workorder.config.RabbitOutboxConfig;
import com.workorder.service.impl.OrderTriageConsumeService;
import com.workorder.service.impl.OrderTriageConsumeService.Outcome;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 分诊事件的消费者（P5 步骤 1）：从 {@code workorder.order.triage.queue} 取消息，手动 ACK。
 *
 * <p>结构与 {@link OrderReleaseListener} 完全一致（同一套 ACK 契约、同一个开关），差别只在业务：
 * 这里做的是"异步分诊"而不是"超时释放"。ACK 映射：
 * <ul>
 *   <li>{@code APPLIED} → INFO + ACK；{@code SKIPPED}/{@code DUPLICATE} → DEBUG + ACK（正常结论）；</li>
 *   <li>{@code RETRY_SCHEDULED} → WARN + ACK（失败已落重试账本，阶梯重投；P4 决策：无 DLX 时 NACK 等于静默丢消息）；</li>
 *   <li>{@code NOT_RETRYABLE} → ERROR + ACK（缺 x-event-id，无法落账，必须留痕）。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "workorder.outbox.dispatch.enabled", havingValue = "true")
public class OrderTriageListener {

    private final OrderTriageConsumeService orderTriageConsumeService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @RabbitListener(queues = RabbitOutboxConfig.TRIAGE_QUEUE,
            concurrency = "${workorder.outbox.listener.concurrency:1}")
    public void onOrderTriage(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        Object rawEventId = message.getMessageProperties().getHeaders().get(RabbitOutboxConfig.HEADER_EVENT_ID);
        String eventId = rawEventId == null ? null : rawEventId.toString();
        String payload = new String(message.getBody(), StandardCharsets.UTF_8);

        Long orderId = parseOrderId(payload);
        if (orderId == null) {
            log.error("[triage-listener] 消息体无法解析出 orderId，本轮 ACK 并留痕（P4 决策：无 DLX 时 NACK 等于静默丢消息）: "
                    + "eventId={} body={}", eventId, payload);
            channel.basicAck(deliveryTag, false);
            return;
        }

        try {
            Outcome outcome = orderTriageConsumeService.consume(eventId, orderId, payload);
            switch (outcome) {
                case APPLIED -> {
                    log.info("[triage-listener] 分诊写回成功: orderId={}, eventId={}", orderId, eventId);
                    channel.basicAck(deliveryTag, false);
                }
                case SKIPPED -> {
                    log.debug("[triage-listener] 跳过（状态守卫未命中：已定稿或已被人工修改）: orderId={}, eventId={}", orderId, eventId);
                    channel.basicAck(deliveryTag, false);
                }
                case DUPLICATE -> {
                    log.debug("[triage-listener] 重复投递，已消费过，直接 ACK: orderId={}, eventId={}", orderId, eventId);
                    channel.basicAck(deliveryTag, false);
                }
                case RETRY_SCHEDULED -> {
                    log.warn("[triage-listener] 分诊失败，已落重试账本、将按阶梯（1m/5m/15m/1h/6h）重投；"
                            + "本轮仍 ACK 并留痕: orderId={}, eventId={}", orderId, eventId);
                    channel.basicAck(deliveryTag, false);
                }
                case NOT_RETRYABLE -> {
                    log.error("[triage-listener] 分诊失败且无法落重试账本（缺 x-event-id），本轮 ACK 并留痕;"
                            + " 工单保持 triage_status=PENDING 等人工处理: orderId={}", orderId);
                    channel.basicAck(deliveryTag, false);
                }
            }
        } catch (Exception e) {
            log.error("[triage-listener] 消费异常，本轮 ACK 并留痕: orderId={}, eventId={}", orderId, eventId, e);
            channel.basicAck(deliveryTag, false);
        }
    }

    private Long parseOrderId(String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload).get("orderId");
            return node == null || !node.canConvertToLong() ? null : node.asLong();
        } catch (Exception e) {
            return null;
        }
    }
}
