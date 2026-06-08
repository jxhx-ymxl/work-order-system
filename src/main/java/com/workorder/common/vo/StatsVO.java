package com.workorder.common.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "工单统计视图")
public class StatsVO {

    @Schema(description = "工单状态", example = "PENDING")
    private String status;

    @Schema(description = "数量", example = "42")
    private Long count;
}
