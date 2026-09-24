package com.workorder.service.impl;

import com.workorder.common.enums.ReleaseResult;
import com.workorder.service.impl.ConsumeRecordService.ConsumeResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * "消费一条释放检查事件"的编排（P4 步骤 2）：**成功/失败分流的唯一落点**。
 *
 * <h3>一、成功与失败怎么判定（沿用步骤 4 的三态）</h3>
 * <ul>
 *   <li>{@link ReleaseResult#RELEASED}、{@link ReleaseResult#SKIPPED}<b>都算成功</b>：
 *       SKIPPED 只是"状态守卫没命中"（单子已经开工/已释放），**不是失败**——
 *       不写重试账本、不计尝试次数（把它当失败会造成"已经不该释放的单被反复重投"）。</li>
 *   <li>{@link ReleaseResult#ERROR} 与抛出的异常都算失败：写重试账本，交给阶梯重投。</li>
 * </ul>
 *
 * <h3>二、为什么"写重试账本"必须跑到业务事务之外</h3>
 * 本类**不带 {@code @Transactional}**：业务事务由 {@link ConsumeRecordService} 持有并在这里抛异常后结束（已回滚），
 * 之后才调 {@link MessageRetryService}（它自己是 {@code REQUIRES_NEW}）。
 * 这样"业务失败 → 去重记录回滚"与"业务失败 → 重试记录落库"**同时成立**——
 * 若把两者塞进同一个事务，要么去重记录不滚（重试被永久跳过），要么重试记录也滚（失败了没人记得要重试）。
 *
 * <h3>三、ACK 时机</h3>
 * 本方法返回时，重试账本已经提交；listener 才 ACK。**顺序不能反**：
 * 先 ACK 再落账，中间崩溃就会出现"消息被确认了、但没人记得要重试"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReleaseCheckConsumeService {

    /** 消费结果：给 listener 决定日志级别与 ACK 动作 */
    public enum Outcome {
        /** 真的释放成功 */
        RELEASED,
        /** 状态守卫未命中（正常结论，不是失败） */
        SKIPPED,
        /** 去重命中：这条事件之前已经消费过 */
        DUPLICATE,
        /** 失败，已落重试账本，等阶梯重投 */
        RETRY_SCHEDULED,
        /** 失败且无法落账（缺 x-event-id，没有可用的重试键）——只能 ACK 并留痕，靠兜底扫描保证释放 */
        NOT_RETRYABLE
    }

    private final ConsumeRecordService consumeRecordService;
    private final MessageRetryService messageRetryService;

    /**
     * 消费一条释放检查事件。
     *
     * @param eventId 事件唯一键（来自 {@code x-event-id}；可能为 null —— 投递侧正常一定会带）
     * @param orderId 工单 ID
     * @param payload 原始消息体（写进重试账本，重投时原样投出）
     */
    public Outcome consume(String eventId, Long orderId, String payload) {
        try {
            ConsumeResult result = consumeRecordService.consumeReleaseCheck(eventId, orderId);

            if (result.duplicate()) {
                // 重复投递说明这条事件**之前已经成功**过（去重记录在），顺手关掉可能存在的重试账本
                closeLedger(eventId);
                return Outcome.DUPLICATE;
            }

            ReleaseResult release = result.release();
            if (release == ReleaseResult.RELEASED) {
                closeLedger(eventId);
                return Outcome.RELEASED;
            }
            if (release == ReleaseResult.SKIPPED) {
                // **不是失败**：不写重试账本、不计尝试次数
                closeLedger(eventId);
                return Outcome.SKIPPED;
            }
            // 三态里的 ERROR：业务侧内部错误（工单不存在等），重试有意义
            boolean scheduled = scheduleRetry(eventId, orderId, payload,
                    "releaseOrder 返回 ERROR（工单不存在或内部错误）");
            return scheduled ? Outcome.RETRY_SCHEDULED : Outcome.NOT_RETRYABLE;

        } catch (Exception e) {
            // 业务异常（事务已回滚：去重记录也回滚了）——这**正是要重试的原因**
            boolean scheduled = scheduleRetry(eventId, orderId, payload,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
            return scheduled ? Outcome.RETRY_SCHEDULED : Outcome.NOT_RETRYABLE;
        }
    }

    /**
     * 写重试账本（在业务事务之外，见类注释"二"）。
     *
     * @return {@code true} = 已落账，可重投；{@code false} = 无法落账（缺事件键或写账本本身失败）
     */
    private boolean scheduleRetry(String eventId, Long orderId, String payload, String error) {
        if (eventId == null || eventId.isBlank()) {
            log.error("[consume] 消费失败但缺少 x-event-id，**无法落重试账本**（重试要靠事件键做幂等），本条不重投；"
                    + "工单的释放仍由兜底扫描保证: orderId={}, 原因={}", orderId, error);
            return false;
        }
        try {
            messageRetryService.recordFailure(eventId, ConsumeRecordService.CONSUMER_ORDER_RELEASE,
                    payloadOf(payload, orderId), error);
            return true;
        } catch (Exception e) {
            // 写账本本身失败：不能吞掉（消息会被 ACK 掉，MQ 这条路径丢一次；兜底扫描仍保释放）
            log.error("[consume] 写重试账本失败——本轮 ACK 后这条消息不会自动重投，"
                    + "工单的释放改由兜底扫描完成: eventId={}, orderId={}", eventId, orderId, e);
            return false;
        }
    }

    private void closeLedger(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return;
        }
        try {
            messageRetryService.markSucceeded(eventId, ConsumeRecordService.CONSUMER_ORDER_RELEASE);
        } catch (Exception e) {
            // 关账失败不影响业务结果（账本最多留一条 PENDING，租约到期后重投时会再判一次重复）
            log.warn("[consume] 关闭重试账本失败（不影响本次业务结果）: eventId={}, 原因={}", eventId, e.getMessage());
        }
    }

    /** payload 列 NOT NULL：消息体缺失时用最小合法载荷兜底 */
    private String payloadOf(String payload, Long orderId) {
        return payload == null || payload.isBlank() ? "{\"orderId\":" + orderId + "}" : payload;
    }
}
