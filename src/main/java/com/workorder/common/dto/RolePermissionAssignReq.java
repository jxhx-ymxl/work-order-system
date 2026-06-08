package com.workorder.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "角色权限分配请求")
public class RolePermissionAssignReq {

    @NotEmpty(message = "权限ID列表不能为空")
    @Schema(description = "权限ID列表", example = "[1, 2, 3]")
    private List<Long> permIds;
}
