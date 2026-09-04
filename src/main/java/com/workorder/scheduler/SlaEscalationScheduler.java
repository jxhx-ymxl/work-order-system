package com.workorder.scheduler;

import com.workorder.entity.WorkOrder;
import com.workorder.mapper.WorkOrderMapper;
import com.workorder.service.MessagePublishService;
import com.workorder.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Issue #36: SLA 超时升级定时扫描器
 * 每 5 分钟扫一次 t_work_order，找出 sla_deadline < NOW() 且状态未完结的工单
 * Issue #39: 扫描到超时工单后，通过 NotificationService 发送站内信通知管理员
 *
 * 修复（2026-09）：同一张超时工单在未完结前会每 5 分钟反复命中同一扫描，
 * 导致给管理员重复发站内信。现以 Redis SETNX 幂等键去重：每张工单 24h 内
 * 只发一次通知，避免对管理员的重复骚扰。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlaEscalationScheduler {

    private final WorkOrderMapper workOrderMapper;
    private final MessagePublishService messagePublishService;
    private final NotificationService notificationService;
    private final StringRedisTemplate redisTemplate;

    /** 幂等键前缀：sla_notified:{orderId} —— 24h 内同工单只通知一次管理员 */
    private static final String SLA_NOTIFIED_KEY_PREFIX = "sla_notified:";
    private static final Duration SLA_NOTIFIED_TTL = Duration.ofHours(24);

    private static final int BATCH_SIZE = 200;

    @Scheduled(fixedRate = 300_000)
    public void scanSlaExpired() {
        List<WorkOrder> expired = workOrderMapper.findSlaExpired(BATCH_SIZE);
        log.info("SLA扫描: 发现{}条超时工单", expired.size());

        for (WorkOrder order : expired) {
            Long orderId = order.getId();
            // Redis SETNX 幂等守卫：抢到 key 才通知，24h 内同工单只发一次
            // 抢不到说明本工单已通知过（本次周期内重复命中或历史已发），跳过
            String key = SLA_NOTIFIED_KEY_PREFIX + orderId;
            Boolean acquired;
            try {
                acquired = redisTemplate.opsForValue().setIfAbsent(key, "1", SLA_NOTIFIED_TTL);
            } catch (Exception e) {
                // Redis 异常不应阻断定时扫描——降级为照常发送（由 SQL 状态/其它去重兜底）
                log.warn("SLA幂等键写入失败，跳过本次去重: orderId={}, err={}", orderId, e.getMessage());
                acquired = Boolean.TRUE;
            }

            if (!Boolean.TRUE.equals(acquired)) {
                log.info("SLA通知已发送过，跳过重复通知: orderId={}", orderId);
                continue;
            }

            try {
                messagePublishService.sendSlaEscalation(orderId);
                notificationService.sendToRole("SYS_ADMIN",
                        "工单 " + order.getOrderNo() + " SLA 超时",
                        "类型:" + order.getType()
                                + ", 优先级:" + order.getPriority()
                                + ", 当前状态:" + order.getStatus()
                                + ", 超时时间:" + order.getSlaDeadline());
            } catch (Exception e) {
                log.error("SLA升级通知发送失败: orderId={}", orderId, e);
                // 通知失败时删除幂等键，允许下个周期重试，避免永久漏发
                try {
                    redisTemplate.delete(key);
                } catch (Exception ex) {
                    log.warn("清理SLA幂等键失败: orderId={}", orderId, ex);
                }
            }
        }
    }
}
