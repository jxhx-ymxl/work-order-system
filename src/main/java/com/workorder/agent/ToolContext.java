package com.workorder.agent;

/**
 * 一次调查的**授权上下文**（`docs/AGENT-PLAN.md` §11-2，裁决见 `docs/DECISIONS.md` D79）。
 *
 * <p><b>取值时点</b>：在**受理调查的 Web 请求**里一次性快照、之后**不可变**（record 天然如此）。
 * 执行侧（S4 的消费 / 调度线程）**不重新取值**——那里没有 Sa-Token 会话，想取也取不到。
 *
 * <p><b>范围只由部门表达</b>：{@code callerDeptId} 是**唯一**的范围载体——`docs/AGENT-PLAN.md` §1 第二题裁定
 * "统一只调查本部门用户提交的工单，升级状态不扩大可见范围"，因此**不接受任何"可跨部门"标志**
 * （2026-10-06 C2 收口：原 {@code canSeeAllDepts} 字段已删除）。
 * 部门取值由**受理层**用与业务接口**同一套**权限判定得出；工具与 agent 侧**不得**另写
 * {@code isAdmin()} 之类的本地判断。若将来 §1 改为允许跨部门，必须走 D 条目显式扩宽本记录。
 *
 * <p><b>工具的硬性约束</b>：{@link AgentTool} 实现**不得**读 Sa-Token（{@code StpUtil}）、
 * **不得**依赖 {@code RequestContextHolder}、不得读取任何"当前请求"的隐式状态；
 * 需要身份就只用本对象里的字段。
 *
 * <p><b>承诺边界</b>：快照会过时（受理后调岗 / 撤权，本次调查仍按旧权限跑完）——
 * S2 只承诺"受理时正确"，不承诺"执行期间实时"（后者由 §3.3 的 {@code PERMISSION_REVOKED} 在 S4 覆盖）。
 *
 * <p><b>构造期不变量</b>（§11-2：用 {@code IllegalArgumentException} 拒绝，**不新开失败码**）：
 * <ul>
 *   <li>{@code investigationId} / {@code callerUserId} / {@code callerDeptId} 三者都不得为
 *       {@code null} 或空白——**缺部门就是"没有过滤条件"**（= 越权），故不接受"无部门"的上下文。</li>
 * </ul>
 * 标识字段首版按**不透明字符串**处理（类型属实现细节，S2 可定，但不变量不得放宽）。
 */
public record ToolContext(
        String investigationId,
        String callerUserId,
        String callerDeptId) {

    public ToolContext {
        requireNonBlank(investigationId, "investigationId");
        requireNonBlank(callerUserId, "callerUserId");
        requireNonBlank(callerDeptId, "callerDeptId");
    }

    /** 限定在某个部门内。 */
    public static ToolContext ofDepartment(String investigationId, String callerUserId, String callerDeptId) {
        return new ToolContext(investigationId, callerUserId, callerDeptId);
    }

    private static void requireNonBlank(String value, String name) {
        if (isBlank(value)) {
            throw new IllegalArgumentException(name + " 不得为 null 或空白");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
