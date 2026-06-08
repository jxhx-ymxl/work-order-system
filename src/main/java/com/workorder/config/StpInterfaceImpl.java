package com.workorder.config;

import cn.dev33.satoken.stp.StpInterface;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.workorder.entity.Permission;
import com.workorder.entity.Role;
import com.workorder.entity.RolePermission;
import com.workorder.entity.UserRole;
import com.workorder.mapper.PermissionMapper;
import com.workorder.mapper.RoleMapper;
import com.workorder.mapper.RolePermissionMapper;
import com.workorder.mapper.UserRoleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

@Component
@RequiredArgsConstructor
public class StpInterfaceImpl implements StpInterface {

    private final UserRoleMapper userRoleMapper;
    private final RoleMapper roleMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final PermissionMapper permissionMapper;

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        Long userId = Long.valueOf(loginId.toString());

        List<UserRole> userRoles = userRoleMapper.selectList(
                new LambdaQueryWrapper<UserRole>().eq(UserRole::getUserId, userId));
        if (userRoles.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> roleIds = userRoles.stream()
                .map(UserRole::getRoleId)
                .toList();

        List<Role> roles = roleMapper.selectList(
                new LambdaQueryWrapper<Role>().in(Role::getId, roleIds));
        return roles.stream()
                .map(Role::getRoleCode)
                .toList();
    }

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        Long userId = Long.valueOf(loginId.toString());

        List<UserRole> userRoles = userRoleMapper.selectList(
                new LambdaQueryWrapper<UserRole>().eq(UserRole::getUserId, userId));
        if (userRoles.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> roleIds = userRoles.stream()
                .map(UserRole::getRoleId)
                .toList();

        List<RolePermission> rolePermissions = rolePermissionMapper.selectList(
                new LambdaQueryWrapper<RolePermission>().in(RolePermission::getRoleId, roleIds));
        if (rolePermissions.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> permIds = rolePermissions.stream()
                .map(RolePermission::getPermissionId)
                .distinct()
                .toList();

        List<Permission> permissions = permissionMapper.selectList(
                new LambdaQueryWrapper<Permission>().in(Permission::getId, permIds));
        return permissions.stream()
                .map(Permission::getPermCode)
                .toList();
    }
}
