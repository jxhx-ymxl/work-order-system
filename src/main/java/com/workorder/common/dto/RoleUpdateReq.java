package com.workorder.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "角色更新请求")
public class RoleUpdateReq {

    @NotBlank(message = "角色名称不能为空")
    @Schema(description = "角色名称", example = "更新后的角色名")
    private String roleName;

    @Schema(description = "备注")
    private String remark;
}
