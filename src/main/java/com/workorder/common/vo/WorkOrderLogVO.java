package com.workorder.common.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "工单操作日志视图")
public class WorkOrderLogVO {

    @Schema(description = "日志ID")
    private Long id;

    @Schema(description = "关联工单ID")
    private Long orderId;

    @Schema(description = "工单编号")
    private String orderNo;

    @Schema(description = "操作人ID")
    private Long operatorId;

    @Schema(description = "操作人姓名")
    private String operatorName;

    @Schema(description = "操作类型", example = "SUBMIT")
    private String action;

    @Schema(description = "变更前状态")
    private String oldStatus;

    @Schema(description = "变更后状态")
    private String newStatus;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "操作时间")
    private LocalDateTime createdAt;
}
