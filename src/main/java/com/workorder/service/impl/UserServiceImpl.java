package com.workorder.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.workorder.common.BizException;
import com.workorder.common.ErrorCode;
import com.workorder.common.PageResult;
import com.workorder.common.dto.LoginReq;
import com.workorder.common.dto.RegisterReq;
import com.workorder.common.vo.LoginVO;
import com.workorder.common.vo.UserDetailVO;
import com.workorder.entity.Permission;
import com.workorder.entity.Role;
import com.workorder.entity.RolePermission;
import com.workorder.entity.User;
import com.workorder.entity.UserRole;
import com.workorder.mapper.PermissionMapper;
import com.workorder.mapper.RoleMapper;
import com.workorder.mapper.RolePermissionMapper;
import com.workorder.mapper.UserMapper;
import com.workorder.mapper.UserRoleMapper;
import com.workorder.service.UserService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final PermissionMapper permissionMapper;
    private final PasswordEncoder passwordEncoder;

    /**
     * 启动自愈：确保系统始终存在至少一名活着的超级管理员。
     *
     * 无论何种原因导致 ID=1 的造物主账号丢失 SYS_ADMIN 角色
     * （数据库被手动篡改、错误的批量操作、历史数据迁移遗漏等），
     * 应用每次启动时都会强制修复。
     */
    @PostConstruct
    public void ensureAdminSurvival() {
        try {
            Role sysAdminRole = roleMapper.selectOne(
                    new LambdaQueryWrapper<Role>().eq(Role::getRoleCode, "SYS_ADMIN"));
            if (sysAdminRole == null) {
                log.warn("[启动自愈] SYS_ADMIN 角色不存在，跳过");
                return;
            }

            Long count = userRoleMapper.selectCount(
                    new LambdaQueryWrapper<UserRole>()
                            .eq(UserRole::getUserId, 1L)
                            .eq(UserRole::getRoleId, sysAdminRole.getId()));
            if (count == 0) {
                UserRole ur = new UserRole();
                ur.setUserId(1L);
                ur.setRoleId(sysAdminRole.getId());
                userRoleMapper.insert(ur);
                log.warn("[启动自愈] 检测到造物主账号 (ID=1) 丢失 SYS_ADMIN 角色——已自动修复！");
            }
        } catch (Exception e) {
            log.error("[启动自愈] 修复造物主账号失败，系统可能处于无管理员状态！", e);
        }
    }

    @Override
    public UserDetailVO getUserDetailByUsername(String username) {
        User user = getByUsername(username);
        return buildUserDetailVO(user);
    }

    @Override
    public User getByUsername(String username) {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (user == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在: " + username);
        }
        return user;
    }

    @Override
    public LoginVO login(LoginReq req) {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, req.getUsername()));
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new BizException(ErrorCode.FORBIDDEN, "账号已被禁用");
        }
        if (!passwordEncoder.matches(req.getPassword(), user.getPassword())) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }
        StpUtil.login(user.getId());
        return new LoginVO(StpUtil.getTokenValue(), user.getId(), user.getUsername(), user.getPhone());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void register(RegisterReq req) {
        Long count = userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getUsername, req.getUsername()));
        if (count > 0) {
            throw new BizException(ErrorCode.CONFLICT, "用户名已存在");
        }

        User user = new User();
        user.setUsername(req.getUsername());
        user.setPassword(passwordEncoder.encode(req.getPassword()));
        user.setPhone(req.getPhone());
        user.setDeptId(req.getDeptId());
        user.setStatus(1);
        userMapper.insert(user);

        Role submitterRole = roleMapper.selectOne(
                new LambdaQueryWrapper<Role>().eq(Role::getRoleCode, "SUBMITTER"));
        if (submitterRole != null) {
            UserRole userRole = new UserRole();
            userRole.setUserId(user.getId());
            userRole.setRoleId(submitterRole.getId());
            userRoleMapper.insert(userRole);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assignRoles(Long userId, List<Long> roleIds) {
        // ──────────────────────────────────────────────
        // 安全防线：外科手术级拦截（不阻断恢复性操作）
        // ──────────────────────────────────────────────

        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在: id=" + userId);
        }

        // 获取当前操作人（无人登录时跳过——如单元测试场景）
        Long operatorId = null;
        try {
            operatorId = StpUtil.getLoginIdAsLong();
        } catch (Exception ignored) {
            // 无人登录场景
        }

        // 获取 SYS_ADMIN 角色 ID
        Role sysAdminRole = roleMapper.selectOne(
                new LambdaQueryWrapper<Role>().eq(Role::getRoleCode, "SYS_ADMIN"));
        Long sysAdminRoleId = sysAdminRole != null ? sysAdminRole.getId() : null;

        // 查询目标用户当前是否拥有 SYS_ADMIN 角色
        boolean targetHasSysAdmin = false;
        if (sysAdminRoleId != null) {
            Long sysAdminCount = userRoleMapper.selectCount(
                    new LambdaQueryWrapper<UserRole>()
                            .eq(UserRole::getUserId, userId)
                            .eq(UserRole::getRoleId, sysAdminRoleId));
            targetHasSysAdmin = sysAdminCount > 0;
        }

        boolean newRolesIncludeSysAdmin = sysAdminRoleId != null && roleIds.contains(sysAdminRoleId);

        // ── 防线 1：造物主 SYS_ADMIN 不可被移除 ──
        // ID=1 是种子超管——允许为其增加角色，但不允许移除其 SYS_ADMIN
        if (userId == 1L && targetHasSysAdmin && !newRolesIncludeSysAdmin) {
            throw new BizException(ErrorCode.FORBIDDEN,
                    "越权阻断：系统内置造物主账号 (ID=1) 的系统管理员角色不可移除！");
        }

        // ── 防线 2：防自杀——禁止操作人移除自己的 SYS_ADMIN ──
        if (operatorId != null && operatorId.equals(userId) && targetHasSysAdmin && !newRolesIncludeSysAdmin) {
            throw new BizException(ErrorCode.FORBIDDEN,
                    "安全阻断：不允许移除自己的系统管理员角色。如需移除此角色，请让其他管理员操作。");
        }

        // ── 防线 3：无头系统防护——禁止移除系统中最后一名 SYS_ADMIN ──
        if (targetHasSysAdmin && !newRolesIncludeSysAdmin) {
            Long totalSysAdminUsers = userRoleMapper.selectCount(
                    new LambdaQueryWrapper<UserRole>()
                            .eq(UserRole::getRoleId, sysAdminRoleId));
            if (totalSysAdminUsers <= 1) {
                throw new BizException(ErrorCode.FORBIDDEN,
                        "无法移除此用户的系统管理员角色：系统中至少需要保留一名系统管理员。");
            }
        }

        // ── 防线 4：防自毁——禁止清空自己的所有角色 ──
        if (operatorId != null && operatorId.equals(userId) && roleIds.isEmpty()) {
            throw new BizException(ErrorCode.FORBIDDEN,
                    "安全阻断：不允许清空自己的所有角色！");
        }

        // 清空旧角色并分配新角色
        userRoleMapper.delete(
                new LambdaQueryWrapper<UserRole>().eq(UserRole::getUserId, userId));

        for (Long roleId : roleIds) {
            UserRole ur = new UserRole();
            ur.setUserId(userId);
            ur.setRoleId(roleId);
            userRoleMapper.insert(ur);
        }
    }

    @Override
    public PageResult<UserDetailVO> listUsers(Integer page, Integer size, String username, Long deptId) {
        if (page == null || page < 1) page = 1;
        if (size == null || size < 1) size = 10;
        if (size > 100) size = 100;

        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        if (username != null && !username.isBlank()) {
            wrapper.like(User::getUsername, username);
        }
        if (deptId != null) {
            wrapper.eq(User::getDeptId, deptId);
        }
        wrapper.orderByAsc(User::getId);

        IPage<User> userPage = userMapper.selectPage(new Page<>(page, size), wrapper);

        List<UserDetailVO> records = new ArrayList<>();
        for (User user : userPage.getRecords()) {
            records.add(buildUserDetailVO(user));
        }

        return PageResult.of(userPage.getTotal(), userPage.getPages(), userPage.getCurrent(), records);
    }

    @Override
    public UserDetailVO getUserDetail(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在: id=" + userId);
        }
        return buildUserDetailVO(user);
    }

    /** 将 User 实体组装为含角色+权限码的 UserDetailVO */
    private UserDetailVO buildUserDetailVO(User user) {
        UserDetailVO vo = new UserDetailVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setPhone(user.getPhone());
        vo.setDeptId(user.getDeptId());
        vo.setStatus(user.getStatus());
        vo.setCreatedAt(user.getCreatedAt());

        List<Role> roles = getRolesByUserId(user.getId());
        vo.setRoles(roles.stream()
                .map(r -> {
                    UserDetailVO.RoleInfo ri = new UserDetailVO.RoleInfo();
                    ri.setRoleCode(r.getRoleCode());
                    ri.setRoleName(r.getRoleName());
                    return ri;
                }).toList());

        List<String> permCodes = getPermCodesByUserId(user.getId());
        vo.setPermCodes(permCodes);

        return vo;
    }

    private List<Role> getRolesByUserId(Long userId) {
        List<UserRole> userRoles = userRoleMapper.selectList(
                new LambdaQueryWrapper<UserRole>().eq(UserRole::getUserId, userId));
        if (userRoles.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> roleIds = userRoles.stream().map(UserRole::getRoleId).toList();
        return roleMapper.selectList(
                new LambdaQueryWrapper<Role>().in(Role::getId, roleIds));
    }

    private List<String> getPermCodesByUserId(Long userId) {
        List<UserRole> userRoles = userRoleMapper.selectList(
                new LambdaQueryWrapper<UserRole>().eq(UserRole::getUserId, userId));
        if (userRoles.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> roleIds = userRoles.stream().map(UserRole::getRoleId).toList();

        List<RolePermission> rps = rolePermissionMapper.selectList(
                new LambdaQueryWrapper<RolePermission>().in(RolePermission::getRoleId, roleIds));
        if (rps.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> permIds = rps.stream().map(RolePermission::getPermissionId).distinct().toList();

        return permissionMapper.selectList(
                        new LambdaQueryWrapper<Permission>().in(Permission::getId, permIds))
                .stream().map(Permission::getPermCode).toList();
    }
}
