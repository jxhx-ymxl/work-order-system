package com.workorder.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "分页查询参数")
public class PageQuery {

    @Schema(description = "页码", example = "1")
    private Integer page = 1;

    @Schema(description = "每页条数(最大100)", example = "20")
    private Integer size = 20;

    @Schema(description = "工单状态筛选", example = "PENDING")
    private String status;

    @Schema(description = "工单编号模糊搜索", example = "WO-20260608")
    private String orderNo;

    @Schema(description = "提交人ID筛选")
    private Long submitterId;

    @Schema(description = "处理人ID筛选")
    private Long assigneeId;

    public int getSize() {
        return size != null && size > 0 ? Math.min(size, 100) : 20;
    }

    public int getPage() {
        return page != null && page > 0 ? page : 1;
    }
}
