package com.workorder.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 事件发件箱（表 {@code t_event_outbox}）。
 *
 * <p>写入发生在**业务事务内**：业务提交则事件一定在；业务回滚则事件一定不在（这是 outbox 的全部意义）。
 * 投递由独立任务完成（P1 步骤 3），本表因此同时是"待投递队列"与"投递审计记录"。
 */
@Data
@TableName("t_event_outbox")
public class EventOutbox {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** UNIQUE：事件唯一键，同时是消费端幂等键 */
    private String eventId;

    private String eventType;

    private Long aggregateId;

    /** 事件发生时的聚合版本（工单乐观锁 version），用于区分同一工单的多次合法事件 */
    private Integer aggregateVersion;

    /** 瘦消息载荷（JSON 列，只带 orderId） */
    private String payload;

    /** 最早可投递时间 = occurredAt + accept_minutes */
    private LocalDateTime deliverAt;

    /** PENDING / SENT / FAILED */
    private String status;

    private Integer retryCount;

    private LocalDateTime nextRetryAt;

    /** 业务事件发生时间（业务侧时钟） */
    private LocalDateTime occurredAt;

    /** 记录落库时间（数据库侧时钟，由 DDL 默认值填充） */
    private LocalDateTime createdAt;

    private LocalDateTime sentAt;
}
