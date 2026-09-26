package com.workorder.scheduler;

import com.workorder.common.enums.ReleaseResult;
import com.workorder.entity.WorkOrder;
import com.workorder.mapper.WorkOrderMapper;
import com.workorder.service.WorkOrderService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 *
 * <h3>两个触发入口（P2 步骤 2b）：本地 {@code @Scheduled} 与调度中心 {@code @XxlJob} 并行</h3>
 * 方案 §P2 明确定为"**兜底默认开启、与调度中心并行**，靠乐观锁/SETNX 吸收重复"——所以
 * {@code @Scheduled} **不是遗留、不要顺手关掉**：把 {@code @Scheduled} 换成只由调度中心触发之后，
 * "停 MQ 仍能释放"这条保证会退化成"MQ 挂、**且 admin 健在**才不丢"（方案 §P2 记录的那条可靠性净倒退），
 * 本地兜底正是它的缓解措施之一。两路都调同一个 {@link #scanOnce(String)}，**靠"来源标记"区分双跑**。
 *
 * <h3>为什么不需要为"执行器是否存在"再加一个开关</h3>
 * {@code XxlJobSpringExecutor} 只在 {@code xxl.job.executor.enabled=true} 时装配（见 {@code XxlJobConfig}）；
 * **executor 不在时 {@code @XxlJob} 注解不生效**（处理器不会注册，调度中心也调不到它）。
 * 所以本地/CI 天然不受影响——**别再为此新增开关**，那只会多一处可能写歪的真相。
 *
 * <h3>在调度中心建这个任务时，四项配置必须显式设（危险区）</h3>
 * <ol>
 *   <li><b>阻塞策略 = 单机串行（Serial Execution）</b>：本轮的幂等依据只是"乐观锁 + Redis SETNX"，
 *       **没打算靠"不重叠"来保证正确性**；但两轮扫描重叠会让同一个 handler 并发跑、日志与计数互相污染，
 *       排查时极难分辨。显式设串行，把"不重叠"变成配置事实。</li>
 *   <li><b>超时 = 120s</b>（SLA 扫描用 300s）：超时后 xxl-job 会标记失败并 {@code future.cancel(true)} 中断线程。
 *       扫描里每张单的写都在自己的事务里，**中断不会留半截数据**；但**不要设成 0（永不超时）**——
 *       那会让卡死的一轮永远占着这个 handler，后续调度全部堵住。
 *       取值理由：单轮上限 {@code BATCH_SIZE=200}，正常一轮是秒级，120s 已是两个数量级余量
 *       （SLA 扫描还含 Redis 与逐条通知，放宽到 300s）。</li>
 *   <li><b>失败重试次数 = 0</b>：扫描本身幂等，下一轮自然会再来；重试只会放大日志噪音。</li>
 *   <li><b>调度过期策略 = 忽略（DO_NOTHING）</b>：错过就错过，下一轮补——扫描是"状态驱动"（每次都重新查库），
 *       不依赖"每一轮都必须跑到"。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReleaseTimeoutScheduler {

    /** 单轮处理上限：与 {@code SlaEscalationScheduler} 一致，避免一次大堆积占满调度线程；其余下一轮继续 */
    private static final int BATCH_SIZE = 200;

    /** 配置缺失检测的单轮上限：只需留痕，不需要全量打印 */
    private static final int MISSING_CONFIG_LOG_LIMIT = 50;

    /** 触发来源：进程内 {@code @Scheduled} 兜底 */
    public static final String SOURCE_LOCAL = "local";

    /** 触发来源：调度中心 {@code @XxlJob} */
    public static final String SOURCE_XXL = "xxl";

    private final WorkOrderMapper workOrderMapper;
    private final WorkOrderService workOrderService;

    /** 本地兜底：每 60s 一轮（**保留**，理由见类注释"两个触发入口"） */
    @Scheduled(fixedRate = 60_000)
    public void scanAndReleaseTimeout() {
        scanOnce(SOURCE_LOCAL);
    }

    /** 调度中心入口：摘要写进 xxl-job 调度日志，控制台可见"这一轮释放了几张"。 */
    @XxlJob("releaseTimeoutScan")
    public void releaseTimeoutScan() {
        try {
            String summary = scanOnce(SOURCE_XXL);
            XxlJobHelper.log("{}", summary);
            XxlJobHelper.handleSuccess(summary);
        } catch (Exception e) {
            XxlJobHelper.log("[release-scan] 触发来源=xxl 执行异常: {}", e.getMessage());
            XxlJobHelper.handleFail(e.getMessage());
        }
    }

    /**
     * 单轮扫描（两个入口共用）。**行为与改造前一致**：每张超时单都被尝试、异常不逃出本方法。
     *
     * @param source 触发来源（{@link #SOURCE_LOCAL} / {@link #SOURCE_XXL}）——
     *               **双跑判据的载体**：日志里带上它才能分辨"这一轮是谁触发的"，
     *               进而证明"本地兜底与调度中心并行"真的成立（P2 步骤 2b 的验收）
     * @return 本轮摘要（供 {@code @XxlJob} 写进调度日志）
     */
    public String scanOnce(String source) {
        log.info("[release-scan] 触发来源={} 开始扫描（单轮上限 {}）", source, BATCH_SIZE);

        List<WorkOrder> timeoutOrders = workOrderMapper.findAcceptTimeoutOrders(BATCH_SIZE);

        int released = 0, skipped = 0, errored = 0;
        for (WorkOrder order : timeoutOrders) {
            try {
                ReleaseResult result = workOrderService.releaseOrder(order.getId());
                // 行为不变：无论哪种结果都继续扫下一张；变化只在"跳过"从静默变成了显式结果。
                switch (result) {
                    case RELEASED -> {
                        released++;
                        log.info("超时释放成功: orderId={}, orderNo={}", order.getId(), order.getOrderNo());
                    }
                    case SKIPPED -> {
                        skipped++;
                        log.debug("超时释放跳过（状态守卫未命中，工单状态已变）: orderId={}, orderNo={}",
                                order.getId(), order.getOrderNo());
                    }
                    case ERROR -> {
                        errored++;
                        log.error("超时释放内部出错（需人工核查）: orderId={}, orderNo={}",
                                order.getId(), order.getOrderNo());
                    }
                }
            } catch (Exception e) {
                // DB 层故障等仍走这里：记错误、继续扫下一张（不因为一张单失败而中断整批）
                errored++;
                log.error("超时释放失败: orderId={}, error={}", order.getId(), e.getMessage());
            }
        }

        // 配置缺失：这类工单**不会**被释放（不发明默认时限），但必须留痕。
        // 这里刻意保持 ERROR 且每轮都报——它是**配置缺陷**，与 SLA 扫描那条"正常业务现象"的噪音处理相反：
        // 后者（已通知的工单每轮被重复查出）可以降到 DEBUG，前者必须一直刺眼，直到有人补齐 t_sla_config。
        List<Long> missingConfigOrderIds = workOrderMapper.findAcceptedOrdersWithoutSlaConfig(MISSING_CONFIG_LOG_LIMIT);
        int missingConfig = missingConfigOrderIds.size();
        if (!missingConfigOrderIds.isEmpty()) {
            log.error("[release] {} 张 ACCEPTED 工单的 type+priority 在 t_sla_config 中查不到 accept_minutes，"
                            + "本轮不释放它们（不发明默认时限），补齐配置后自动恢复：orderIds={}",
                    missingConfig, missingConfigOrderIds);
        }

        String summary = String.format("[release-scan] 触发来源=%s 本轮释放 %d 条（候选 %d 跳过 %d 出错 %d 缺配置 %d）",
                source, released, timeoutOrders.size(), skipped, errored, missingConfig);
        log.info("{}", summary);
        return summary;
    }
}
