package com.workorder.common.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 调查结果（同步接口的对外形状）。
 *
 * <p>**如实映射** `AgentInvestigationService.Outcome`：状态 / 原因码 / 报告（只有编号）/ 渲染文本。
 * 报告里**没有模型自由文本**——`problemType` + 证据编号 + 建议编号，正文由后端渲染（§3.2）。
 */
@Data
public class AgentInvestigationVO {

    @Schema(description = "终态：COMPLETED / FAILED / TIMED_OUT / CANCELLED / INCOMPLETE")
    private String status;

    @Schema(description = "失败原因码（成功时为 null）")
    private String failureCode;

    @Schema(description = "问题类型（无报告时为 null）")
    private String problemType;

    @Schema(description = "报告引用的证据编号（模型只能引用这些编号）")
    private List<String> evidenceIds;

    @Schema(description = "报告引用的建议编号")
    private List<String> suggestionIds;

    @Schema(description = "后端按证据渲染的正文（三段；失败时为 null）")
    private String renderedText;
}
