package com.workorder.common.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Schema(description = "权限树节点")
public class PermissionTreeVO {

    @Schema(description = "权限ID")
    private Long id;

    @Schema(description = "权限编码")
    private String permCode;

    @Schema(description = "权限名称")
    private String permName;

    @Schema(description = "父权限ID")
    private Long parentId;

    @Schema(description = "子权限列表")
    private List<PermissionTreeVO> children = new ArrayList<>();
}
