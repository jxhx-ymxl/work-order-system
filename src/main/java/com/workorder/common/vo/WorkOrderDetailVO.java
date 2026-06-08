package com.workorder.common.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "工单详情视图")
public class WorkOrderDetailVO {

    @Schema(description = "工单基本信息")
    private WorkOrderVO order;

    @Schema(description = "操作日志列表(按时间正序)")
    private List<WorkOrderLogVO> logs;
}
