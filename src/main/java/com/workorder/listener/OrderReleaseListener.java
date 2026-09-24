package com.workorder.listener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.workorder.common.enums.ReleaseResult;
import com.workorder.config.RabbitOutboxConfig;
import com.workorder.service.WorkOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 释放检查事件的消费者（P1 步骤 4）。
 *
 * <p><b>这是本项目的第一个 MQ 消费者</b>，它建立的 ACK/NACK 契约是后续所有消费者的模板。
 *
 * <h3>一、为什么 ERROR 也 ACK（不要以为这里漏了 NACK）</h3>
 * 当前**没有死信队列**（DLX 在 P4）。此时 NACK 且 {@code default-requeue-rejected=false} 的后果是
 * **消息被直接丢弃、且没有任何痕迹**；而 "ACK + ERROR 日志" 至少留下可查的记录。更关键的是：
 * **释放的权威通道是兜底扫描 {@code ReleaseTimeoutScheduler}，MQ 只是"更早触发"的优化路径**——
 * 所以这里丢一条消息不会导致工单不被释放，只会让它晚一点由兜底扫描释放（plan §3.4 第 5 条）。
 * <b>P4 接入死信与退避后，ERROR 分支改为 NACK</b>（届时消息进 DLX 而不是消失）。
 *
 * <h3>二、幂等从哪来（当前没有去重表，别误以为有）</h3>
 * 本轮的幂等**由状态守卫天然提供**：{@code releaseOrder} 的 UPDATE 带 {@code WHERE status='ACCEPTED'}，
 * 同一条消息投两次，第二次影响 0 行 → {@link ReleaseResult#SKIPPED} → ACK，只释放一次。
 * **消费去重表 {@code t_consume_record} 是 P4 的事**，当前不要以为自己拿到了事件级幂等键就去假设"不会重复消费"。
 *
 * <h3>三、为什么不放在与投递任务同一个开关下</h3>
 * 用**同一个** {@code workorder.outbox.dispatch.enabled}（默认 false）：打开就是"MQ 这条链路整体启用"，
 * 关闭则**连监听容器都不会创建**——否则本地不启 broker 时，Spring 启动就会去连 broker，
 * 把"没有 MQ 也能启动与跑测试"这条保证破坏掉（plan §3.4 第 7 条）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "workorder.outbox.dispatch.enabled", havingValue = "true")
public class OrderReleaseListener {

    private final WorkOrderService workOrderService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 消费"到点的释放检查"事件。
     *
     * <p><b>手动 ACK</b>：{@code spring.rabbitmq.listener.simple.acknowledge-mode=manual} 已配置，
     * 因此这里必须自己调 {@code basicAck}——方法签名带 {@link Channel} 参数（两个参数都必需，缺一个就退化成自动 ACK）。
     */
    @RabbitListener(queues = RabbitOutboxConfig.RELEASE_QUEUE,
            concurrency = "${workorder.outbox.listener.concurrency:1}")
    public void onReleaseCheck(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String eventId = String.valueOf(message.getMessageProperties().getHeaders()
                .get(RabbitOutboxConfig.HEADER_EVENT_ID));

        Long orderId = parseOrderId(message);
        if (orderId == null) {
            // 消息体不可解析：属于"这条消息永远处理不了"，本轮 ACK + ERROR（P4 改 NACK 进死信）
            log.error("[release-listener] 消息体无法解析出 orderId，本轮 ACK 并留痕（P4 接入死信后改为 NACK）: "
                    + "eventId={} body={}", eventId, new String(message.getBody(), StandardCharsets.UTF_8));
            channel.basicAck(deliveryTag, false);
            return;
        }

        try {
            ReleaseResult result = workOrderService.releaseOrder(orderId);
            switch (result) {
                case RELEASED -> {
                    log.info("[release-listener] 释放成功: orderId={}, eventId={}", orderId, eventId);
                    channel.basicAck(deliveryTag, false);
                }
                case SKIPPED -> {
                    // 正常结论（状态已被 START 等改过 / 重复投递）：ACK，不算失败，所以用 DEBUG
                    log.debug("[release-listener] 跳过（状态守卫未命中，工单状态已变或已释放）: orderId={}, eventId={}",
                            orderId, eventId);
                    channel.basicAck(deliveryTag, false);
                }
                case ERROR -> {
                    // 见类注释"为什么 ERROR 也 ACK"：本轮没有 DLX，NACK 会让消息无声消失。
                    // P4 接入死信与退避后，这里改为 basicNack(tag, false, false) 让它进 DLX。
                    log.error("[release-listener] 释放内部出错，本轮 ACK 并留痕（P4 接入死信与退避后改为 NACK）: "
                            + "orderId={}, eventId={}", orderId, eventId);
                    channel.basicAck(deliveryTag, false);
                }
            }
        } catch (Exception e) {
            // DB 故障等未预期异常：同样 ACK + ERROR 日志（理由同上）。绝不能"吞掉且不记日志"。
            log.error("[release-listener] 消费异常，本轮 ACK 并留痕（P4 接入死信与退避后改为 NACK）: "
                    + "orderId={}, eventId={}", orderId, eventId, e);
            channel.basicAck(deliveryTag, false);
        }
    }

    /**
     * 从瘦消息体里取 {@code orderId}（投递侧写入的 payload 形如 {@code {"orderId":123}}）。
     *
     * @return 解析不出来时返回 {@code null}，由调用方走"ACK + ERROR 留痕"分支
     */
    private Long parseOrderId(Message message) {
        try {
            JsonNode node = objectMapper.readTree(message.getBody()).get("orderId");
            return node == null || !node.canConvertToLong() ? null : node.asLong();
        } catch (Exception e) {
            log.warn("[release-listener] payload 不是合法 JSON: {}", e.getMessage());
            return null;
        }
    }
}
