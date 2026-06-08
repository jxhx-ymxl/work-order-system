package com.workorder.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_work_order_log")
public class WorkOrderLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long orderId;

    private String orderNo;

    private Long operatorId;

    private String action;

    private String oldStatus;

    private String newStatus;

    private String remark;

    private LocalDateTime createdAt;
}
