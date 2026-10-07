package com.workorder.controller;

import com.workorder.common.Result;
import com.workorder.common.vo.DeptOptionVO;
import com.workorder.service.DeptService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 部门下拉（**免登录只读**）：`GET /api/depts` → 只返回 `enabled = 1` 的 `[{id, name}]`。
 *
 * <p>为什么免登录：注册页要在**注册之前**让用户选部门，那时没有会话。
 * <b>取舍</b>（D109 已登记）：未登录者可读到"部门 id + 名称"这一层信息（不含用户数、不含停用项）；
 * 备选方案是"注册时不选部门、由管理员事后分配"——登记为候选，本轮不选（多一步人工，且现在就是这么漏的）。
 *
 * <p>路径已加进 `SaTokenConfig.excludePathPatterns`（与 `/api/login`、`/api/users/register` 同栏）。
 */
@RestController
@RequestMapping("/api/depts")
@RequiredArgsConstructor
@Tag(name = "部门（公开只读）")
public class DeptPublicController {

    private final DeptService deptService;

    @GetMapping
    @Operation(summary = "启用的部门列表（id + 名称），供注册页下拉——免登录")
    public Result<List<DeptOptionVO>> listEnabledDepts() {
        return Result.ok(deptService.listEnabled());
    }
}
