package com.workorder.service;

public interface MessagePublishService {

    void sendReleaseCheck(Long orderId);

    /**
     * SLA 升级通知（P1 边界：**本方法保持现状，不迁移**）。
     *
     * <p>它同时被两处调用：{@code SlaEscalationScheduler}（真·SLA 超时）与
     * {@code WorkOrderServiceImpl.rejectOrder}（驳回次数达上限）——两类事件共用一条通道，
     * 语义已过载（`ASYNC-SCHEDULING-PLAN.md` §3.2 第 2 条）。
     *
     * <p><b>迁移计划：P4/P5 迁到事件模型（{@code MessagePublisher} + {@code OrderEvent}），届时删除本方法
     * 与整个旧接口。</b>P4 之前不动的原因：SLA 告警链路的正确性依赖 H1 的分级催办与
     * `eventVersion` 递增，而这些属于 P4 的幂等/死信设计——在那之前迁移会把"漏发"风险引入进来。
     * 决策见 {@code docs/DECISIONS.md} D31。
     */
    void sendSlaEscalation(Long orderId);
}
