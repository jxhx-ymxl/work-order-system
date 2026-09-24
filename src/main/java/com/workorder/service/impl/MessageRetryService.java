package com.workorder.service.impl;

import com.workorder.entity.MessageRetry;
import com.workorder.mapper.MessageRetryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 消费失败的**重试账本**（P4 步骤 2）。
 *
 * <h3>一、本类最重要的一件事：事务传播必须是 REQUIRES_NEW（不能被卷进业务事务）</h3>
 * 两张表的事务要求**正好相反**，这是本步最容易做错的地方：
 * <table border="1">
 *   <caption>事务要求对比</caption>
 *   <tr><th>表</th><th>与业务事务的关系</th><th>为什么</th></tr>
 *   <tr><td>{@code t_consume_record}</td><td><b>必须同事务</b></td>
 *       <td>业务失败要连去重记录一起回滚，否则重试被永久跳过（消息被静默吃掉）</td></tr>
 *   <tr><td>{@code t_message_retry}（本类）</td><td><b>必须在业务事务之外</b></td>
 *       <td>业务失败**恰恰是要重试的原因**：重试记录若跟着业务回滚，就变成"失败了但没人记得要重试"</td></tr>
 * </table>
 * 实现：本类的两个写方法都是 {@code @Transactional(propagation = REQUIRES_NEW)}——
 * 即使调用方正处在（即将回滚的）业务事务里，也会挂起它、用**独立事务**提交。
 * <b>不要为了"统一"把这两个写方法改成默认传播</b>：那样本步的灵魂测试会立刻失败。
 *
 * <h3>二、阶梯（怎么由 attempt 推出 next_retry_at）</h3>
 * {@code attempt} = **已失败次数**（每次业务失败 +1；成功/跳过/重复都不计）。
 * 失败第 N 次（N ≤ 5）→ {@code next_retry_at = now + LADDER[N-1]}，阶梯 = **1m → 5m → 15m → 1h → 6h**；
 * 第 6 次失败（{@code attempt > 5}）→ {@code status='PARKED'}、{@code next_retry_at=NULL} 并记 ERROR 日志（人工介入入口）。
 * 因此"超过 5 次停车"= 自动重投最多 5 次，累计跨度约 7 小时 21 分。
 *
 * <h3>三、为什么是"更新同一条"而不是"插一条新的"</h3>
 * {@code UNIQUE(event_id, consumer)}：同一条事件的重试账本只应有一条，多次失败是**更新它**（attempt 递增）。
 * 用 {@code SELECT ... FOR UPDATE} 让并发失败串行化（短事务、无网络 IO）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageRetryService {

    /** 阶梯：1m → 5m → 15m → 1h → 6h（P4 步骤 2；消费端专用，见 D53 与 outbox 的对比） */
    static final Duration[] LADDER = {
            Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15),
            Duration.ofHours(1), Duration.ofHours(6)
    };

    /** 最大自动重试次数（= 阶梯长度）；超过 → PARKED */
    public static final int MAX_ATTEMPTS = LADDER.length;

    /** {@code last_error} 列长，写入前截断（避免"列超长"把重试记录本身写失败） */
    static final int LAST_ERROR_MAX_LENGTH = 500;

    private final MessageRetryMapper messageRetryMapper;

    /**
     * 记录一次失败：首次插入（attempt=1），之后 attempt+1 并按阶梯推 {@code next_retry_at}；超上限转 PARKED。
     *
     * <p><b>REQUIRES_NEW</b>：见类注释"一"。调用点可能在业务事务的 catch 里（那时业务事务尚未回滚完）。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void recordFailure(String eventId, String consumer, String payload, String error) {
        String lastError = truncate(error);
        MessageRetry existing = messageRetryMapper.selectForUpdate(eventId, consumer);

        if (existing == null) {
            MessageRetry row = new MessageRetry();
            row.setEventId(eventId);
            row.setConsumer(consumer);
            row.setPayload(payload);
            row.setAttempt(1);
            row.setStatus("PENDING");
            row.setNextRetryAt(LocalDateTime.now().plus(LADDER[0]));
            row.setLastError(lastError);
            messageRetryMapper.insert(row);
            log.warn("[retry] 首次失败已落账，{} 后重投（1/{}）：eventId={} 原因={}",
                    LADDER[0], MAX_ATTEMPTS, eventId, lastError);
            return;
        }

        int attempt = (existing.getAttempt() == null ? 0 : existing.getAttempt()) + 1;
        if (attempt > MAX_ATTEMPTS) {
            messageRetryMapper.markParked(existing.getId(), attempt, lastError);
            log.error("[retry] 重试已达上限 {} 次，停止自动重投并转 PARKED（等人工介入）：eventId={} 最后一次原因={}",
                    MAX_ATTEMPTS, eventId, lastError);
        } else {
            Duration delay = LADDER[attempt - 1];
            messageRetryMapper.markPendingAgain(existing.getId(), attempt, LocalDateTime.now().plus(delay), lastError);
            log.warn("[retry] 第 {} 次失败已落账，{} 后重投（{}/{}）：eventId={} 原因={}",
                    attempt, delay, attempt, MAX_ATTEMPTS, eventId, lastError);
        }
    }

    /**
     * 业务成功（RELEASED / SKIPPED / 重复投递）时关闭账本。
     *
     * <p>没有账本时影响 0 行——**这是常态**（绝大多数消息第一次就成功，从来没写过重试记录）。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void markSucceeded(String eventId, String consumer) {
        int updated = messageRetryMapper.markSucceeded(eventId, consumer);
        if (updated > 0) {
            log.info("[retry] 重投成功，账本关闭（SUCCEEDED）：eventId={}", eventId);
        }
    }

    private String truncate(String error) {
        if (error == null) {
            return "（无错误信息）";
        }
        return error.length() <= LAST_ERROR_MAX_LENGTH ? error : error.substring(0, LAST_ERROR_MAX_LENGTH);
    }
}
