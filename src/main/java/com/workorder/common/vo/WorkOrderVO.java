package com.workorder.common.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class WorkOrderVO {

    private Long id;

    private String orderNo;

    private String title;

    private String content;

    private String type;

    private Integer priority;

    private String status;

    private Long submitterId;

    private Long assigneeId;

    private Integer rejectCount;

    private Integer maxReject;

    private LocalDateTime slaDeadline;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
