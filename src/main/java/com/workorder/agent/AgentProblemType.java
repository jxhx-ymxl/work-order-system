package com.workorder.agent;

import java.util.Set;

/**
 * 首版支持的问题类型与"调查完成"的判据（`docs/AGENT-PLAN.md` §3.1 定稿）。
 *
 * <p><b>边界</b>：这三个业务类型是 **S1 的协议演示用虚构类型**（见 §1 收口结论第 1 条），
 * 不代表业务选题；S2 接入真实工具时必须重新声明，并与委托方补录的 §1 八项对齐。
 *
 * <p><b>完成判据落在这里</b>：必需事实必须被报告引用的证据覆盖；允许未知项"未知也算覆盖"，
 * 但报告必须显式标注未知。判据落在证据的 {@code fact} 字段上，不落在模型的说法上。
 */
public enum AgentProblemType {

    ORDER_STATUS(
            "这单现在到哪一步",
            Set.of("order.exists", "order.status", "order.assignee"),
            Set.of("order.assignee", "order.sla_deadline"),
            "只能陈述证据里登记过的事实；处理人未知时必须写“未分配”，不得推测姓名"),

    TIMEOUT_REASON(
            "为什么超时 / 为什么没人接",
            Set.of("order.exists", "order.status", "order.sla_deadline", "order.alert_count"),
            Set.of("order.alert_count"),
            "只有 sla_deadline 已登记且已过期，才能给“超时原因”类建议，否则只列事实"),

    REASSIGN_HISTORY(
            "被谁处理过 / 转过几手",
            Set.of("order.exists", "order.accept_events"),
            Set.of(),
            "接单记录为空时必须建议“等待指派 / 主管介入”，不得建议“联系处理人”"),

    /** 不属于上面三类：只允许输出"不支持"，不得给出事实性结论。 */
    UNSUPPORTED(
            "不属于首版支持范围",
            Set.of(),
            Set.of(),
            "证据与建议都必须是空数组，只说明不属于首版支持范围");

    private final String description;
    private final Set<String> requiredFacts;
    private final Set<String> allowedUnknownFacts;
    private final String premise;

    AgentProblemType(String description, Set<String> requiredFacts, Set<String> allowedUnknownFacts, String premise) {
        this.description = description;
        this.requiredFacts = requiredFacts;
        this.allowedUnknownFacts = allowedUnknownFacts;
        this.premise = premise;
    }

    public String description() {
        return description;
    }

    /** 必须被报告引用的证据覆盖的事实键。 */
    public Set<String> requiredFacts() {
        return requiredFacts;
    }

    /** 未知也算"已覆盖"的事实键（但报告必须显式标未知）。 */
    public Set<String> allowedUnknownFacts() {
        return allowedUnknownFacts;
    }

    /** 建议前提：不满足时不得给该类建议。 */
    public String premise() {
        return premise;
    }

    /** 按名字解析；未知名字返回 null（由调用方判成校验失败，而不是抛异常中断调查）。 */
    public static AgentProblemType parse(String name) {
        if (name == null) {
            return null;
        }
        for (AgentProblemType type : values()) {
            if (type.name().equalsIgnoreCase(name.trim())) {
                return type;
            }
        }
        return null;
    }
}
