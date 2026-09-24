package com.workorder.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workorder.common.event.OrderEvent;
import com.workorder.entity.EventOutbox;
import com.workorder.mapper.EventOutboxMapper;
import com.workorder.service.MessagePublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * outbox 写路径：{@code publish()} **只写库，不做任何网络调用**。
 *
 * <p><b>因此没有 Mock 实现</b>——本地与 CI 天然可跑（差异与理由见 {@code docs/DECISIONS.md} D33）。
 * "是否真的发到 MQ"是投递任务的职责，开关也应放在投递环节，而不是这里。
 *
 * <p><b>必须在业务事务内被调用</b>：本类的 insert 不另开事务，跟随调用方事务提交/回滚。
 * 若业务事务回滚，这条记录随之消失——这正是它取代 {@code afterCommit} 直发的意义
 * （`CLAUDE.md` §3 架构不变量第 2 条）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxMessagePublisher implements MessagePublisher {

    private final EventOutboxMapper eventOutboxMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void publish(OrderEvent event) {
        EventOutbox row = new EventOutbox();
        row.setEventId(event.eventId());
        row.setEventType(event.eventType());
        row.setAggregateId(event.aggregateId());
        row.setAggregateVersion(event.aggregateVersion());
        row.setDeliverAt(event.deliverAt());
        row.setOccurredAt(event.occurredAt());
        row.setStatus("PENDING");
        row.setRetryCount(0);
        try {
            row.setPayload(objectMapper.writeValueAsString(event.payload()));
        } catch (Exception e) {
            throw new IllegalStateException("事件载荷序列化失败: " + event.eventId(), e);
        }

        try {
            eventOutboxMapper.insert(row);
        } catch (DuplicateKeyException e) {
            // UNIQUE(event_id) 命中：同一事件被重复写入，属幂等场景，静默跳过（不覆盖已有记录的状态）。
            log.info("[outbox] 事件已存在，跳过重复写入: eventId={}", event.eventId());
        }
    }
}
