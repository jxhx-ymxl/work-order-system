package com.workorder.scheduler;

import com.workorder.entity.WorkOrder;
import com.workorder.mapper.WorkOrderMapper;
import com.workorder.service.MessagePublishService;
import com.workorder.service.NotificationService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
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
 *
 * <h3>两个触发入口（P2 步骤 2b）：本地 {@code @Scheduled} 与调度中心 {@code @XxlJob} 并行</h3>
 * 方案 §P2 明确定为"**兜底默认开启、与调度中心并行**，靠乐观锁/SETNX 吸收重复"——所以
 * {@code @Scheduled} **不是遗留、不要顺手关掉**（它与 {@code ReleaseTimeoutScheduler} 同款理由，
 * 见那边的类注释：只留调度中心会把"调度中心挂掉也照常工作"这条保证退化成"只有 admin 健在才不丢"）。
 * 两路都调同一个 {@link #scanOnce(String)}，**靠"来源标记"区分双跑**。
 *
 * <h3>为什么不需要为"执行器是否存在"再加一个开关</h3>
 * {@code XxlJobSpringExecutor} 只在 {@code xxl.job.executor.enabled=true} 时装配（见 {@code XxlJobConfig}）；
 * **executor 不在时 {@code @XxlJob} 注解不生效**（处理器不会注册，调度中心也调不到它）。
 * 所以本地/CI 天然不受影响——**别再为此新增开关**，那只会多一处可能写歪的真相。
 *
 * <h3>在调度中心建这个任务时，四项配置必须显式设（危险区）</h3>
 * <ol>
 *   <li><b>阻塞策略 = 单机串行（Serial Execution）</b>：本轮的幂等依据只是"Redis SETNX 键 + SQL 状态"，
 *       **没打算靠"不重叠"来保证正确性**；但两轮扫描重叠会让同一个 handler 并发跑、日志与计数互相污染，
 *       排查时极难分辨。显式设串行，把"不重叠"变成配置事实。</li>
 *   <li><b>超时 = 300s</b>（release 扫描用 120s）：超时后 xxl-job 会标记失败并 {@code future.cancel(true)} 中断线程。
 *       扫描里每张单的通知各自独立、失败也会清掉幂等键留给下一轮，**中断不会留半截数据**；
 *       但**不要设成 0（永不超时）**——那会让卡死的一轮永远占着这个 handler，后续调度全部堵住。
 *       取值理由：单轮上限 {@code BATCH_SIZE=200}，本扫描比 release 扫描重（每单要 Redis SETNX + 发通知 + 可能两次 Redis 写），
 *       正常一轮仍是秒级，放宽到 300s 留足余量。</li>
 *   <li><b>失败重试次数 = 0</b>：扫描本身幂等，下一轮自然会再来；重试只会放大日志噪音。</li>
 *   <li><b>调度过期策略 = 忽略（DO_NOTHING）</b>：错过就错过，下一轮补——扫描是"状态驱动"（每次都重新查库），
 *       不依赖"每一轮都必须跑到"。</li>
 * </ol>
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
    public static final String SLA_NOTIFIED_KEY_PREFIX = "sla_notified:";
    public static final Duration SLA_NOTIFIED_TTL = Duration.ofHours(24);

    /**
     * 幂等键的构造入口（**public**，供 P5 的分诊链路复用）。
     *
     * <p>为什么对外暴露：H4 b-1 要求"分诊重算后已过期 → 立即告警，且计为 H1 的**首次告警**，24h 催办节奏自该时刻起算"。
     * 那意味着分诊链路必须写**同一个**去重键。若各自复制一份字符串常量，两处一旦写歪就会重复告警——
     * 这是本项目反复强调的"不要制造第二处定义"。
     */
    public static String notifiedKey(Long orderId) {
        return SLA_NOTIFIED_KEY_PREFIX + orderId;
    }

    private static final int BATCH_SIZE = 200;

    /** 触发来源：进程内 {@code @Scheduled} 兜底 */
    public static final String SOURCE_LOCAL = "local";

    /** 触发来源：调度中心 {@code @XxlJob} */
    public static final String SOURCE_XXL = "xxl";

    /** 本地兜底：每 5 分钟一轮（**保留**，理由见类注释"两个触发入口"） */
    @Scheduled(fixedRate = 300_000)
    public void scanSlaExpired() {
        scanOnce(SOURCE_LOCAL);
    }

    /** 调度中心入口：摘要写进 xxl-job 调度日志，控制台可见"这一轮通知了几张"。 */
    @XxlJob("slaEscalationScan")
    public void slaEscalationScan() {
        try {
            String summary = scanOnce(SOURCE_XXL);
            XxlJobHelper.log("{}", summary);
            XxlJobHelper.handleSuccess(summary);
        } catch (Exception e) {
            XxlJobHelper.log("[sla-scan] 触发来源=xxl 执行异常: {}", e.getMessage());
            XxlJobHelper.handleFail(e.getMessage());
        }
    }

    /**
     * 单轮扫描（两个入口共用）。**行为与改造前一致**：每张超时单都被尝试、单张失败不逃出本方法。
     *
     * <p><b>已知问题的痕迹（不要让下面那条 DEBUG 日志误导后来者）</b>：扫描 SQL（{@code findSlaExpired}）
     * **不会排除已通知过的工单**，所以已经发过通知的单子每一轮都会被重新查出来，再在下面被 Redis 幂等键挡掉。
     * 后果有二：① 每轮都产生"已通知，跳过"的日志行（本条已降为 DEBUG 以消噪音）；
     * ② **查询成本随积压工单数线性增长**（`BATCH_SIZE=200` 只是每次取回的上限，不改变扫描成本）。
     * **根治在 P4**：事件级幂等表建好后，扫描 SQL 直接排除已通知的工单（`ASYNC-SCHEDULING-PLAN.md` §5.2、P4）。
     * 在那之前，这里保持"查出后跳过"的老行为——降日志级别只是消噪音，**不代表问题已修好**。
     *
     * @param source 触发来源（{@link #SOURCE_LOCAL} / {@link #SOURCE_XXL}）——
     *               **双跑判据的载体**：日志里带上它才能分辨"这一轮是谁触发的"，
     *               进而证明"本地兜底与调度中心并行"真的成立（P2 步骤 2b 的验收）
     * @return 本轮摘要（供 {@code @XxlJob} 写进调度日志）
     */
    public String scanOnce(String source) {
        log.info("[sla-scan] 触发来源={} 开始扫描（单轮上限 {}）", source, BATCH_SIZE);

        List<WorkOrder> expired = workOrderMapper.findSlaExpired(BATCH_SIZE);
        log.info("SLA扫描: 发现{}条超时工单", expired.size());

        int notified = 0, skipped = 0, failed = 0;
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
                // DEBUG：每轮都会命中（扫描 SQL 不排除已通知工单），INFO 级别会把它变成日志噪音。
                // 这是"已知问题未被根治"的痕迹，不是"无需处理"——见方法注释。
                log.debug("SLA通知已发送过，跳过重复通知: orderId={}", orderId);
                skipped++;
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
                notified++;
            } catch (Exception e) {
                failed++;
                log.error("SLA升级通知发送失败: orderId={}", orderId, e);
                // 通知失败时删除幂等键，允许下个周期重试，避免永久漏发
                try {
                    redisTemplate.delete(key);
                } catch (Exception ex) {
                    log.warn("清理SLA幂等键失败: orderId={}", orderId, ex);
                }
            }
        }

        String summary = String.format("[sla-scan] 触发来源=%s 本轮通知 %d 条（候选 %d 跳过 %d 失败 %d）",
                source, notified, expired.size(), skipped, failed);
        log.info("{}", summary);
        return summary;
    }
}
