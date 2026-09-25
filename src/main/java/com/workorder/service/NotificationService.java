package com.workorder.service;

import com.workorder.common.PageResult;
import com.workorder.common.vo.NotificationVO;

public interface NotificationService {

    void send(Long userId, String title, String content);

    void sendToRole(String roleCode, String title, String content);

    /**
     * **带事件幂等键**的按角色群发（P5 步骤 2 新增）：同一条事件对同一个接收人只落一条站内信。
     *
     * <p>与 {@link #sendToRole} 的差别只有一条：这里把 {@code eventId} 一起写进 {@code t_notification}，
     * 由 {@code UNIQUE(event_id, user_id)} 兜住"重复投递 / 二次消费 / 手工重投"这些重复来源。
     *
     * <p><b>调用约束</b>：接收人解析（查角色 → 查该角色下全部用户）是**按人数线性增长的写操作**，
     * 只允许在**消费端**调用，不得出现在提交等用户请求的事务里（`ASYNC-SCHEDULING-PLAN.md` §2.1）。
     *
     * @param eventId 触发本次通知的事件键（写进 {@code t_notification.event_id}）；为空时退化为无法去重
     * @return **真正新增**的条数（命中去重的接收人不计入）
     */
    int sendToRoleOnce(String roleCode, String title, String content, String eventId, String refType, Long refId);

    PageResult<NotificationVO> listByUser(Long userId, Integer page, Integer size);

    void markAsRead(Long notificationId, Long userId);

    long getUnreadCount(Long userId);
}
