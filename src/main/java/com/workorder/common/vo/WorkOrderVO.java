package com.workorder.common.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "工单视图对象")
public class WorkOrderVO {

    @Schema(description = "工单主键ID")
    private Long id;

    @Schema(description = "工单编号", example = "WO-20260608-00001")
    private String orderNo;

    @Schema(description = "工单标题")
    private String title;

    @Schema(description = "工单内容")
    private String content;

    @Schema(description = "工单类型")
    private String type;

    @Schema(description = "优先级: 0普通 1紧急")
    private Integer priority;

    @Schema(description = "当前状态")
    private String status;

    @Schema(description = "提交人ID")
    private Long submitterId;

    @Schema(description = "处理人ID")
    private Long assigneeId;

    @Schema(description = "驳回次数")
    private Integer rejectCount;

    @Schema(description = "最大驳回次数")
    private Integer maxReject;

    @Schema(description = "SLA截止时间")
    private LocalDateTime slaDeadline;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;
}
