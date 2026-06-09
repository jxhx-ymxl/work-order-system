package com.workorder.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AssignReq {
    @NotNull(message = "被指派人ID不能为空")
    @Schema(description = "被指派人用户ID")
    private Long assigneeId;
}
