package com.workorder.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 消息重试账本（表 {@code t_message_retry}，P4 步骤 2）。
 *
 * <p><b>它与 {@link ConsumeRecord} 的事务要求正好相反</b>（两个类放在一起看更清楚）：
 * <ul>
 *   <li>{@code t_consume_record}：**必须与业务同事务**——业务失败要连去重记录一起回滚，否则重试会被永久跳过。</li>
 *   <li>{@code t_message_retry}（本表）：**必须在业务事务之外**写——业务失败恰恰是要重试的原因，
 *       如果跟着业务一起回滚，就变成"失败了但没人记得要重试"，消息被 ACK 掉后彻底消失。</li>
 * </ul>
 * <b>不要把两者"顺手统一"到同一个事务里</b>，那是本步最容易做错的地方（见 {@code MessageRetryService} 类注释）。
 */
@Data
@TableName("t_message_retry")
public class MessageRetry {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 事件唯一键；重投时必须原样带回 {@code x-event-id} */
    private String eventId;

    /** 消费者标识（取值见 {@code ConsumeRecordService.CONSUMER_ORDER_RELEASE}） */
    private String consumer;

    /** 原始消息体（瘦消息 JSON），重投时原样投出 */
    private String payload;

    /** 已失败次数 */
    private Integer attempt;

    /** 下次重投时间（{@code PARKED}/{@code SUCCEEDED} 时为 NULL） */
    private LocalDateTime nextRetryAt;

    /** PENDING / SUCCEEDED / PARKED */
    private String status;

    /** 最近一次失败原因（已截断） */
    private String lastError;

    /** 首次失败落库时间 */
    private LocalDateTime createdAt;
}
