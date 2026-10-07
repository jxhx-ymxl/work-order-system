package com.workorder.common.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 部门下拉项（**免登录只读**的 `/api/depts` 用）：只有 `id` 与 `name`，不带停用项、不带用户数。
 *
 * <p>暴露面的取舍见 D109：未登录者可看到"部门 id + 名称"（为了注册页能选部门）。
 */
@Data
@AllArgsConstructor
@Schema(description = "部门下拉项（id + 名称）")
public class DeptOptionVO {

    private Long id;

    private String name;
}
