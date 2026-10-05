package com.workorder.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
                () -> new ToolContext(null, "1", "D-1", false));
        assertThrows(IllegalArgumentException.class,
                () -> new ToolContext("   ", "1", "D-1", false));
    }

    @Test
    @DisplayName("callerUserId 为空 / 空白 → 拒绝")
    void callerUserIdMustNotBeBlank() {
        assertThrows(IllegalArgumentException.class,
                () -> new ToolContext("inv-1", null, "D-1", false));
        assertThrows(IllegalArgumentException.class,
                () -> new ToolContext("inv-1", "", "D-1", false));
    }

    @Test
    @DisplayName("禁止 canSeeAllDepts=false 与 callerDeptId 为空同时出现（否则退化成没有过滤条件）")
    void departmentScopedContextMustCarryADepartment() {
        assertThrows(IllegalArgumentException.class,
                () -> new ToolContext("inv-1", "1", null, false));
        assertThrows(IllegalArgumentException.class,
                () -> new ToolContext("inv-1", "1", "  ", false));
    }

    @Test
    @DisplayName("canSeeAllDepts=true 允许不带部门（显式跨部门）")
    void allDepartmentsContextMayOmitDepartment() {
        ToolContext ctx = ToolContext.allDepartments("inv-1", "1");

        assertEquals("inv-1", ctx.investigationId());
        assertEquals("1", ctx.callerUserId());
        assertNull(ctx.callerDeptId());
        org.junit.jupiter.api.Assertions.assertTrue(ctx.canSeeAllDepts());
    }

    @Test
    @DisplayName("部门上下文：四个字段原样保留")
    void departmentContextKeepsAllFields() {
        ToolContext ctx = ToolContext.ofDepartment("inv-9", "42", "D-7");

        assertEquals("inv-9", ctx.investigationId());
        assertEquals("42", ctx.callerUserId());
        assertEquals("D-7", ctx.callerDeptId());
        org.junit.jupiter.api.Assertions.assertFalse(ctx.canSeeAllDepts());
    }
}
