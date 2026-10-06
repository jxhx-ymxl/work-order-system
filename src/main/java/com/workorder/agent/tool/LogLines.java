package com.workorder.agent.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.workorder.agent.SensitiveDataRedactor;
import com.workorder.entity.User;
import com.workorder.entity.WorkOrderLog;
import com.workorder.mapper.UserMapper;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 日志行的渲染口径（`动作@时间 by 操作人`）——**两个读日志的工具共用一份，不各写一套**
 * （`docs/AGENT-PLAN.md` §3.2：同一事实键在不同工具里必须同形状；复制品迟早漂移）。
 *
 * <p>两条既有约束照旧：① `operatorId = 0` 是**系统操作**（`OrderLogAspect` 的降级），不得渲染成某个人；
 * ② 操作人只给**脱敏显示名**（§4.4 / D77），有 id 但查不到用户行时返回 {@code null}（D83 的"真正的未知"）。
 */
final class LogLines {

    /** 系统操作人（`OrderLogAspect.resolveOperatorId` 在无登录上下文时降级为 0）。 */
    static final long SYSTEM_OPERATOR_ID = 0L;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private LogLines() {
    }

    static String render(List<WorkOrderLog> logs, UserMapper userMapper) {
        return logs.stream().map(log -> renderOne(log, userMapper)).collect(Collectors.joining("；"));
    }

    static String renderOne(WorkOrderLog log, UserMapper userMapper) {
        String who = renderOperator(log.getOperatorId(), userMapper);
        String at = log.getCreatedAt() == null ? "时间未知" : log.getCreatedAt().format(TIME);
        return log.getAction() + "@" + at + " by " + who;
    }

    /** 操作人渲染：系统操作（id=0）优先，其次脱敏名，查不到则明说。 */
    static String renderOperator(Long operatorId, UserMapper userMapper) {
        if (operatorId != null && operatorId == SYSTEM_OPERATOR_ID) {
            return "系统操作";
        }
        String displayName = operatorId == null ? null : maskedDisplayNameOrNull(operatorId, userMapper);
        return displayName == null ? "显示名查不到" : displayName;
    }

    /**
     * 处理人只给脱敏显示名（§4.4 白名单：不外发 username / name 原值、不外发 user_id）。
     *
     * <p><b>有 id 但查不到用户行时返回 {@code null}</b>——那是"真正的未知"（D83），
     * 由调用方决定标法与文案；不要在这里编一个占位名，否则"查不到"会被伪装成"查到了"。
     */
    static String maskedDisplayNameOrNull(Long userId, UserMapper userMapper) {
        User user = userMapper.selectById(userId);
        if (user == null || user.getUsername() == null || user.getUsername().isBlank()) {
            return null;
        }
        return SensitiveDataRedactor.maskName(user.getUsername());
    }
}
