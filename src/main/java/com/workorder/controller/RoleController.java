package com.workorder.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.workorder.common.Result;
import com.workorder.common.dto.RoleCreateReq;
import com.workorder.common.dto.RolePermissionAssignReq;
import com.workorder.common.dto.RoleUpdateReq;
import com.workorder.common.vo.PermissionTreeVO;
import com.workorder.common.vo.RoleVO;
import com.workorder.entity.Permission;
import com.workorder.entity.Role;
import com.workorder.service.PermissionService;
import com.workorder.service.RoleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@Tag(name = "角色管理")
public class RoleController {

    private final RoleService roleService;
    private final PermissionService permissionService;

    @GetMapping("/roles")
    @SaCheckPermission("system:role:manage")
    @Operation(summary = "角色列表")
    public Result<List<Role>> listRoles() {
        return Result.ok(roleService.listRoles());
    }

    @GetMapping("/roles/{id}")
    @SaCheckPermission("system:role:manage")
    @Operation(summary = "角色详情（含已分配权限ID列表）")
    public Result<RoleVO> getRoleDetail(
            @Parameter(description = "角色ID") @PathVariable Long id) {
        return Result.ok(roleService.getRoleDetail(id));
    }

    @PostMapping("/roles")
    @SaCheckPermission("system:role:manage")
    @Operation(summary = "创建角色")
    public Result<Role> createRole(@Valid @RequestBody RoleCreateReq req) {
        return Result.ok(roleService.createRole(req));
    }

    @PutMapping("/roles/{id}")
    @SaCheckPermission("system:role:manage")
    @Operation(summary = "更新角色")
    public Result<Role> updateRole(
            @Parameter(description = "角色ID") @PathVariable Long id,
            @Valid @RequestBody RoleUpdateReq req) {
        return Result.ok(roleService.updateRole(id, req));
    }

    @DeleteMapping("/roles/{id}")
    @SaCheckPermission("system:role:manage")
    @Operation(summary = "删除角色")
    public Result<Void> deleteRole(
            @Parameter(description = "角色ID") @PathVariable Long id) {
        roleService.deleteRole(id);
        return Result.ok();
    }

    @PutMapping("/roles/{id}/permissions")
    @SaCheckPermission("system:role:manage")
    @Operation(summary = "为角色分配权限")
    public Result<Void> assignPermissions(
            @Parameter(description = "角色ID") @PathVariable Long id,
            @Valid @RequestBody RolePermissionAssignReq req) {
        roleService.assignPermissions(id, req.getPermIds());
        return Result.ok();
    }

    @GetMapping("/permissions/tree")
    @SaCheckPermission("system:role:manage")
    @Operation(summary = "权限树")
    public Result<List<PermissionTreeVO>> permissionTree() {
        List<Permission> flatList = permissionService.listAll();
        return Result.ok(permissionService.buildTree(flatList));
    }
}
