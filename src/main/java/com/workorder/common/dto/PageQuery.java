package com.workorder.common.dto;

import lombok.Data;

@Data
public class PageQuery {

    private Integer page = 1;

    private Integer size = 20;

    private String status;

    private String orderNo;

    private Long submitterId;

    private Long assigneeId;

    public int getSize() {
        return size != null && size > 0 ? Math.min(size, 100) : 20;
    }

    public int getPage() {
        return page != null && page > 0 ? page : 1;
    }
}
