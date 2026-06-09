package com.workorder.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.workorder.entity.Notification;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface NotificationMapper extends BaseMapper<Notification> {

    IPage<Notification> selectByUserId(Page<Notification> page, @Param("userId") Long userId);

    @Update("UPDATE t_notification SET is_read = 1 WHERE id = #{id}")
    int markAsRead(@Param("id") Long id);
}
