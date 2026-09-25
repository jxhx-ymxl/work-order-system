package com.workorder.service.impl;

import com.workorder.entity.WorkOrder;
import com.workorder.mapper.WorkOrderMapper;
import com.workorder.service.NotificationService;
import com.workorder.service.impl.ConsumeRecordService.BusinessOutcome;
import com.workorder.service.impl.ConsumeRecordService.ConsumeResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 提交通知事件的消费编排（P5 步骤 2）：**接收人解析与站内信写入都在这里，不在提交事务里**。
 *
 * <h3>一、这条链路为什么要异步（它比 triage 更早被论证为"必须异步"）</h3>
 * 依据 `ASYNC-SCHEDULING-PLAN.md` §2.1：`sendToRole` 是"查出角色下所有用户 → 逐个 insert"，
 * 一个校区 30–50 名处理人就是 30–50 次单行插入。它若留在提交事务里，后果有两层：
 * <ol>
 *   <li>提交 RT 随处理人数量线性上涨（30 人约 +60–150ms，且随表增长恶化）；</li>
 *   <li>更糟：**通知插入失败（表锁、连接超时）会把用户的工单提交整体回滚**，
 *       把旁路动作的失败变成核心业务的失败；并发提交时还会和业务写抢同一个 Hikari 连接池（上限 20）。</li>
 * </ol>
 * 因此提交侧只写一行 outbox（P1 已建好的写路径），本类才做"查人 + 写 N 条"。
 *
 * <h3>二、两道幂等防线（职责不同，都要留）</h3>
 * <ol>
 *   <li>去重表 `t_consume_record`（consumer=`order-submitted-listener`）：回答"这条事件消费过吗"；</li>
 *   <li>通知表唯一键 `UNIQUE(event_id, user_id)`：回答"这条事件给**这个接收人**发过吗"。
 *       它才是"同一个接收人不会收到两条一样的站内信"的硬保证——去重表只能保证"本方法只跑一次"，
 *       挡不住"去重记录被归档清理 / 有人绕过消费端直写通知"这些情况。</li>
 * </ol>
 *
 * <h3>三、状态守卫：只通知"还在池子里"的单</h3>
 * 这条通知的语义是"池子里有新单"（plan §2.1 的原话）。若消费时工单已经不是 `PENDING`
 * （被别人抢走 / 已释放），通知就变成误导性噪音，于是返回 SKIPPED。
 * SKIPPED **不是失败**：不写重试账本、不计尝试次数（与释放链路的 SKIPPED 同一套语义）。
 *
 * <h3>四、失败怎么办</h3>
 * 工单不存在、写站内信抛异常 → 返回 `BusinessOutcome.FAILED`：去重记录随之回滚，
 * 再由本编排层（在业务事务之外）写 P4 的重试账本，按 1m/5m/15m/1h/6h 阶梯重投、超限 PARKED。
 * 通知失败不影响提交：提交早已成功返回，工单也已落库（PENDING 池有列表轮询兜底）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderSubmittedConsumeService {

    /** 通知对象的口径：**全部处理人**（plan §2.1 的前提就是"一个校区 30–50 名处理人"，逐个 insert） */
    public static final String NOTIFY_ROLE = "HANDLER";

    /** 消费结果：给 listener 决定日志级别与 ACK 动作（与另外两条消费链路同构） */
    public enum Outcome { NOTIFIED, SKIPPED, DUPLICATE, RETRY_SCHEDULED, NOT_RETRYABLE }

    private final ConsumeRecordService consumeRecordService;
    private final MessageRetryService messageRetryService;
    private final WorkOrderMapper workOrderMapper;
    private final NotificationService notificationService;

    /**
     * 消费一条"工单已提交"事件：**同一事务内**"写去重记录 + 查接收人 + 写站内信"。
     *
     * <p>三者同事务是刻意的：站内信写失败 → 去重记录一起回滚 → 重投时能重来
     * （若去重记录先落地，失败后的重投会被判"已消费"而永久跳过，等于静默丢通知）。
     * 而"重试账本"必须落在这个事务**之外**（{@link MessageRetryService} 是 REQUIRES_NEW），
     * 理由见 D53：业务失败恰恰是要重试的原因，账本跟着回滚就没得重试了。
     */
    public Outcome consume(String eventId, Long orderId, String payload) {
        try {
            ConsumeResult result = consumeRecordService.consumeOnce(eventId,
                    ConsumeRecordService.CONSUMER_ORDER_SUBMITTED,
                    () -> notifyHandlers(eventId, orderId));

            if (result.duplicate()) {
                closeLedger(eventId);
                return Outcome.DUPLICATE;
            }
            return switch (result.outcome()) {
                case SUCCESS -> {
                    closeLedger(eventId);
                    yield Outcome.NOTIFIED;
                }
                case SKIPPED -> {
                    closeLedger(eventId);
                    yield Outcome.SKIPPED;
                }
                case FAILED -> scheduleRetry(eventId, orderId, payload, "提交通知失败（工单不存在或站内信写入失败）");
            };
        } catch (Exception e) {
            return scheduleRetry(eventId, orderId, payload, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * 真正的业务：在调用方（`consumeOnce`）的事务里执行——查接收人 + 逐个写站内信。
     */
    private BusinessOutcome notifyHandlers(String eventId, Long orderId) {
        WorkOrder order = workOrderMapper.selectById(orderId);
        if (order == null) {
            log.error("[notify] 工单不存在，无法通知（进重试账本）: orderId={}, eventId={}", orderId, eventId);
            return BusinessOutcome.FAILED;
        }

        // 状态守卫：只通知"还在池子里"的单
        if (!"PENDING".equals(order.getStatus())) {
            log.debug("[notify] 跳过：工单已不在待分配池（status={}），通知已无意义: orderId={}, eventId={}",
                    order.getStatus(), orderId, eventId);
            return BusinessOutcome.SKIPPED;
        }

        String title = "新工单待抢单：" + order.getOrderNo();
        // 内容按"消费时刻"的库中状态生成。注意：若分诊还没写回，这里显示的可能是兜底类型
        // （OTHER/普通）——这是"先落库、后修正"的已知窗口期，不额外处理（通知只负责"有新单"）。
        String content = "类型:" + order.getType()
                + ", 优先级:" + (order.getPriority() != null && order.getPriority() == 1 ? "紧急" : "普通")
                + ", 当前状态:" + order.getStatus()
                + ", SLA 截止:" + order.getSlaDeadline();

        int inserted = notificationService.sendToRoleOnce(NOTIFY_ROLE, title, content,
                eventId, "ORDER", orderId);
        if (inserted == 0) {
            // 两种正常情况都会走到这里：① 该角色下没有用户；② 这条事件已经给这些接收人发过（唯一键挡住）。
            // 都不算失败——重试不会变出新接收人。记 WARN 让"看起来发了、其实没人收到"这类状态可见。
            log.warn("[notify] 本次没有新增站内信（角色下无用户，或该事件已给这些接收人发过，被 UNIQUE(event_id,user_id) 挡住）: "
                    + "orderId={}, role={}, eventId={}", orderId, NOTIFY_ROLE, eventId);
            return BusinessOutcome.SUCCESS;
        }
        log.info("[notify] 提交通知已发出: orderNo={}, role={}, 新增接收人={} 人, eventId={}",
                order.getOrderNo(), NOTIFY_ROLE, inserted, eventId);
        return BusinessOutcome.SUCCESS;
    }

    private Outcome scheduleRetry(String eventId, Long orderId, String payload, String error) {
        if (eventId == null || eventId.isBlank()) {
            log.error("[notify] 通知失败但缺少 x-event-id，无法落重试账本，本条不重投"
                    + "（处理人未收到通知，工单仍在 PENDING 池里可被列表看到）: orderId={}, 原因={}", orderId, error);
            return Outcome.NOT_RETRYABLE;
        }
        try {
            messageRetryService.recordFailure(eventId, ConsumeRecordService.CONSUMER_ORDER_SUBMITTED,
                    payload == null || payload.isBlank() ? "{\"orderId\":" + orderId + "}" : payload, error);
            return Outcome.RETRY_SCHEDULED;
        } catch (Exception e) {
            log.error("[notify] 写重试账本失败（这条消息本轮 ACK 后不会自动重投）: eventId={}, orderId={}", eventId, orderId, e);
            return Outcome.NOT_RETRYABLE;
        }
    }

    private void closeLedger(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return;
        }
        try {
            messageRetryService.markSucceeded(eventId, ConsumeRecordService.CONSUMER_ORDER_SUBMITTED);
        } catch (Exception e) {
            log.warn("[notify] 关闭重试账本失败（不影响本次结果）: eventId={}, 原因={}", eventId, e.getMessage());
        }
    }
}
