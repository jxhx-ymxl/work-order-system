package com.workorder.common.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class SubmitOrderReq {

    @NotBlank(message = "工单标题不能为空")
    private String title;

    @NotBlank(message = "工单内容不能为空")
    private String content;

    @NotBlank(message = "工单类型不能为空")
    private String type;

    private Integer priority;
}
