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
import org.springframework.stereotype.Service;

import java.util.List;

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
