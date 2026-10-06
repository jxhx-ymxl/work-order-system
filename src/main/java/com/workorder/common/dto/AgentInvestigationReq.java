package com.workorder.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 调查请求（`POST /api/agent/investigations`）：起点单 + 自然语言问题。 */
@Data
public class AgentInvestigationReq {

    @NotBlank(message = "orderNo 不能为空")
    @Schema(description = "起点工单编号（结构化入参，不从问题文本里解析）", example = "WO-20261006-00001")
    private String orderNo;

    @NotBlank(message = "question 不能为空")
    @Schema(description = "自然语言问题", example = "这张单现在到哪一步了？")
    private String question;
}
