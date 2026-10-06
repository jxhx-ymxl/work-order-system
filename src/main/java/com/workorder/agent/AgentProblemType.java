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
            // D83：allowedUnknown **只留真有未知来源的事实**——assignee 对应"有 id 但 t_user 查不到用户"；
            // sla_deadline 的 NULL 是**已知的"无 SLA"**（空值走"已知值"），原条目已不可达，删除。
            Set.of("order.assignee"),
            "只能陈述证据里登记过的事实；处理人未知时必须写“未分配”，不得推测姓名"),

    TIMEOUT_SITUATION(
            "超时情况调查（已核实什么 / 还缺什么 / 下一步找谁核实）",
            // §3.1 按数据能力对齐（D82）：alert_count **移出必需**（映射表已证无列、只能近似，不配当必需），
            // 但**保留在 allowedUnknown**——否则工具返回的"显式未知"一旦被引用就会撞
            // validateReport 的"该类型不允许未知"分支；工具返回或不返回该事实都合法。
            Set.of("order.exists", "order.status", "order.sla_deadline"),
            // D83：assignee 在这里也可能是"有 id 查不到用户"（真正的未知），所以一并允许；
            // alert_count 是"无来源"（查不到），保留。
            Set.of("order.assignee", "order.alert_count"),
            "不得给出任何原因性结论：只能交付已核实事实、证据缺口与下一步核实建议；"
                    + "sla_deadline 未登记或未过期时，不得断言“已超时”"),

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
