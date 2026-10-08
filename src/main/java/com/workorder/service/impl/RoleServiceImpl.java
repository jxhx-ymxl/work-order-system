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

    /** 内置超管角色：权限集**不可编辑**（自锁的唯一入口，见 {@link #assignPermissions}）。 */
    private static final String SUPER_ADMIN_ROLE_CODE = "SYS_ADMIN";

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

        // ⚠ 硬保护（2026-10-08）：**内置超管角色的权限集不可编辑**——必须在下面的 delete **之前**返回。
        //
        // 背景（真实事故）：2026-10-08 在服务器上把 SYS_ADMIN 的权限清到只剩一条，管理员权限当场失效
        // （角色管理/用户管理全部打不开），恢复只能直接写库。根因是**两侧不对称**：
        // 用户侧改角色/停用自己有四道防线（不能删自己、不能移除自己的最后一个角色、admin 不允许无角色…），
        // 而**角色侧**只有"内置角色不许删"一条，`assignPermissions` 是"先 delete 全部、再按传入列表 insert"，
        // 一道防线都没有——一次点错就把自己锁死。见 `docs/DECISIONS.md` D110。
        //
        // 为什么整条禁掉而不是"至少留一条"：SYS_ADMIN 的语义就是"全部权限"
        // （`sql/init.sql` 里 1..16 全绑），任何子集都不是它；而"至少留一条"这种软约束
        // 仍然允许把 16 条删成 1 条 —— 那时管理员已经进不去页面了。
        if (SUPER_ADMIN_ROLE_CODE.equals(role.getRoleCode())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "内置超管角色的权限集不可编辑");
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
