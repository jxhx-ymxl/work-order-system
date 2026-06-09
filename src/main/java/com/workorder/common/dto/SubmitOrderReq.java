package com.workorder.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "工单提交请求")
public class SubmitOrderReq {

    @NotBlank(message = "工单标题不能为空")
    @Schema(description = "工单标题", example = "空调报修")
    private String title;

    @NotBlank(message = "工单内容不能为空")
    @Schema(description = "工单内容", example = "3楼空调不制冷")
    private String content;

    @Schema(description = "工单类型: REPAIR/LEAVE/REIMBURSE/OTHER（可选，留空由LLM自动识别）", example = "REPAIR")
    private String type;

    @Schema(description = "优先级: 0普通 1紧急", example = "0")
    private Integer priority;
}
