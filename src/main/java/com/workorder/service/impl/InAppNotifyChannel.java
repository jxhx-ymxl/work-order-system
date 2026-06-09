package com.workorder.service.impl;

import com.workorder.entity.Notification;
import com.workorder.mapper.NotificationMapper;
import com.workorder.service.NotifyChannel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** 站内信通知渠道 —— 写入 t_notification 表 */
@Component
@RequiredArgsConstructor
public class InAppNotifyChannel implements NotifyChannel {

    private final NotificationMapper notificationMapper;

    @Override
    public void send(Long userId, String title, String content) {
        Notification notification = new Notification();
        notification.setUserId(userId);
        notification.setTitle(title);
        notification.setContent(content);
        notification.setIsRead(0);
        notification.setCreatedAt(LocalDateTime.now());
        notificationMapper.insert(notification);
    }
}
