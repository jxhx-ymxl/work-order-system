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
 * @param deliverAt        最早可投递时间 = occurredAt + 该工单 type+priority 的 {@code accept_minutes}
 *                         （P1 步骤 2 新增字段：它是"该何时投递"的唯一真相来源，也便于 B 方案做向上取整兜底校验；
 *                         见 D32。放到事件上而不是让 publisher 去查库，是为了让 publisher 保持"纯 DB 写入"）
 * @param payload          瘦消息载荷
 */
public record OrderEvent(
        String eventId,
        String eventType,
        Long aggregateId,
        Integer aggregateVersion,
        LocalDateTime occurredAt,
        LocalDateTime deliverAt,
        Map<String, Object> payload
) {

    /** 聚合根类型：工单 */
    public static final String AGGREGATE_ORDER = "order";

    /** 事件类型：接单后的超时释放检查（P1 只接这一条链路） */
    public static final String TYPE_ORDER_RELEASE_CHECK = "ORDER_RELEASE_CHECK";

    /** 事件类型：提交时缺 type/priority 的工单，交给消费端异步分诊（P5） */
    public static final String TYPE_ORDER_TRIAGE = "ORDER_TRIAGE";

    /** 事件类型：工单已提交，交给消费端异步通知处理人（P5 步骤 2） */
    public static final String TYPE_ORDER_SUBMITTED = "ORDER_SUBMITTED";

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
     * @param deliverAt     最早可投递时间（= occurredAt + 该工单的 accept_minutes）
     */
    public static OrderEvent orderReleaseCheck(Long orderId, Integer orderVersion,
                                              LocalDateTime occurredAt, LocalDateTime deliverAt) {
        return new OrderEvent(
                buildEventId(AGGREGATE_ORDER, orderId, orderVersion, TYPE_ORDER_RELEASE_CHECK),
                TYPE_ORDER_RELEASE_CHECK,
                orderId,
                orderVersion,
                occurredAt,
                deliverAt,
                Map.of("orderId", orderId));
    }

    /**
     * 分诊事件（P5）：payload 仍是瘦消息（只带 orderId），另外带一条**元信息**——
     * {@code missingFields} 记录"提交时哪些字段是空的"（取值 {@code type} / {@code priority}）。
     *
     * <p>为什么把"哪些字段为空"放进消息而不是"消费时再查一次请求"：请求早就结束了，查不到；
     * 也不能用 t_work_order 的 NULL 表示"没提供"——那两列是 NOT NULL（SLA 计算与前端都依赖它们有值）。
     * 元信息随消息走还有一个好处：它同时被 outbox 与重试账本持久化，重投时不会丢（见 D55）。
     *
     * @param missingFields 提交时缺失的字段名集合（{@code type} / {@code priority}），消费端**只写回这些字段**
     */
    public static OrderEvent orderTriage(Long orderId, Integer orderVersion, LocalDateTime occurredAt,
                                        java.util.List<String> missingFields) {
        return new OrderEvent(
                buildEventId(AGGREGATE_ORDER, orderId, orderVersion, TYPE_ORDER_TRIAGE),
                TYPE_ORDER_TRIAGE,
                orderId,
                orderVersion,
                occurredAt,
                occurredAt,   // 分诊是即时事件：deliver_at = 发生时刻（x-delay=0）
                Map.of("orderId", orderId, "missingFields", missingFields));
    }

    /**
     * 提交事件（P5 步骤 2）：工单落库后**同一事务内**写 outbox，消费端据此通知处理人。
     *
     * <p><b>为什么必须是事件而不是在提交事务里直接通知</b>：接收人是"某个角色下的全部用户"，
     * 按 {@code sendToRole} 的写法是"查出来 → 逐个 insert"。一个校区 30–50 名处理人就是 30–50 次单行插入，
     * 串在请求线程里会让提交 RT 随处理人数线性上涨；更要命的是它进了提交事务后，
     * 任何一次通知插入失败都会把**用户的工单提交整体回滚**（`ASYNC-SCHEDULING-PLAN.md` §2.1）。
     *
     * <p><b>版本号</b>：提交时工单 version=0，因此事件键固定是 {@code order:{id}:v0:ORDER_SUBMITTED}。
     * 同一张工单被超时释放后再次提交是不存在的动作（提交只发生一次），所以这里不存在"同一工单两次合法提交"，
     * 版本 0 不会造成漏发。
     *
     * @param deliverAt 最早可投递时间；提交通知是即时事件（= occurredAt，即 x-delay=0）
     */
    public static OrderEvent orderSubmitted(Long orderId, Integer orderVersion, LocalDateTime occurredAt) {
        return new OrderEvent(
                buildEventId(AGGREGATE_ORDER, orderId, orderVersion, TYPE_ORDER_SUBMITTED),
                TYPE_ORDER_SUBMITTED,
                orderId,
                orderVersion,
                occurredAt,
                occurredAt,
                Map.of("orderId", orderId));
    }
}
