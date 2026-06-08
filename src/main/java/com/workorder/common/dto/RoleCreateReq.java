package com.workorder.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "角色创建请求")
public class RoleCreateReq {

    @NotBlank(message = "角色编码不能为空")
    @Schema(description = "角色编码", example = "CUSTOM_HANDLER")
    private String roleCode;

    @NotBlank(message = "角色名称不能为空")
    @Schema(description = "角色名称", example = "自定义处理人")
    private String roleName;

    @Schema(description = "备注", example = "仅限处理指定类型工单")
    private String remark;
}
