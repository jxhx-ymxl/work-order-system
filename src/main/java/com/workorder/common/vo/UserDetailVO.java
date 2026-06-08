package com.workorder.common.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Schema(description = "用户详情视图对象")
public class UserDetailVO {

    @Schema(description = "用户ID")
    private Long id;

    @Schema(description = "用户名")
    private String username;

    @Schema(description = "手机号")
    private String phone;

    @Schema(description = "所属部门ID")
    private Long deptId;

    @Schema(description = "状态: 1启用 0禁用")
    private Integer status;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "角色列表")
    private List<RoleInfo> roles;

    @Schema(description = "权限码列表")
    private List<String> permCodes;

    @Data
    @Schema(description = "角色简要信息")
    public static class RoleInfo {

        @Schema(description = "角色编码")
        private String roleCode;

        @Schema(description = "角色名称")
        private String roleName;
    }
}
