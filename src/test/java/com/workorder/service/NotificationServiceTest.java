package com.workorder.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.workorder.common.PageResult;
import com.workorder.common.vo.NotificationVO;
import com.workorder.entity.Notification;
import com.workorder.mapper.NotificationMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class NotificationServiceTest {

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private NotificationMapper notificationMapper;

    @Test
    @DisplayName("send —— 站内信落库成功")
    void testSend_insertsNotification() {
        notificationService.send(1L, "测试标题", "测试内容");

        List<Notification> list = notificationMapper.selectList(
                new LambdaQueryWrapper<Notification>()
                        .eq(Notification::getUserId, 1L));
        assertEquals(1, list.size());
        Notification n = list.get(0);
        assertEquals("测试标题", n.getTitle());
        assertEquals("测试内容", n.getContent());
        assertEquals(0, n.getIsRead());
    }

    @Test
    @DisplayName("listByUser —— 分页查询本人站内信")
    void testListByUser_pagination() {
        notificationService.send(1L, "标题1", "内容1");
        notificationService.send(1L, "标题2", "内容2");
        notificationService.send(2L, "标题3", "内容3");
        notificationService.send(1L, "标题4", "内容4");

        PageResult<NotificationVO> page = notificationService.listByUser(1L, 1, 20);

        assertEquals(3, page.getTotal());
        assertEquals(3, page.getRecords().size());
        List<String> titles = page.getRecords().stream()
                .map(NotificationVO::getTitle).toList();
        assertTrue(titles.containsAll(List.of("标题1", "标题2", "标题4")));
    }

    @Test
    @DisplayName("markAsRead —— 标记已读成功且幂等")
    void testMarkAsRead_idempotent() {
        notificationService.send(1L, "标题", "内容");
        List<Notification> list = notificationMapper.selectList(
                new LambdaQueryWrapper<Notification>().eq(Notification::getUserId, 1L));
        Long id = list.get(0).getId();
        assertEquals(0, list.get(0).getIsRead());

        notificationService.markAsRead(id, 1L);

        Notification updated = notificationMapper.selectById(id);
        assertEquals(1, updated.getIsRead());

        // 重复标记已读不报错
        assertDoesNotThrow(() -> notificationService.markAsRead(id, 1L));
    }

    @Test
    @DisplayName("markAsRead —— 非本人通知抛 NOT_FOUND")
    void testMarkAsRead_notOwner() {
        notificationService.send(1L, "标题", "内容");
        List<Notification> list = notificationMapper.selectList(
                new LambdaQueryWrapper<Notification>().eq(Notification::getUserId, 1L));
        Long id = list.get(0).getId();

        assertThrows(Exception.class, () -> notificationService.markAsRead(id, 2L));
    }

    @Test
    @DisplayName("getUnreadCount —— 返回未读数量正确")
    void testGetUnreadCount() {
        notificationService.send(1L, "未读1", "内容");
        notificationService.send(1L, "未读2", "内容");
        notificationService.send(1L, "未读3", "内容");

        long count = notificationService.getUnreadCount(1L);
        assertEquals(3, count);

        // 标记一条已读后未读数减1
        List<Notification> list = notificationMapper.selectList(
                new LambdaQueryWrapper<Notification>().eq(Notification::getUserId, 1L));
        notificationService.markAsRead(list.get(0).getId(), 1L);

        assertEquals(2, notificationService.getUnreadCount(1L));
    }

    @Test
    @DisplayName("send —— InAppNotifyChannel 策略模式注入验证")
    void testSend_viaNotifyChannelInterface() {
        // NotificationService 通过构造注入 NotifyChannel（InAppNotifyChannel 实例）
        // 调用 send 等价于 InAppNotifyChannel.send，结果应落库
        notificationService.send(1L, "策略模式验证", "通过接口调用");

        List<Notification> list = notificationMapper.selectList(
                new LambdaQueryWrapper<Notification>()
                        .eq(Notification::getUserId, 1L));
        assertEquals(1, list.size());
        assertEquals("策略模式验证", list.get(0).getTitle());
    }
}
