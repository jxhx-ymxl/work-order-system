package com.workorder.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.workorder.common.PageResult;
import com.workorder.common.Result;
import com.workorder.common.vo.NotificationVO;
import com.workorder.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
@Tag(name = "站内信")
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping
    @SaCheckLogin
    @Operation(summary = "查本人站内信分页列表")
    public Result<PageResult<NotificationVO>> list(@RequestParam(defaultValue = "1") Integer page,
                                                    @RequestParam(defaultValue = "20") Integer size) {
        Long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(notificationService.listByUser(userId, page, size));
    }

    @GetMapping("/unread-count")
    @SaCheckLogin
    @Operation(summary = "查本人未读数量")
    public Result<Map<String, Long>> unreadCount() {
        Long userId = StpUtil.getLoginIdAsLong();
        long count = notificationService.getUnreadCount(userId);
        return Result.ok(Map.of("count", count));
    }

    @PutMapping("/{id}/read")
    @SaCheckLogin
    @Operation(summary = "标记已读")
    public Result<Void> markAsRead(@PathVariable Long id) {
        Long userId = StpUtil.getLoginIdAsLong();
        notificationService.markAsRead(id, userId);
        return Result.ok();
    }
}
