package com.workorder.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.workorder.common.Result;
import com.workorder.common.dto.DeptCreateReq;
import com.workorder.common.dto.DeptUpdateReq;
import com.workorder.common.vo.DeptVO;
import com.workorder.service.DeptService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 部门管理（2026-10-08 部门实体化）：`/api/admin/depts`，权限码 `system:dept:manage`。
 *
 * <p><b>刻意没有 DELETE</b>：部门被用户/工单引用，物理删除会留悬空引用 —— "删除" = `PUT {enabled:0}`。
 * 理由与不做清单见 `docs/DECISIONS.md` D109。
 */
@RestController
@RequestMapping("/api/admin/depts")
@RequiredArgsConstructor
@Tag(name = "部门管理")
public class DeptController {

    private final DeptService deptService;

    @GetMapping
    @SaCheckPermission("system:dept:manage")
    @Operation(summary = "部门列表（含停用项，带 userCount）")
    public Result<List<DeptVO>> listDepts() {
        return Result.ok(deptService.listAll());
    }

    @PostMapping
    @SaCheckPermission("system:dept:manage")
    @Operation(summary = "新增部门（重名 → 业务码 400）")
    public Result<DeptVO> createDept(@Valid @RequestBody DeptCreateReq req) {
        return Result.ok(deptService.create(req.getName()));
    }

    @PutMapping("/{id}")
    @SaCheckPermission("system:dept:manage")
    @Operation(summary = "改名 / 启停（不做物理删除）")
    public Result<DeptVO> updateDept(
            @Parameter(description = "部门ID") @PathVariable Long id,
            @RequestBody DeptUpdateReq req) {
        return Result.ok(deptService.update(id, req.getName(), req.getEnabled()));
    }
}
