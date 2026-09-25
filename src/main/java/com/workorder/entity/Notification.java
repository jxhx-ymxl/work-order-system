package com.workorder.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_notification")
public class Notification {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String title;

    private String content;

    private String refType;

    private Long refId;

    /**
     * 触发本通知的事件键（P5 步骤 2 新增，格式 {@code {aggregate}:{id}:v{version}:{eventType}}）。
     *
     * <p>它是"通知的第二道幂等防线"：与 {@link #userId} 组成 {@code UNIQUE(event_id, user_id)}，
     * 保证**同一条事件不会给同一个接收人发两条**。为 {@code null} 表示该通知没有事件级去重
     * （SLA 超时、驳回达上限这两条 P5 之前就存在的链路仍未回填——MySQL 唯一索引允许多个 NULL，互不冲突）。
     */
    private String eventId;

    private Integer isRead;

    private LocalDateTime createdAt;
}
