package com.workorder.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.workorder.common.BizException;
import com.workorder.common.ErrorCode;
import com.workorder.common.PageResult;
import com.workorder.common.vo.NotificationVO;
import com.workorder.entity.Notification;
import com.workorder.entity.Role;
import com.workorder.entity.User;
import com.workorder.entity.UserRole;
import com.workorder.mapper.NotificationMapper;
import com.workorder.mapper.RoleMapper;
import com.workorder.mapper.UserMapper;
import com.workorder.mapper.UserRoleMapper;
import com.workorder.service.NotificationService;
import com.workorder.service.NotifyChannel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private final NotifyChannel notifyChannel;
    private final NotificationMapper notificationMapper;
    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final UserMapper userMapper;

    @Override
    public void send(Long userId, String title, String content) {
        notifyChannel.send(userId, title, content);
    }

    @Override
    public void sendToRole(String roleCode, String title, String content) {
        Role role = roleMapper.selectOne(
                new LambdaQueryWrapper<Role>().eq(Role::getRoleCode, roleCode));
        if (role == null) {
            return;
        }
        List<UserRole> userRoles = userRoleMapper.selectList(
                new LambdaQueryWrapper<UserRole>().eq(UserRole::getRoleId, role.getId()));
        for (UserRole ur : userRoles) {
            notifyChannel.send(ur.getUserId(), title, content);
        }
    }

    /**
     * 带事件幂等键的按角色群发（P5 步骤 2）。
     *
     * <p><b>为什么"接收人解析"在这里而调用方在消费端</b>：解析规则是"按角色"（plan §2.1 的前提是
     * 一个校区 30–50 名处理人，全部要通知），但解析出来的**人数**决定了写放大倍数——
     * 所以这段代码只能被消费端调用，绝不能出现在提交事务里。方法本身不做事务声明，跟随调用方事务。
     */
    @Override
    public int sendToRoleOnce(String roleCode, String title, String content,
                              String eventId, String refType, Long refId) {
        Role role = roleMapper.selectOne(
                new LambdaQueryWrapper<Role>().eq(Role::getRoleCode, roleCode));
        if (role == null) {
            // 角色不存在 = 没有接收人。不抛异常：这是数据/配置状态，重试也不会变出人来
            log.warn("[notify] 角色不存在，本次通知没有接收人: roleCode={}, eventId={}", roleCode, eventId);
            return 0;
        }
        List<UserRole> userRoles = userRoleMapper.selectList(
                new LambdaQueryWrapper<UserRole>().eq(UserRole::getRoleId, role.getId()));
        int inserted = 0;
        for (UserRole ur : userRoles) {
            if (notifyChannel.sendOnce(ur.getUserId(), title, content, eventId, refType, refId)) {
                inserted++;
            }
        }
        if (userRoles.isEmpty()) {
            log.warn("[notify] 角色 {} 下没有任何用户，本次通知没有接收人: eventId={}", roleCode, eventId);
        }
        return inserted;
    }

    @Override
    public PageResult<NotificationVO> listByUser(Long userId, Integer page, Integer size) {
        if (page == null || page < 1) page = 1;
        if (size == null || size < 1) size = 20;
        if (size > 100) size = 100;

        IPage<Notification> result = notificationMapper.selectByUserId(
                new Page<>(page, size), userId);

        List<NotificationVO> vos = result.getRecords().stream()
                .map(this::toVO)
                .toList();

        return PageResult.of(result.getTotal(), result.getPages(), result.getCurrent(), vos);
    }

    @Override
    public void markAsRead(Long notificationId, Long userId) {
        Notification notification = notificationMapper.selectById(notificationId);
        if (notification == null || !notification.getUserId().equals(userId)) {
            throw new BizException(ErrorCode.NOT_FOUND, "通知不存在");
        }
        notificationMapper.markAsRead(notificationId);
    }

    @Override
    public long getUnreadCount(Long userId) {
        return notificationMapper.selectCount(
                new LambdaQueryWrapper<Notification>()
                        .eq(Notification::getUserId, userId)
                        .eq(Notification::getIsRead, 0));
    }

    private NotificationVO toVO(Notification n) {
        NotificationVO vo = new NotificationVO();
        vo.setId(n.getId());
        vo.setTitle(n.getTitle());
        vo.setContent(n.getContent());
        vo.setRefType(n.getRefType());
        vo.setRefId(n.getRefId());
        vo.setIsRead(n.getIsRead());
        vo.setCreatedAt(n.getCreatedAt());
        return vo;
    }
}
