package com.workorder.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.workorder.common.BizException;
import com.workorder.common.dto.RoleCreateReq;
import com.workorder.common.dto.RolePermissionAssignReq;
import com.workorder.common.dto.RoleUpdateReq;
import com.workorder.common.vo.RoleVO;
import com.workorder.entity.Role;
import com.workorder.entity.RolePermission;
import com.workorder.entity.UserRole;
import com.workorder.mapper.RoleMapper;
import com.workorder.mapper.RolePermissionMapper;
import com.workorder.mapper.UserRoleMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class RoleServiceTest {

    @Autowired
    private RoleService roleService;

    @Autowired
    private RoleMapper roleMapper;

    @Autowired
    private RolePermissionMapper rolePermissionMapper;

    @Autowired
    private UserRoleMapper userRoleMapper;

    @Test
    @DisplayName("创建角色——校验roleCode唯一性")
    void testCreateRole_success() {
        RoleCreateReq req = buildCreateReq("TEST_ROLE", "测试角色", "备注");
        roleService.createRole(req);

        List<Role> roles = roleMapper.selectList(
                new LambdaQueryWrapper<Role>().eq(Role::getRoleCode, "TEST_ROLE"));
        assertEquals(1, roles.size());
        assertEquals("测试角色", roles.get(0).getRoleName());
    }

    @Test
    @DisplayName("创建角色——roleCode重复时抛异常")
    void testCreateRole_duplicateCode() {
        RoleCreateReq req = buildCreateReq("SUBMITTER", "重复编码", null);
        assertThrows(BizException.class, () -> roleService.createRole(req));
    }

    @Test
    @DisplayName("更新角色——角色不存在时抛异常")
    void testUpdateRole_notFound() {
        RoleUpdateReq req = new RoleUpdateReq();
        req.setRoleName("不存在");
        assertThrows(BizException.class, () -> roleService.updateRole(99999L, req));
    }

    @Test
    @DisplayName("更新角色——正常更新")
    void testUpdateRole_success() {
        // 先创建
        RoleCreateReq createReq = buildCreateReq("UPDATE_TEST", "旧名", null);
        Role created = roleService.createRole(createReq);

        RoleUpdateReq updateReq = new RoleUpdateReq();
        updateReq.setRoleName("新名称");
        updateReq.setRemark("新备注");
        roleService.updateRole(created.getId(), updateReq);

        Role updated = roleMapper.selectById(created.getId());
        assertEquals("新名称", updated.getRoleName());
        assertEquals("新备注", updated.getRemark());
    }

    @Test
    @DisplayName("删除角色——种子数据禁止删除")
    void testDeleteRole_protectedSeedData() {
        Role seedRole = roleMapper.selectOne(
                new LambdaQueryWrapper<Role>().eq(Role::getRoleCode, "SYS_ADMIN"));
        assertNotNull(seedRole);

        assertThrows(BizException.class, () -> roleService.deleteRole(seedRole.getId()));
    }

    @Test
    @DisplayName("删除角色——有用户关联时禁止删除")
    void testDeleteRole_withUsers() {
        // admin用户绑定了SYS_ADMIN角色，先查出一个有用户的角色
        UserRole userRole = userRoleMapper.selectList(null).stream().findFirst().orElse(null);
        if (userRole == null) {
            // 如果没有用户角色关联，跳过此测试
            return;
        }
        assertThrows(BizException.class, () -> roleService.deleteRole(userRole.getRoleId()));
    }

    @Test
    @DisplayName("分配权限——原子事务：先删旧关联再批量插入新关联")
    void testAssignPermissions_atomicTransaction() {
        // 创建新角色
        RoleCreateReq createReq = buildCreateReq("ATOMIC_ROLE", "原子测试角色", null);
        Role role = roleService.createRole(createReq);

        // 第一次分配权限
        RolePermissionAssignReq assignReq = new RolePermissionAssignReq();
        assignReq.setPermIds(List.of(1L, 2L, 3L));
        roleService.assignPermissions(role.getId(), assignReq.getPermIds());

        List<RolePermission> firstResult = rolePermissionMapper.selectList(
                new LambdaQueryWrapper<RolePermission>().eq(RolePermission::getRoleId, role.getId()));
        assertEquals(3, firstResult.size());

        // 第二次分配（替换旧权限）
        RolePermissionAssignReq reassignReq = new RolePermissionAssignReq();
        reassignReq.setPermIds(List.of(4L, 5L));
        roleService.assignPermissions(role.getId(), reassignReq.getPermIds());

        List<RolePermission> secondResult = rolePermissionMapper.selectList(
                new LambdaQueryWrapper<RolePermission>().eq(RolePermission::getRoleId, role.getId()));
        assertEquals(2, secondResult.size());
        List<Long> permIds = secondResult.stream().map(RolePermission::getPermissionId).toList();
        assertTrue(permIds.contains(4L));
        assertTrue(permIds.contains(5L));
        assertFalse(permIds.contains(1L));  // 旧权限已删除
    }

    @Test
    @DisplayName("查询角色详情——返回角色信息+已分配权限ID列表")
    void testGetRoleDetail_withPermIds() {
        // 为HANDLER角色查详情
        Role handlerRole = roleMapper.selectOne(
                new LambdaQueryWrapper<Role>().eq(Role::getRoleCode, "HANDLER"));
        assertNotNull(handlerRole);

        // 先分配一些权限
        roleService.assignPermissions(handlerRole.getId(), List.of(1L, 2L));

        RoleVO detail = roleService.getRoleDetail(handlerRole.getId());
        assertNotNull(detail);
        assertEquals("HANDLER", detail.getRoleCode());
        assertNotNull(detail.getPermIds());
        assertTrue(detail.getPermIds().contains(1L));
        assertTrue(detail.getPermIds().contains(2L));
    }

    @Test
    @DisplayName("查询全量角色列表")
    void testListRoles() {
        List<Role> roles = roleService.listRoles();
        assertFalse(roles.isEmpty());
        // 种子数据至少有4条
        assertTrue(roles.size() >= 4);
    }

    private RoleCreateReq buildCreateReq(String roleCode, String roleName, String remark) {
        RoleCreateReq req = new RoleCreateReq();
        req.setRoleCode(roleCode);
        req.setRoleName(roleName);
        req.setRemark(remark);
        return req;
    }
}
