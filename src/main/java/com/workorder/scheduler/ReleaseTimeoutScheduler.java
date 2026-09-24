package com.workorder.scheduler;

import com.workorder.common.enums.ReleaseResult;
import com.workorder.entity.WorkOrder;
import com.workorder.mapper.WorkOrderMapper;
import com.workorder.service.WorkOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 兜底释放扫描（每 60s 一轮）。**释放的权威通道**——MQ 只是"更早触发"的优化路径。
 *
 * <p><b>时限来源（P1 步骤 5 收敛）</b>：每张工单的时限取自它自己的 {@code type+priority} 在
 * {@code t_sla_config.accept_minutes} 里的取值，**这里不再有任何硬编码时限**——
 * 注解里的 60s 是**扫描频率**，与"多长时间算超时"是两件事（G5/I8 的教训就是把它们混为一谈）。
 *
 * <p><b>配置查不到时（含兜底组合也缺）</b>：这些工单**不会被释放**（不发明默认时限，与
 * {@code publishReleaseCheck} 同口径：缺配置时宁可"不动作"），但会单独查出来记 ERROR。
 * 少了这段 ERROR，"配置缺失"就表现成"这张单永远挂在那儿"——与 I4 的静默失效同类。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReleaseTimeoutScheduler {

    /** 单轮处理上限：与 {@code SlaEscalationScheduler} 一致，避免一次大堆积占满调度线程；其余下一轮继续 */
    private static final int BATCH_SIZE = 200;

    /** 配置缺失检测的单轮上限：只需留痕，不需要全量打印 */
    private static final int MISSING_CONFIG_LOG_LIMIT = 50;

    private final WorkOrderMapper workOrderMapper;
    private final WorkOrderService workOrderService;

    @Scheduled(fixedRate = 60_000)
    public void scanAndReleaseTimeout() {
        List<WorkOrder> timeoutOrders = workOrderMapper.findAcceptTimeoutOrders(BATCH_SIZE);

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

        // 配置缺失：这类工单**不会**被释放（不发明默认时限），但必须留痕。
        // 这里刻意保持 ERROR 且每轮都报——它是**配置缺陷**，与 SLA 扫描那条"正常业务现象"的噪音处理相反：
        // 后者（已通知的工单每轮被重复查出）可以降到 DEBUG，前者必须一直刺眼，直到有人补齐 t_sla_config。
        List<Long> missingConfigOrderIds = workOrderMapper.findAcceptedOrdersWithoutSlaConfig(MISSING_CONFIG_LOG_LIMIT);
        if (!missingConfigOrderIds.isEmpty()) {
            log.error("[release] {} 张 ACCEPTED 工单的 type+priority 在 t_sla_config 中查不到 accept_minutes，"
                            + "本轮不释放它们（不发明默认时限），补齐配置后自动恢复：orderIds={}",
                    missingConfigOrderIds.size(), missingConfigOrderIds);
        }
    }
}
