package com.workorder.scheduler;

import com.workorder.entity.WorkOrder;
import com.workorder.mapper.WorkOrderMapper;
import com.workorder.service.MessagePublishService;
import com.workorder.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Issue #36: SLA 超时升级定时扫描器
 * 每 5 分钟扫一次 t_work_order，找出 sla_deadline < NOW() 且状态未完结的工单
 * Issue #39: 扫描到超时工单后，通过 NotificationService 发送站内信通知管理员
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlaEscalationScheduler {

    private final WorkOrderMapper workOrderMapper;
    private final MessagePublishService messagePublishService;
    private final NotificationService notificationService;

    private static final int BATCH_SIZE = 200;

    @Scheduled(fixedRate = 300_000)
    public void scanSlaExpired() {
        List<WorkOrder> expired = workOrderMapper.findSlaExpired(BATCH_SIZE);
        log.info("SLA扫描: 发现{}条超时工单", expired.size());

        for (WorkOrder order : expired) {
            try {
                messagePublishService.sendSlaEscalation(order.getId());
                notificationService.sendToRole("SYS_ADMIN",
                        "工单 " + order.getOrderNo() + " SLA 超时",
                        "类型:" + order.getType()
                                + ", 优先级:" + order.getPriority()
                                + ", 当前状态:" + order.getStatus()
                                + ", 超时时间:" + order.getSlaDeadline());
            } catch (Exception e) {
                log.error("SLA升级通知发送失败: orderId={}", order.getId(), e);
            }
        }
    }
}
