package com.workorder.agent;

/**
 * 一条被登记的证据。
 *
 * @param id      本轮内的证据编号（{@code E1}、{@code E2}…），模型只能引用这个编号
 * @param fact    事实键（如 {@code order.status}），完成判据比对的就是它
 * @param value   事实值（已脱敏）
 * @param unknown 是否显式未知（未知也计"已覆盖"，但报告必须标注）
 * @param empty   是否"空 / 无记录"（完整事实，**不是**未知；§11-4 的禁止项要用它区分）
 */
public record AgentEvidence(String id, String fact, String value, boolean unknown, boolean empty) {
}
