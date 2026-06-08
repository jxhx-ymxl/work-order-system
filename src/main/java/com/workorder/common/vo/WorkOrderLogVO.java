package com.workorder.common.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class WorkOrderLogVO {

    private Long id;

    private Long orderId;

    private String orderNo;

    private Long operatorId;

    private String operatorName;

    private String action;

    private String oldStatus;

    private String newStatus;

    private String remark;

    private LocalDateTime createdAt;
}
