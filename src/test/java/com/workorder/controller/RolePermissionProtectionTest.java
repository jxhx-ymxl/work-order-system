package com.workorder.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.workorder.common.BizException;
import com.workorder.common.ErrorCode;
import com.workorder.entity.Role;
import com.workorder.entity.RolePermission;
import com.workorder.mapper.RoleMapper;
import com.workorder.mapper.RolePermissionMapper;
import com.workorder.service.RoleService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * **内置超管角色的权限集不可编辑**（2026-10-08 自锁事故的回归用例，见 `docs/DECISIONS.md` D110）。
 *
 * <p>判据三条，缺一不可：
 * ① 业务码 **400**（不是"命令没报错"，也不是 HTTP 状态）；
 * ② **库未变**——只断言"报错了"不够：本缺陷的形态就是"**先删了再失败**"，
 *    所以必须断言"调用前后该角色的权限条数一致"；
 * ③ **对照**：非种子角色仍能正常改权限（别把功能一起锁死）。
 *
 * <p>用**种子里就有的** admin（id=1、SYS_ADMIN）登录去做 HTTP 用例——不新建账号：
 * 权限查询看不到测试事务里未提交的用户行（同轮在部门用例上踩过，见 D109）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("角色权限保护：超管不可改权限（400 且库未变）+ 非种子角色仍可改")
class RolePermissionProtectionTest {

    private static final long SYS_ADMIN_ROLE_ID = 1L;
    private static final long ADMIN_USER_ID = 1L;
    private static final long SOME_PERM_ID = 2L;
    private static final long ANOTHER_PERM_ID = 3L;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private RoleService roleService;
    @Autowired
    private RoleMapper roleMapper;
    @Autowired
    private RolePermissionMapper rolePermissionMapper;

    private String adminToken() {
        StpUtil.login(ADMIN_USER_ID);
        return StpUtil.getTokenValue();
    }

    private long countPerms(long roleId) {
        return rolePermissionMapper.selectCount(
                new LambdaQueryWrapper<RolePermission>().eq(RolePermission::getRoleId, roleId));
    }

    // ---------- ① + ② 服务层：拒绝，且**没有先删** ----------

    @Test
    @DisplayName("① 服务层：给 SYS_ADMIN 赋权限 → 业务码 400，且**权限条数不变**（不是先删再拦）")
    void sysAdminPermissionsAreRejectedAndNothingIsDeleted() {
        long before = countPerms(SYS_ADMIN_ROLE_ID);
        assertTrue(before > 0, "前置：种子库里 SYS_ADMIN 本来就有权限绑定");

        BizException ex = assertThrows(BizException.class,
                () -> roleService.assignPermissions(SYS_ADMIN_ROLE_ID, List.of(SOME_PERM_ID)));

        assertEquals(ErrorCode.BAD_REQUEST.getCode(), ex.getErrorCode().getCode(), "业务码必须是 400");
        assertEquals("内置超管角色的权限集不可编辑", ex.getMessage());
        assertEquals(before, countPerms(SYS_ADMIN_ROLE_ID),
                "**库未变**：拒绝必须发生在 delete 之前（本缺陷的形态就是先删再失败）");
    }

    @Test
    @DisplayName("② HTTP 层：PUT /api/admin/roles/1/permissions → HTTP 200 + body.code=400，且权限条数不变")
    void sysAdminPermissionsAreRejectedOverHttp() throws Exception {
        long before = countPerms(SYS_ADMIN_ROLE_ID);

        mockMvc.perform(put("/api/admin/roles/" + SYS_ADMIN_ROLE_ID + "/permissions")
                        .header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content(("{\"permIds\":[" + SOME_PERM_ID + "]}").getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk())                      // 本项目约定：业务失败也走 HTTP 200
                .andExpect(jsonPath("$.code").value(400));

        assertEquals(before, countPerms(SYS_ADMIN_ROLE_ID), "HTTP 这条路也不能删掉任何一行");
    }

    // ---------- ③ 对照：别把功能锁死 ----------

    @Test
    @DisplayName("③ 对照：非种子角色仍可正常改权限（先 1 条、再 2 条，条数如实变化）")
    void nonSeedRolePermissionsCanStillBeEdited() {
        Role role = new Role();
        role.setRoleCode("TEST_ROLE_" + (System.nanoTime() % 1_000_000));
        role.setRoleName("回归用临时角色");
        role.setRemark("RolePermissionProtectionTest");
        roleMapper.insert(role);

        roleService.assignPermissions(role.getId(), List.of(SOME_PERM_ID));
        assertEquals(1L, countPerms(role.getId()));

        roleService.assignPermissions(role.getId(), List.of(SOME_PERM_ID, ANOTHER_PERM_ID));
        assertEquals(2L, countPerms(role.getId()), "非种子角色仍然可以改权限（保护没扩大到其它角色）");
    }
}
