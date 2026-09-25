package com.workorder.service.impl;

import com.workorder.entity.Notification;
import com.workorder.mapper.NotificationMapper;
import com.workorder.service.NotifyChannel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 站内信通知渠道 —— 写入 t_notification 表。
 *
 * <p>{@link #sendOnce} 的第二道幂等防线靠 {@code UNIQUE(event_id, user_id)}：命中冲突即视为"这条事件
 * 已经给该接收人发过"，返回 {@code false}、不抛异常。注意 MySQL 的唯一索引**允许多个 NULL 并存**，
 * 所以 {@code eventId} 为 null 的老链路（SLA 超时 / 驳回达上限）行为完全不变。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InAppNotifyChannel implements NotifyChannel {

    private final NotificationMapper notificationMapper;

    @Override
    public void send(Long userId, String title, String content) {
        notificationMapper.insert(build(userId, title, content, null, null, null));
    }

    @Override
    public boolean sendOnce(Long userId, String title, String content, String eventId, String refType, Long refId) {
        Notification notification = build(userId, title, content, eventId, refType, refId);
        try {
            notificationMapper.insert(notification);
            return true;
        } catch (DuplicateKeyException e) {
            // 唯一键 (event_id, user_id) 命中：同一条事件已经给这个接收人发过，本次跳过。
            // **只记 DEBUG**：这是"幂等生效"的正常结论，不是异常（重复投递本就允许发生）。
            log.debug("[notify] 该事件已给此接收人发过，跳过重复发送: eventId={}, userId={}", eventId, userId);
            return false;
        }
    }

    private Notification build(Long userId, String title, String content, String eventId, String refType, Long refId) {
        Notification notification = new Notification();
        notification.setUserId(userId);
        notification.setTitle(title);
        notification.setContent(content);
        notification.setEventId(eventId);
        notification.setRefType(refType);
        notification.setRefId(refId);
        notification.setIsRead(0);
        notification.setCreatedAt(LocalDateTime.now());
        return notification;
    }
}
