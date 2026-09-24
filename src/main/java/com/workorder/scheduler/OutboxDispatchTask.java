package com.workorder.scheduler;

import com.workorder.config.RabbitOutboxConfig;
import com.workorder.entity.EventOutbox;
import com.workorder.mapper.EventOutboxMapper;
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
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * outbox → RabbitMQ 投递任务（P1 步骤 3 的核心）。
 *
 * <p><b>一轮做四件事</b>：回收遗留中间态 → 原子抢占 → 发送（此时不持有任何行锁）→ 按 confirm 结果回写。
 *
 * <p><b>三条硬约束，逐条说明为什么这么写</b>：
 * <ol>
 *   <li><b>不允许"持锁做网络 IO"</b>：抢占是一条条件 UPDATE（语句结束即释放锁），
 *       之后才发送。若用 {@code SELECT ... FOR UPDATE} 再在事务里发送，在 2 vCPU 的机器上
 *       一个卡住的 broker 就能把整个投递循环连同数据库连接一起拖住。</li>
 *   <li><b>抢占后崩溃必须能回收</b>：SENDING 是租约，超过 {@code reclaim-minutes} 无进展
 *       就被 {@link EventOutboxMapper#reclaimStale(int)} 改回 PENDING。</li>
 *   <li><b>只有 publisher-confirm 的 ack 才算成功</b>：{@code convertAndSend} 不抛异常
 *       只说明"交给驱动了"，不说明 broker 收下了。nack / 超时一律回写重试。</li>
 * </ol>
 *
 * <p><b>延迟语义</b>：本任务不按 {@code deliver_at} 卡投递时机——到点与否由 broker 的延迟交换机
 * 承担（{@code x-delay = deliver_at - now}，过期的按 0 处理）。理由与代价见 D32/D36。
 * 因此"到点"这件事的真相来源仍然是 {@code t_event_outbox.deliver_at}，只是由 broker 执行。
 *
 * <p><b>本轮刻意不做的事</b>：消费端（P1 步骤 4）。所以队列里会堆积消息，这是预期状态；
 * 验证看队列深度与 outbox 状态，而不是等它被消费。
 */
@Slf4j
@Component
public class OutboxDispatchTask {

    /**
     * 延迟插件把 {@code x-delay} 当 int32 读，超过 {@link Integer#MAX_VALUE} 毫秒（约 24.85 天）
     * 会溢出成负数或未定义行为。这里主动夹到上限并记 WARN，而不是把异常值原样发出去。
     */
    static final long MAX_DELAY_MS = Integer.MAX_VALUE;

    private final EventOutboxMapper eventOutboxMapper;
    private final RabbitTemplate rabbitTemplate;

    private final boolean enabled;
    private final int batchSize;
    private final int reclaimMinutes;
    private final long confirmTimeoutMs;
    private final long batchBudgetMs;
    private final long retryBackoffSeconds;
    private final int maxAttempts;

    public OutboxDispatchTask(EventOutboxMapper eventOutboxMapper,
                              RabbitTemplate rabbitTemplate,
                              @Value("${workorder.outbox.dispatch.enabled:false}") boolean enabled,
                              @Value("${workorder.outbox.dispatch.batch-size:100}") int batchSize,
                              @Value("${workorder.outbox.dispatch.reclaim-minutes:5}") int reclaimMinutes,
                              @Value("${workorder.outbox.dispatch.confirm-timeout-ms:5000}") long confirmTimeoutMs,
                              @Value("${workorder.outbox.dispatch.batch-budget-ms:15000}") long batchBudgetMs,
                              @Value("${workorder.outbox.dispatch.retry-backoff-seconds:30}") long retryBackoffSeconds,
                              @Value("${workorder.outbox.dispatch.max-attempts:20}") int maxAttempts) {
        this.eventOutboxMapper = eventOutboxMapper;
        this.rabbitTemplate = rabbitTemplate;
        this.enabled = enabled;
        this.batchSize = batchSize;
        this.reclaimMinutes = reclaimMinutes;
        this.confirmTimeoutMs = confirmTimeoutMs;
        this.batchBudgetMs = batchBudgetMs;
        this.retryBackoffSeconds = retryBackoffSeconds;
        this.maxAttempts = maxAttempts;
    }

    @PostConstruct
    void logSwitchState() {
        if (enabled) {
            log.info("[outbox] 投递任务已启用：exchange={} 队列={} 批上限={} 确认超时={}ms 批预算={}ms "
                            + "退避={}s 尝试上限={}次 回收阈值={}min",
                    RabbitOutboxConfig.DELAY_EXCHANGE, RabbitOutboxConfig.RELEASE_QUEUE, batchSize,
                    confirmTimeoutMs, batchBudgetMs, retryBackoffSeconds, maxAttempts, reclaimMinutes);
        } else {
            log.info("[outbox] 投递任务已关闭（workorder.outbox.dispatch.enabled=false）——本地与 CI 的默认值，"
                    + "此状态下不会连接 broker、也不会修改 outbox 状态；生产环境由 compose 显式置 true");
        }
    }

    @Scheduled(fixedDelayString = "${workorder.outbox.dispatch.interval-ms:5000}")
    public void dispatch() {
        if (!enabled) {
            log.debug("[outbox] 投递任务关闭中，跳过本轮");
            return;
        }

        final String owner = ownerId();
        final long budgetDeadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(batchBudgetMs);

        // ① 回收遗留中间态（进程崩溃/卡死留下的 SENDING）
        int reclaimed = eventOutboxMapper.reclaimStale(reclaimMinutes);
        if (reclaimed > 0) {
            log.warn("[outbox] 回收遗留 SENDING 记录 {} 条（超过 {} 分钟无进展）", reclaimed, reclaimMinutes);
        }

        // ② 原子抢占（一条 UPDATE，语句结束即释放行锁）
        int claimed = eventOutboxMapper.claimPending(owner, batchSize);
        if (claimed == 0) {
            return;
        }
        List<EventOutbox> rows = eventOutboxMapper.selectClaimedBy(owner);
        if (rows.isEmpty()) {
            return;
        }

        // ③ 发送（从这里开始没有任何数据库行锁）
        List<SendAttempt> attempts = new ArrayList<>(rows.size());
        List<EventOutbox> notAttempted = new ArrayList<>();
        boolean abortBatch = false;
        for (EventOutbox row : rows) {
            if (abortBatch || System.nanoTime() > budgetDeadlineNanos) {
                notAttempted.add(row);
                continue;
            }
            try {
                CorrelationData correlationData = new CorrelationData(row.getEventId());
                rabbitTemplate.convertAndSend(RabbitOutboxConfig.DELAY_EXCHANGE,
                        RabbitOutboxConfig.RELEASE_ROUTING_KEY, buildMessage(row), correlationData);
                attempts.add(new SendAttempt(row, correlationData));
            } catch (Exception e) {
                // 到这一层的异常基本等价于"连接/通道级故障"：消息级问题（不可路由、被 nack）
                // 走 returns 回调与 confirm future，不抛异常。同一轮里对后续记录继续尝试没有信息量，
                // 反而会长时间占着调度线程（它与既有几个 @Scheduled 任务共用线程），故早退。
                markAttemptFailed(row, "publish 抛异常(" + e.getClass().getSimpleName() + "): " + e.getMessage());
                abortBatch = true;
            }
        }

        // ④ 等 confirm：只有 ack 才标 SENT
        for (SendAttempt attempt : attempts) {
            long remainNanos = budgetDeadlineNanos - System.nanoTime();
            if (remainNanos <= 0) {
                markAttemptFailed(attempt.row(), "confirm 超出本轮批预算（未收到 ack）");
                continue;
            }
            long waitNanos = Math.min(remainNanos, TimeUnit.MILLISECONDS.toNanos(confirmTimeoutMs));
            try {
                CorrelationData.Confirm confirm =
                        attempt.correlationData().getFuture().get(waitNanos, TimeUnit.NANOSECONDS);
                if (confirm.isAck()) {
                    markSent(attempt.row());
                } else {
                    markAttemptFailed(attempt.row(), "broker nack: " + confirm.getReason());
                }
            } catch (TimeoutException e) {
                markAttemptFailed(attempt.row(), "confirm 超时（" + confirmTimeoutMs + "ms 内未收到 ack）");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                markAttemptFailed(attempt.row(), "confirm 等待被中断");
            } catch (ExecutionException e) {
                markAttemptFailed(attempt.row(), "confirm 异常: " + (e.getCause() == null ? e : e.getCause()));
            }
        }

        // 未尝试的记录退回 PENDING（不计失败次数：它没被尝试过）
        for (EventOutbox row : notAttempted) {
            if (eventOutboxMapper.releaseClaim(row.getId()) > 0) {
                log.warn("[outbox] 本轮提前收尾，记录退回 PENDING 待下轮：eventId={}", row.getEventId());
            }
        }
    }

    /**
     * 组装消息：瘦消息体（payload 原样，只带 orderId）+ 延迟头 + 幂等键头。
     */
    private Message buildMessage(EventOutbox row) {
        return MessageBuilder.withBody(row.getPayload().getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setContentEncoding(StandardCharsets.UTF_8.name())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setHeader(RabbitOutboxConfig.HEADER_DELAY, delayMillis(row.getDeliverAt()))
                .setHeader(RabbitOutboxConfig.HEADER_EVENT_ID, row.getEventId())
                .setHeader(RabbitOutboxConfig.HEADER_EVENT_TYPE, row.getEventType())
                .build();
    }

    /**
     * 距离 {@code deliver_at} 还剩多少毫秒（已过期返回 0 = 立即投递）。
     *
     * <p>包级可见是为了能被单测直接断言——这段计算是"延迟语义"的唯一实现点。
     */
    long delayMillis(LocalDateTime deliverAt) {
        if (deliverAt == null) {
            return 0;
        }
        long millis = Duration.between(LocalDateTime.now(), deliverAt).toMillis();
        if (millis <= 0) {
            return 0;
        }
        if (millis > MAX_DELAY_MS) {
            log.warn("[outbox] deliver_at 超远（{}），x-delay 夹到 int32 上限 {}ms：", deliverAt, MAX_DELAY_MS);
            return MAX_DELAY_MS;
        }
        return millis;
    }

    private void markSent(EventOutbox row) {
        if (eventOutboxMapper.markSent(row.getId()) != 1) {
            log.warn("[outbox] 标记 SENT 未命中（记录可能已被回收或状态已变）：eventId={}", row.getEventId());
        }
    }

    /**
     * 失败回写。退避现在是固定值，P4 换成 1m/5m/15m/1h/6h 的阶梯退避 + t_message_retry。
     */
    private void markAttemptFailed(EventOutbox row, String reason) {
        int retryCount = (row.getRetryCount() == null ? 0 : row.getRetryCount()) + 1;
        boolean fatal = retryCount >= maxAttempts;
        LocalDateTime nextRetryAt = fatal ? null : LocalDateTime.now().plusSeconds(retryBackoffSeconds);
        int updated = eventOutboxMapper.markAttemptFailed(row.getId(), fatal ? "FAILED" : "PENDING", nextRetryAt);

        if (fatal) {
            // 达到上限不等于"工单一定没被释放"：兜底扫描与 MQ 路径是两条独立通道，
            // 这条记录只代表"MQ 这条通道没能把事件送出去"。P4 的 retry-replay 会接管 FAILED 记录。
            log.error("[outbox] 投递失败已达上限 {} 次，转 FAILED 待人工介入：eventId={} 原因={}",
                    maxAttempts, row.getEventId(), reason);
        } else {
            // 退避：P4 换成 1m/5m/15m/1h/6h
            log.warn("[outbox] 投递未确认，{}s 后重试（第 {} 次）：eventId={} 原因={}",
                    retryBackoffSeconds, retryCount, row.getEventId(), reason);
        }
        if (updated != 1) {
            log.warn("[outbox] 失败回写未命中（记录可能已被回收）：eventId={}", row.getEventId());
        }
    }

    /**
     * 抢占者标识。带上 host/pid 是为了排查"哪个实例留下的 SENDING"，
     * 随机后缀防止同一实例的两轮抢占互相认领（owner 列长 64，超长截断）。
     */
    private String ownerId() {
        String host = System.getenv().getOrDefault("HOSTNAME", "");
        if (host.isBlank()) {
            host = System.getProperty("host.name", "unknown-host");
        }
        String owner = host + ":" + ProcessHandle.current().pid() + ":" + UUID.randomUUID().toString().substring(0, 8);
        return owner.length() > 64 ? owner.substring(0, 64) : owner;
    }

    private record SendAttempt(EventOutbox row, CorrelationData correlationData) {
    }
}
