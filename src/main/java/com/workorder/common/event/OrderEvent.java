package com.workorder.common.event;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 跨进程投递的事件（P1 起使用）。
 *
 * <p><b>为什么是"事件维度"而不是"实体维度"</b>：{@link #eventId} 的构成是
 * {@code {aggregate}:{aggregateId}:v{version}:{eventType}}，**必须带 version**。
 * 一张工单可以走 {@code PENDING → ACCEPTED → RELEASED → ACCEPTED → RELEASED}，
 * 两次接单超时是**两次合法事件**；若用 {@code orderId} 当去重键，第二次会被当成重复消息吞掉——
 * 这是漏发，比重复更危险，因为它静默（`ASYNC-SCHEDULING-PLAN.md` §3.4 第 3 条记录的现存缺陷，不要在 P1 重犯）。
 *
 * <p><b>瘦消息</b>：{@link #payload} 只携带 {@code orderId}，不携带工单快照。
 * 代价是消费者每次要多读一次库；换来的是消息体永远不会与库中状态不一致
 * （见 `ASYNC-SCHEDULING-PLAN.md` §3.3 对"消息体"的取舍）。
 *
 * @param eventId          事件唯一键，同时是 outbox 表的 UNIQUE 约束与消费端幂等键
 * @param eventType        事件类型，如 {@link #TYPE_ORDER_RELEASE_CHECK}
 * @param aggregateId      聚合根 ID（本项目为工单 ID）
 * @param aggregateVersion 事件发生时的聚合版本（工单的乐观锁 {@code version}）
 * @param occurredAt       事件发生时间（业务侧时钟）
 * @param payload          瘦消息载荷
 */
public record OrderEvent(
        String eventId,
        String eventType,
        Long aggregateId,
        Integer aggregateVersion,
        LocalDateTime occurredAt,
        Map<String, Object> payload
) {

    /** 聚合根类型：工单 */
    public static final String AGGREGATE_ORDER = "order";

    /** 事件类型：接单后的超时释放检查（P1 只接这一条链路） */
    public static final String TYPE_ORDER_RELEASE_CHECK = "ORDER_RELEASE_CHECK";

    /**
     * 构造事件维度的事件键：{@code {aggregate}:{aggregateId}:v{version}:{eventType}}。
     *
     * <p>例：{@code order:123:v7:ORDER_RELEASE_CHECK}——同一工单在不同 version 上是**不同事件**。
     */
    public static String buildEventId(String aggregate, Long aggregateId, Integer aggregateVersion, String eventType) {
        return aggregate + ":" + aggregateId + ":v" + aggregateVersion + ":" + eventType;
    }

    /**
     * 释放检查事件：接单（或指派）成功后，业务事务内写 outbox 用。
     *
     * @param orderId       工单 ID
     * @param orderVersion  接单后的工单版本（乐观锁字段），用于区分"同一工单的多次合法接单"
     * @param occurredAt    事件发生时间
     */
    public static OrderEvent orderReleaseCheck(Long orderId, Integer orderVersion, LocalDateTime occurredAt) {
        return new OrderEvent(
                buildEventId(AGGREGATE_ORDER, orderId, orderVersion, TYPE_ORDER_RELEASE_CHECK),
                TYPE_ORDER_RELEASE_CHECK,
                orderId,
                orderVersion,
                occurredAt,
                Map.of("orderId", orderId));
    }
}
