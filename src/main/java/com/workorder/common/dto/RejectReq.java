package com.workorder.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RejectReq {
    @NotBlank(message = "Token不能为空")
    @Schema(description = "操作Token（通过 GET /api/orders/{id}/action-token 获取）")
    private String token;

    @NotBlank(message = "驳回理由不能为空")
    @Schema(description = "驳回理由")
    private String remark;
}
