package com.workorder.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.workorder.common.BizException;
import com.workorder.common.PageResult;
import com.workorder.common.vo.UserDetailVO;
import com.workorder.entity.Role;
import com.workorder.entity.User;
import com.workorder.entity.UserRole;
import com.workorder.mapper.RoleMapper;
import com.workorder.mapper.UserMapper;
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
class UserServiceTest {

    @Autowired
    private UserService userService;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private RoleMapper roleMapper;

    @Autowired
    private UserRoleMapper userRoleMapper;

    @Test
    @DisplayName("分配角色——原子事务：删除旧关联+批量插入新关联")
    void testAssignRoles_atomicTransaction() {
        User testUser = getOrCreateTestUser();
        Role handlerRole = roleMapper.selectOne(
                new LambdaQueryWrapper<Role>().eq(Role::getRoleCode, "HANDLER"));
        Role submitterRole = roleMapper.selectOne(
                new LambdaQueryWrapper<Role>().eq(Role::getRoleCode, "SUBMITTER"));

        // 先分配 HANDLER
        userService.assignRoles(testUser.getId(), List.of(handlerRole.getId()));
        List<UserRole> first = userRoleMapper.selectList(
                new LambdaQueryWrapper<UserRole>().eq(UserRole::getUserId, testUser.getId()));
        assertEquals(1, first.size());
        assertEquals(handlerRole.getId(), first.get(0).getRoleId());

        // 重新分配 — 旧角色被删，仅剩 SUBMITTER
        userService.assignRoles(testUser.getId(), List.of(submitterRole.getId()));
        List<UserRole> second = userRoleMapper.selectList(
                new LambdaQueryWrapper<UserRole>().eq(UserRole::getUserId, testUser.getId()));
        assertEquals(1, second.size());
        assertEquals(submitterRole.getId(), second.get(0).getRoleId());
    }

    @Test
    @DisplayName("分配角色——系统中最后一名SYS_ADMIN不可被移除（无头系统防护）")
    void testAssignRoles_adminProtection() {
        User adminUser = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, "admin"));
        assertNotNull(adminUser);

        // 获取 SYS_ADMIN 角色和其他角色
        Role sysAdminRole = roleMapper.selectOne(
                new LambdaQueryWrapper<Role>().eq(Role::getRoleCode, "SYS_ADMIN"));
        assertNotNull(sysAdminRole);

        Role handlerRole = roleMapper.selectOne(
                new LambdaQueryWrapper<Role>().eq(Role::getRoleCode, "HANDLER"));
        assertNotNull(handlerRole);

        // 先移除其他所有用户身上的 SYS_ADMIN 角色，确保 admin 是唯一超管
        List<UserRole> allSysAdminUsers = userRoleMapper.selectList(
                new LambdaQueryWrapper<UserRole>()
                        .eq(UserRole::getRoleId, sysAdminRole.getId())
                        .ne(UserRole::getUserId, adminUser.getId()));
        for (UserRole ur : allSysAdminUsers) {
            userRoleMapper.delete(
                    new LambdaQueryWrapper<UserRole>()
                            .eq(UserRole::getUserId, ur.getUserId())
                            .eq(UserRole::getRoleId, ur.getRoleId()));
        }

        // 尝试将 admin 的角色改为不含 SYS_ADMIN → 应被最后一名超管防护拒绝
        assertThrows(BizException.class,
                () -> userService.assignRoles(adminUser.getId(), List.of(handlerRole.getId())));
    }

    @Test
    @DisplayName("分配角色——用户不存在时抛异常")
    void testAssignRoles_userNotFound() {
        Role handlerRole = roleMapper.selectOne(
                new LambdaQueryWrapper<Role>().eq(Role::getRoleCode, "HANDLER"));
        assertThrows(BizException.class,
                () -> userService.assignRoles(99999L, List.of(handlerRole.getId())));
    }

    @Test
    @DisplayName("获取用户详情——包含角色列表和权限码列表")
    void testGetUserDetail_withRolesAndPermissions() {
        User adminUser = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, "admin"));

        UserDetailVO detail = userService.getUserDetail(adminUser.getId());
        assertNotNull(detail);
        assertEquals("admin", detail.getUsername());
        assertNotNull(detail.getRoles());
        assertFalse(detail.getRoles().isEmpty());
        // admin有SYS_ADMIN角色
        assertTrue(detail.getRoles().stream()
                .anyMatch(r -> "SYS_ADMIN".equals(r.getRoleCode())));
        assertNotNull(detail.getPermCodes());
        assertFalse(detail.getPermCodes().isEmpty());
    }

    @Test
    @DisplayName("用户列表分页——支持username模糊搜索")
    void testListUsers_byUsername() {
        PageResult<UserDetailVO> result = userService.listUsers(1, 10, "admin", null);
        assertNotNull(result);
        // 模糊搜索能命中 admin；其它账号(如 dept_admin 含 'admin')也可能命中，
        // 故不断言恰好 1 条，只断言命中列表包含 admin 且数量非空
        assertTrue(result.getTotal() >= 1);
        assertTrue(result.getRecords().stream()
                .anyMatch(u -> "admin".equals(u.getUsername())));
    }

    @Test
    @DisplayName("用户列表分页——默认分页参数")
    void testListUsers_defaultPagination() {
        PageResult<UserDetailVO> result = userService.listUsers(1, 10, null, null);
        assertNotNull(result);
        assertTrue(result.getTotal() >= 1);
        assertTrue(result.getRecords().size() <= 10);
    }

    @Test
    @DisplayName("获取用户详情——用户不存在时抛异常")
    void testGetUserDetail_notFound() {
        assertThrows(BizException.class, () -> userService.getUserDetail(99999L));
    }

    private User getOrCreateTestUser() {
        User existing = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, "test_assign"));
        if (existing != null) {
            return existing;
        }
        User user = new User();
        user.setUsername("test_assign");
        user.setPassword("$2a$encoded");
        user.setStatus(1);
        userMapper.insert(user);
        return user;
    }
}
