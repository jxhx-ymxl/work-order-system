package com.workorder.scheduler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.workorder.common.enums.ReleaseResult;
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
                ReleaseResult result = workOrderService.releaseOrder(order.getId());
                // 行为不变：无论哪种结果都继续扫下一张；变化只在"跳过"从静默变成了显式结果。
                switch (result) {
                    case RELEASED -> log.info("超时释放成功: orderId={}, orderNo={}", order.getId(), order.getOrderNo());
                    case SKIPPED -> log.debug("超时释放跳过（状态守卫未命中，工单状态已变）: orderId={}, orderNo={}",
                            order.getId(), order.getOrderNo());
                    case ERROR -> log.error("超时释放内部出错（需人工核查）: orderId={}, orderNo={}",
                            order.getId(), order.getOrderNo());
                }
            } catch (Exception e) {
                // DB 层故障等仍走这里：记错误、继续扫下一张（不因为一张单失败而中断整批）
                log.error("超时释放失败: orderId={}, error={}", order.getId(), e.getMessage());
            }
        }
    }
}
