package com.workorder.scheduler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.workorder.entity.WorkOrder;
import com.workorder.mapper.WorkOrderMapper;
import com.workorder.service.WorkOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReleaseTimeoutScheduler {

    private final WorkOrderMapper workOrderMapper;
    private final WorkOrderService workOrderService;

    @Scheduled(fixedRate = 60_000)
    public void scanAndReleaseTimeout() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(30);
        List<WorkOrder> timeoutOrders = workOrderMapper.selectList(
                new LambdaQueryWrapper<WorkOrder>()
                        .eq(WorkOrder::getStatus, "ACCEPTED")
                        .le(WorkOrder::getUpdatedAt, threshold));

        for (WorkOrder order : timeoutOrders) {
            try {
                workOrderService.releaseOrder(order.getId());
                log.info("超时释放成功: orderId={}, orderNo={}", order.getId(), order.getOrderNo());
            } catch (Exception e) {
                log.error("超时释放失败: orderId={}, error={}", order.getId(), e.getMessage());
            }
        }
    }
}
