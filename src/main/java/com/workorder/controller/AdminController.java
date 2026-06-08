package com.workorder.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.workorder.common.BizException;
import com.workorder.common.ErrorCode;
import com.workorder.common.PageResult;
import com.workorder.common.Result;
import com.workorder.common.dto.SlaConfigUpdateReq;
import com.workorder.common.dto.UserRoleAssignReq;
import com.workorder.common.vo.StatsVO;
import com.workorder.common.vo.UserDetailVO;
import com.workorder.entity.SlaConfig;
import com.workorder.mapper.SlaConfigMapper;
import com.workorder.service.UserService;
import com.workorder.service.WorkOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@Tag(name = "管理员")
public class AdminController {

    private final UserService userService;
    private final SlaConfigMapper slaConfigMapper;
    private final WorkOrderService workOrderService;

    @GetMapping("/users")
    @SaCheckPermission("system:user:manage")
    @Operation(summary = "用户列表分页")
    public Result<PageResult<UserDetailVO>> listUsers(
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") Integer page,
            @Parameter(description = "每页条数") @RequestParam(defaultValue = "10") Integer size,
            @Parameter(description = "用户名（模糊搜索）") @RequestParam(required = false) String username,
            @Parameter(description = "部门ID") @RequestParam(required = false) Long deptId) {
        return Result.ok(userService.listUsers(page, size, username, deptId));
    }

    @GetMapping("/users/{id}")
    @SaCheckPermission("system:user:manage")
    @Operation(summary = "用户详情（含角色和权限）")
    public Result<UserDetailVO> getUserDetail(
            @Parameter(description = "用户ID") @PathVariable Long id) {
        return Result.ok(userService.getUserDetail(id));
    }

    @PutMapping("/users/{id}/roles")
    @SaCheckPermission("system:user:manage")
    @Operation(summary = "分配用户角色")
    public Result<Void> assignUserRoles(
            @Parameter(description = "用户ID") @PathVariable Long id,
            @Valid @RequestBody UserRoleAssignReq req) {
        userService.assignRoles(id, req.getRoleIds());
        return Result.ok();
    }

    @GetMapping("/sla/config")
    @SaCheckPermission("sla:config:manage")
    @Operation(summary = "查看全部SLA配置")
    public Result<List<SlaConfig>> listSlaConfig() {
        return Result.ok(slaConfigMapper.selectList(null));
    }

    @PutMapping("/sla/config")
    @SaCheckPermission("sla:config:manage")
    @Operation(summary = "更新SLA配置（根据type+priority定位）")
    public Result<Void> updateSlaConfig(@Valid @RequestBody SlaConfigUpdateReq req) {
        SlaConfig config = slaConfigMapper.selectOne(
                new LambdaQueryWrapper<SlaConfig>()
                        .eq(SlaConfig::getType, req.getType())
                        .eq(SlaConfig::getPriority, req.getPriority()));
        if (config == null) {
            throw new BizException(ErrorCode.NOT_FOUND,
                    "SLA配置不存在: type=" + req.getType() + ", priority=" + req.getPriority());
        }
        config.setAcceptMinutes(req.getAcceptMinutes());
        config.setFinishMinutes(req.getFinishMinutes());
        slaConfigMapper.updateById(config);
        return Result.ok();
    }

    @GetMapping("/orders/stats")
    @SaCheckPermission("order:stats")
    @Operation(summary = "工单统计（DEPT=部门级 / ALL=全局需order:stats:all权限）")
    public Result<List<StatsVO>> orderStats(
            @Parameter(description = "统计范围: DEPT 或 ALL") @RequestParam(defaultValue = "DEPT") String scope) {
        if ("ALL".equals(scope)) {
            StpUtil.checkPermission("order:stats:all");
        }
        Long currentUserId = StpUtil.getLoginIdAsLong();
        return Result.ok(workOrderService.getStats(scope, currentUserId));
    }
}
