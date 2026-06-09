package com.workorder.common.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class NotificationVO {

    private Long id;

    private String title;

    private String content;

    private String refType;

    private Long refId;

    private Integer isRead;

    private LocalDateTime createdAt;
}
