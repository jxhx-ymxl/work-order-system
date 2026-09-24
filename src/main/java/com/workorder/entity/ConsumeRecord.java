package com.workorder.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 消费去重记录（表 {@code t_consume_record}，P4 步骤 1）。
 *
 * <p><b>职责边界（不要和状态守卫混为一谈）</b>：
 * 本表回答"**这条事件消费过吗**"；工单的状态守卫（{@code WHERE status='ACCEPTED'}）回答
 * "**这张单现在该被释放吗**"。两者都保留：删掉本表 → 重复投递会重复执行；
 * 删掉状态守卫 → 迟到的释放检查会把已经开工/已释放的单改错状态。
 *
 * <p><b>写入必须在业务事务内</b>：插入本行与业务写一起提交、一起回滚（见 {@code ConsumeRecordService}）。
 */
@Data
@TableName("t_consume_record")
public class ConsumeRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 事件唯一键：{@code {aggregate}:{aggregateId}:v{version}:{eventType}} */
    private String eventId;

    /** 消费者标识；本项目的释放检查消费者取 {@code order-release-listener} */
    private String consumer;

    /** 消费时间（与业务写同事务提交） */
    private LocalDateTime consumedAt;
}
