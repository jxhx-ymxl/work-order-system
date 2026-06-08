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
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final PermissionMapper permissionMapper;
    private final PasswordEncoder passwordEncoder;

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
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在: id=" + userId);
        }
        if ("admin".equals(user.getUsername()) && roleIds.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "管理员角色不允许被清空");
        }

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

            records.add(vo);
        }

        return PageResult.of(userPage.getTotal(), userPage.getPages(), userPage.getCurrent(), records);
    }

    @Override
    public UserDetailVO getUserDetail(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在: id=" + userId);
        }

        UserDetailVO vo = new UserDetailVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setPhone(user.getPhone());
        vo.setDeptId(user.getDeptId());
        vo.setStatus(user.getStatus());
        vo.setCreatedAt(user.getCreatedAt());

        List<Role> roles = getRolesByUserId(userId);
        vo.setRoles(roles.stream()
                .map(r -> {
                    UserDetailVO.RoleInfo ri = new UserDetailVO.RoleInfo();
                    ri.setRoleCode(r.getRoleCode());
                    ri.setRoleName(r.getRoleName());
                    return ri;
                }).toList());

        List<String> permCodes = getPermCodesByUserId(userId);
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
