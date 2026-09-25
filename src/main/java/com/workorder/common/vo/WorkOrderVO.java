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

    /**
     * AI 分诊状态（P5 步骤 3 新增暴露）：{@code PENDING} 分类中 / {@code DONE} 已分类 / {@code FAILED} 分类失败。
     *
     * <p><b>为什么必须出现在这里（而不仅是详情 VO）</b>：列表页的「类型」列也要按三态渲染——
     * 只暴露在详情里的话，列表仍会把兜底值 {@code OTHER} 当成"AI 判成其他"显示出来。
     * {@code WorkOrderDetailVO} 内嵌的就是本 VO，因此详情响应同时受益，不需要在详情 VO 上再抄一份字段
     * （抄一份反而多一个要同步的地方）。
     */
    @Schema(description = "AI 分诊状态: PENDING分类中 / DONE已分类 / FAILED分类失败")
    private String triageStatus;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;
}
