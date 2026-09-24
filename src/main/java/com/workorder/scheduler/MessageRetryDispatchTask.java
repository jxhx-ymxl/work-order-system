package com.workorder.scheduler;

import com.workorder.config.RabbitOutboxConfig;
import com.workorder.entity.MessageRetry;
import com.workorder.mapper.MessageRetryMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 重投任务（P4 步骤 2）：把 {@code t_message_retry} 里到期的 PENDING 记录重新投到释放检查队列。
 *
 * <h3>一、与 OutboxDispatchTask 的对照（两处"抢占"写法刻意不同）</h3>
 * <table border="1">
 *   <caption>抢占方式对比</caption>
 *   <tr><th></th><th>OutboxDispatchTask（生产端）</th><th>本类（消费端重投）</th></tr>
 *   <tr><td>取数</td><td>条件 UPDATE 抢占 100 条（写 owner/claimed_at，进 SENDING）</td>
 *       <td>先只取 id（走 idx_retry_dispatch），再逐行 CAS 抢占</td></tr>
 *   <tr><td>租约</td><td>owner 列 + claimed_at，超时由 reclaimStale 回收</td>
 *       <td><b>时间租约</b>：把 next_retry_at 推到 NOW()+lease，到期自动可重投（不需要回收任务）</td></tr>
 *   <tr><td>为什么不同</td><td>outbox 的状态枚举里有 SENDING 中间态，必须显式回收"抢占后崩溃"</td>
 *       <td>本表状态按设计只有 PENDING/SUCCEEDED/PARKED；时间租约同样保证"多实例只有一人投"，且崩溃自愈</td></tr>
 * </table>
 * 两者都满足同一个底线：**条件 UPDATE 抢占（不持锁做网络 IO）**，且都**不是**"全库 + LIMIT 后各投各的"
 * （D49 记录过那种写法的后果：测试互相抢、谁也说不清是谁投的）。
 *
 * <h3>二、重投的消息必须带原 x-event-id</h3>
 * 否则消费端的事件级去重失效，同一条事件会被重复执行（本步的验收项之一）。
 * {@code x-delay} 固定为 0：延迟语义已由 {@code next_retry_at} 决定，重投是"现在就该投"。
 */
@Slf4j
@Component
public class MessageRetryDispatchTask {

    private final MessageRetryMapper messageRetryMapper;
    private final RabbitTemplate rabbitTemplate;

    private final boolean enabled;
    private final int batchSize;
    private final int leaseSeconds;
    private final long confirmTimeoutMs;

    public MessageRetryDispatchTask(MessageRetryMapper messageRetryMapper,
                                    RabbitTemplate rabbitTemplate,
                                    @Value("${workorder.outbox.dispatch.enabled:false}") boolean enabled,
                                    @Value("${workorder.outbox.dispatch.retry-batch-size:100}") int batchSize,
                                    @Value("${workorder.outbox.dispatch.retry-lease-seconds:300}") int leaseSeconds,
                                    @Value("${workorder.outbox.dispatch.retry-confirm-timeout-ms:5000}") long confirmTimeoutMs) {
        this.messageRetryMapper = messageRetryMapper;
        this.rabbitTemplate = rabbitTemplate;
        this.enabled = enabled;
        this.batchSize = batchSize;
        this.leaseSeconds = leaseSeconds;
        this.confirmTimeoutMs = confirmTimeoutMs;
    }

    @PostConstruct
    void logSwitchState() {
        if (enabled) {
            log.info("[retry] 重投任务已启用：批上限={} 租约={}s 确认超时={}ms（阶梯 1m/5m/15m/1h/6h 见 MessageRetryService）",
                    batchSize, leaseSeconds, confirmTimeoutMs);
        } else {
            log.info("[retry] 重投任务已关闭（workorder.outbox.dispatch.enabled=false）——本地与 CI 默认值");
        }
    }

    @Scheduled(fixedDelayString = "${workorder.outbox.dispatch.retry-interval-ms:10000}")
    public void dispatch() {
        if (!enabled) {
            log.debug("[retry] 重投任务关闭中，跳过本轮");
            return;
        }

        // 取数只取 id（与 idx_retry_dispatch 一一对应）；逐行 CAS 抢占，抢到的人才投
        List<Long> candidateIds = messageRetryMapper.selectPendingIds(batchSize);
        if (candidateIds.isEmpty()) {
            return;
        }

        int republished = 0;
        for (Long id : candidateIds) {
            if (messageRetryMapper.claimById(id, leaseSeconds) != 1) {
                continue;   // 别的实例抢走了（或本轮开始前已被处理）
            }
            MessageRetry row = messageRetryMapper.selectById(id);
            if (row == null) {
                continue;
            }
            if (publish(row)) {
                republished++;
            }
            // 投递失败（broker 不可用/nack/超时）不做状态变更：租约到期后这行自动回到可重投状态，
            // 也**不计入 attempt**——它不是业务失败，不该消耗重试次数
        }
        if (republished > 0) {
            log.info("[retry] 本轮重投 {} 条（候选 {} 条）", republished, candidateIds.size());
        }
    }

    /**
     * 投出一条重试消息，并等 publisher-confirm：只有 ack 才算投出。
     *
     * <p>返回 false 时不改状态（见调用点注释）；重复投递由消费端的去重表兜住，不会重复执行业务。
     */
    private boolean publish(MessageRetry row) {
        try {
            CorrelationData correlationData = new CorrelationData(row.getEventId());
            rabbitTemplate.convertAndSend(RabbitOutboxConfig.DELAY_EXCHANGE,
                    RabbitOutboxConfig.RELEASE_ROUTING_KEY, buildMessage(row), correlationData);
            CorrelationData.Confirm confirm =
                    correlationData.getFuture().get(confirmTimeoutMs, TimeUnit.MILLISECONDS);
            if (confirm.isAck()) {
                log.info("[retry] 已重投: eventId={}, attempt={}, 原 payload={}", row.getEventId(), row.getAttempt(), row.getPayload());
                return true;
            }
            log.warn("[retry] 重投未被 broker 确认（nack），租约到期后自动再试: eventId={}, 原因={}",
                    row.getEventId(), confirm.getReason());
            return false;
        } catch (Exception e) {
            log.warn("[retry] 重投失败，租约到期后自动再试: eventId={}, 原因={}", row.getEventId(), e.getMessage());
            return false;
        }
    }

    private Message buildMessage(MessageRetry row) {
        return MessageBuilder.withBody(row.getPayload().getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setContentEncoding(StandardCharsets.UTF_8.name())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                // 延迟已由 next_retry_at 决定：重投就是"现在投"
                .setHeader(RabbitOutboxConfig.HEADER_DELAY, 0L)
                // **必须带原事件键**，否则消费端去重失效、业务会被重复执行
                .setHeader(RabbitOutboxConfig.HEADER_EVENT_ID, row.getEventId())
                .setHeader(RabbitOutboxConfig.HEADER_EVENT_TYPE, "ORDER_RELEASE_CHECK")
                .build();
    }
}
