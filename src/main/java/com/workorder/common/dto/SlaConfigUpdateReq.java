package com.workorder.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "SLA配置更新请求")
public class SlaConfigUpdateReq {

    @NotBlank(message = "工单类型不能为空")
    @Schema(description = "工单类型", example = "REPAIR")
    private String type;

    @NotNull(message = "优先级不能为空")
    @Schema(description = "优先级: 0普通 1紧急", example = "0")
    private Integer priority;

    @NotNull(message = "接单时限不能为空")
    @Schema(description = "接单时限（分钟）", example = "30")
    private Integer acceptMinutes;

    @NotNull(message = "完成时限不能为空")
    @Schema(description = "完成时限（分钟）", example = "120")
    private Integer finishMinutes;
}
