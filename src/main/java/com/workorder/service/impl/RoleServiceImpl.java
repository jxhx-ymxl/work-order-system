package com.workorder.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.workorder.common.BizException;
import com.workorder.common.ErrorCode;
import com.workorder.common.dto.RoleCreateReq;
import com.workorder.common.dto.RoleUpdateReq;
import com.workorder.common.vo.RoleVO;
import com.workorder.entity.Role;
import com.workorder.entity.RolePermission;
import com.workorder.entity.UserRole;
import com.workorder.mapper.RoleMapper;
import com.workorder.mapper.RolePermissionMapper;
import com.workorder.mapper.UserRoleMapper;
import com.workorder.service.RoleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class RoleServiceImpl implements RoleService {

    private static final Set<String> PROTECTED_ROLE_CODES = Set.of("SUBMITTER", "HANDLER", "DEPT_ADMIN", "SYS_ADMIN");

    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final RolePermissionMapper rolePermissionMapper;

    @Override
    public Role createRole(RoleCreateReq req) {
        boolean exists = roleMapper.exists(
                new LambdaQueryWrapper<Role>().eq(Role::getRoleCode, req.getRoleCode()));
        if (exists) {
            throw new BizException(ErrorCode.CONFLICT, "角色编码已存在: " + req.getRoleCode());
        }

        Role role = new Role();
        role.setRoleCode(req.getRoleCode());
        role.setRoleName(req.getRoleName());
        role.setRemark(req.getRemark());
        roleMapper.insert(role);
        log.info("创建角色成功: code={}, id={}", role.getRoleCode(), role.getId());
        return role;
    }

    @Override
    public Role updateRole(Long id, RoleUpdateReq req) {
        Role role = roleMapper.selectById(id);
        if (role == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "角色不存在: id=" + id);
        }
        role.setRoleName(req.getRoleName());
        role.setRemark(req.getRemark());
        roleMapper.updateById(role);
        log.info("更新角色成功: id={}, name={}", id, req.getRoleName());
        return role;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteRole(Long id) {
        Role role = roleMapper.selectById(id);
        if (role == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "角色不存在: id=" + id);
        }
        if (PROTECTED_ROLE_CODES.contains(role.getRoleCode())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "系统内置角色不允许删除: " + role.getRoleCode());
        }

        Long userCount = userRoleMapper.selectCount(
                new LambdaQueryWrapper<UserRole>().eq(UserRole::getRoleId, id));
        if (userCount > 0) {
            throw new BizException(ErrorCode.CONFLICT,
                    "该角色下仍有 " + userCount + " 个用户关联，请先解除绑定");
        }

        rolePermissionMapper.delete(
                new LambdaQueryWrapper<RolePermission>().eq(RolePermission::getRoleId, id));
        roleMapper.deleteById(id);
        log.info("删除角色成功: id={}, code={}", id, role.getRoleCode());
    }

    @Override
    public List<Role> listRoles() {
        return roleMapper.selectList(null);
    }

    @Override
    public RoleVO getRoleDetail(Long roleId) {
        Role role = roleMapper.selectById(roleId);
        if (role == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "角色不存在: id=" + roleId);
        }
        RoleVO vo = new RoleVO();
        vo.setId(role.getId());
        vo.setRoleCode(role.getRoleCode());
        vo.setRoleName(role.getRoleName());
        vo.setRemark(role.getRemark());

        List<RolePermission> rps = rolePermissionMapper.selectList(
                new LambdaQueryWrapper<RolePermission>().eq(RolePermission::getRoleId, roleId));
        vo.setPermIds(rps.stream().map(RolePermission::getPermissionId).toList());
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assignPermissions(Long roleId, List<Long> permIds) {
        Role role = roleMapper.selectById(roleId);
        if (role == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "角色不存在: id=" + roleId);
        }

        rolePermissionMapper.delete(
                new LambdaQueryWrapper<RolePermission>().eq(RolePermission::getRoleId, roleId));

        for (Long permId : permIds) {
            RolePermission rp = new RolePermission();
            rp.setRoleId(roleId);
            rp.setPermissionId(permId);
            rolePermissionMapper.insert(rp);
        }

        log.info("角色权限分配完成: roleId={}, 权限数量={}", roleId, permIds.size());
    }
}
