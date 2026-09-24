package com.workorder.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_work_order")
public class WorkOrder {

    @TableId(type = IdType.AUTO)
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
    /** AI 分诊状态：PENDING 待分诊 / DONE 已定稿 / FAILED 分诊失败（见 t_work_order 列注释） */
    private String triageStatus;

    private LocalDateTime slaDeadline;

    @Version
    private Integer version;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
