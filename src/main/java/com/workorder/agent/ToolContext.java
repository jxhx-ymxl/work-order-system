package com.workorder.agent;

/**
 * 一次调查的**授权上下文**（`docs/AGENT-PLAN.md` §11-2，裁决见 `docs/DECISIONS.md` D79）。
 *
 * <p><b>取值时点</b>：在**受理调查的 Web 请求**里一次性快照、之后**不可变**（record 天然如此）。
 * 执行侧（S4 的消费 / 调度线程）**不重新取值**——那里没有 Sa-Token 会话，想取也取不到。
 *
 * <p><b>判定方</b>：{@code canSeeAllDepts} 由**受理层**用与业务接口**同一套**权限判定得出
 * （与"管理员可见全部"同源）；工具与 agent 侧**不得**另写 {@code isAdmin()} 之类的本地判断。
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
 *   <li>{@code investigationId} / {@code callerUserId} 不得为 {@code null} 或空白；</li>
 *   <li><b>禁止 {@code canSeeAllDepts=false} 与 {@code callerDeptId} 为空同时出现</b>——
 *       那会让"按部门过滤"退化成"没有过滤条件"（= 越权）；</li>
 *   <li>{@code canSeeAllDepts=true} 时 {@code callerDeptId} 允许为空（显式允许跨部门）。</li>
 * </ul>
 * 标识字段首版按**不透明字符串**处理（类型属实现细节，S2 可定，但不变量不得放宽）。
 */
public record ToolContext(
        String investigationId,
        String callerUserId,
        String callerDeptId,
        boolean canSeeAllDepts) {

    public ToolContext {
        requireNonBlank(investigationId, "investigationId");
        requireNonBlank(callerUserId, "callerUserId");
        if (!canSeeAllDepts && isBlank(callerDeptId)) {
            throw new IllegalArgumentException(
                    "禁止 canSeeAllDepts=false 与 callerDeptId 为空同时出现："
                            + "那会让“按部门过滤”退化成“没有过滤条件”（= 越权）；要么有部门，要么显式允许跨部门");
        }
    }

    /** 显式允许跨部门（管理员一类）——判定必须来自受理层，这里只是承载。 */
    public static ToolContext allDepartments(String investigationId, String callerUserId) {
        return new ToolContext(investigationId, callerUserId, null, true);
    }

    /** 限定在某个部门内。 */
    public static ToolContext ofDepartment(String investigationId, String callerUserId, String callerDeptId) {
        return new ToolContext(investigationId, callerUserId, callerDeptId, false);
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
