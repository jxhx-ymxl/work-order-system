package com.workorder.service;

import com.workorder.common.PageResult;
import com.workorder.common.vo.NotificationVO;

public interface NotificationService {

    void send(Long userId, String title, String content);

    void sendToRole(String roleCode, String title, String content);

    PageResult<NotificationVO> listByUser(Long userId, Integer page, Integer size);

    void markAsRead(Long notificationId, Long userId);

    long getUnreadCount(Long userId);
}
