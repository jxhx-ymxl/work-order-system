package com.workorder.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 新增部门请求（2026-10-08）。 */
@Data
@Schema(description = "新增部门请求")
public class DeptCreateReq {

    @NotBlank(message = "部门名称不能为空")
    @Size(max = 64, message = "部门名称不能超过 64 个字符")
    @Schema(description = "部门名称", example = "运维一部")
    private String name;
}
