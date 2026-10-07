package com.workorder.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 修改部门请求（2026-10-08）：两个字段都可选——只给 `name` 就是改名，只给 `enabled` 就是启停。
 *
 * <p>**没有 DELETE 接口**：部门被用户/工单引用，"删除"= `enabled = 0`（见 D109）。
 */
@Data
@Schema(description = "修改部门请求（改名 / 启停，字段都可选）")
public class DeptUpdateReq {

    @Schema(description = "新名称（可选）", example = "运维一部")
    private String name;

    @Schema(description = "1 启用 / 0 停用（可选）", example = "0")
    private Integer enabled;
}
