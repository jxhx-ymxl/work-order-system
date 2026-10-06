package com.workorder.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 授权口径的**构造期不变量**（`docs/AGENT-PLAN.md` §11-2，裁决见 `docs/DECISIONS.md` D79）。
 *
 * <p>为什么用构造期而不是运行期判失败：**越权的形状要在对象造出来之前就消失**，
 * 这样就没有"跑到一半才发现越权"的路径。
 */
@DisplayName("ToolContext 的构造期不变量")
class ToolContextTest {

    @Test
    @DisplayName("investigationId 为空 / 空白 → 拒绝")
    void investigationIdMustNotBeBlank() {
        assertThrows(IllegalArgumentException.class,
                () -> new ToolContext(null, "1", "D-1"));
        assertThrows(IllegalArgumentException.class,
                () -> new ToolContext("   ", "1", "D-1"));
    }

    @Test
    @DisplayName("callerUserId 为空 / 空白 → 拒绝")
    void callerUserIdMustNotBeBlank() {
        assertThrows(IllegalArgumentException.class,
                () -> new ToolContext("inv-1", null, "D-1"));
        assertThrows(IllegalArgumentException.class,
                () -> new ToolContext("inv-1", "", "D-1"));
    }

    @Test
    @DisplayName("callerDeptId 为空 / 空白 → 拒绝（§1 未开跨部门口：缺部门就是没有过滤条件）")
    void callerDeptIdMustNotBeBlank() {
        assertThrows(IllegalArgumentException.class,
                () -> new ToolContext("inv-1", "1", null));
        assertThrows(IllegalArgumentException.class,
                () -> new ToolContext("inv-1", "1", "  "));
    }

    @Test
    @DisplayName("部门上下文：三个字段原样保留")
    void departmentContextKeepsAllFields() {
        ToolContext ctx = ToolContext.ofDepartment("inv-9", "42", "D-7");

        assertEquals("inv-9", ctx.investigationId());
        assertEquals("42", ctx.callerUserId());
        assertEquals("D-7", ctx.callerDeptId());
    }
}
