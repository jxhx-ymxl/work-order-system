package com.workorder.service;

import com.workorder.common.event.OrderEvent;

/**
 * 事件发布入口（P1 引入）。
 *
 * <p><b>与既有的 {@link MessagePublishService} 的关系</b>：
 * <ul>
 *   <li>{@code MessagePublishService}（旧接口，方法签名 {@code sendXxx(orderId)}）**暂时保留**，
 *       它承载 SLA 升级链路（{@code sendSlaEscalation}），P1 不改那条链路的行为。</li>
 *   <li>{@code MessagePublisher}（本接口）承载"释放检查"这一条链路，以 {@link OrderEvent} 为参数，
 *       携带事件维度的事件键（见 {@code OrderEvent} 的类注释）。</li>
 * </ul>
 * 两条接口并存是**过渡态**：待 P4/P5 把 SLA 链路一并迁移到事件模型后，旧接口删除
 * （见 {@code docs/DECISIONS.md} 的对应条目）。
 *
 * <p><b>实现约束（P1）</b>：实现类**不得**在业务事务内直接 {@code convertAndSend}，
 * 也不得挂 {@code TransactionSynchronization.afterCommit}——跨事务投递一律走 outbox
 * （`CLAUDE.md` §3 架构不变量第 2 条）。本接口的实现可以是"写 outbox"，由投递任务负责真正发出。
 */
public interface MessagePublisher {

    /**
     * 发布一个事件。实现方负责保证"业务事务提交则事件不丢"（P1 用 outbox 达成）。
     */
    void publish(OrderEvent event);
}
