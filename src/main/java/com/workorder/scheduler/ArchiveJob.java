package com.workorder.scheduler;

import com.workorder.mapper.ArchiveMapper;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * P6 归档/清理任务：把三张"只增不减"的表按保留期**分批删除**，参数与分片全部走调度中心。
 *
 * <pre>
 * 任务参数（控制台"任务参数"栏）：
 *   tables=consume_record,message_retry;retentionDays=30;batchSize=1000;maxBatches=20
 * </pre>
 *
 * <h3>四条硬约束（改这个类之前先读完）</h3>
 * <ol>
 *   <li><b>只在白名单表上删</b>：{@link ArchiveTarget} 就是白名单（t_consume_record / t_message_retry /
 *       t_event_outbox 的 SENT 行）。**业务表一概不碰**——工单、日志、通知、SLA 配置不在枚举里，
 *       参数写破天也删不到。</li>
 *   <li><b>不引入 id 水位（watermark）</b>：方案 §5.6 最初写的是"水位线 + 区间均分"，本任务**不采用**。
 *       理由：水位线会把"记了什么"和"删了什么"变成两件事——一旦某轮"水位推进了但删除失败或被中断"，
 *       那段区间就**永久漏删**，而且没有任何地方会报错。改用"每轮重新按时间筛"之后，
 *       状态只有数据自己（consumed_at &lt; cutoff），漏删的唯一可能是"这一轮没跑"，下一轮自然补上。
 *       代价：每轮都要重新扫一遍索引区间（比水位线多读，但走索引、且分摊在 N 个分片上）。
 *       t_job_watermark 表**仍然建着**（步骤 2 的报表任务可能用），但归档删除**不用它**。</li>
 *   <li><b>分片条件不改变删除范围</b>：MOD(id, shardTotal) = shardIndex 只是"这一轮由谁删"，
 *       谓词仍然是"锚点列早于 cutoff"。每个分片每轮都重新筛自己的那一份，所以**并行安全、不重不漏**；
 *       也正因为如此，分片数变了、某几个分片这轮没跑，**都不会漏删**（下一轮按新的分片数重新筛）。
 *       代价：单个分片要扫过约 shardTotal 倍于它删除行数的候选行（MOD 用不上索引），
 *       这是"不重不漏"换来的、可接受的读放大。</li>
 *   <li><b>随时可中断</b>：一批 = 一条 DELETE = 一个事务（自动提交），删完就落盘。
 *       没有水位线，所以不存在"记了没删 / 删了没记"的中间态；进程被杀、任务被 xxl-job 的
 *       future.cancel(true) 打断，最坏结果都是"这一轮少删了一批"，下一轮继续。</li>
 * </ol>
 *
 * <h3>预算用尽不是失败</h3>
 * 单表最多 maxBatches 批（默认 20 批 x 1000 行 = 2 万行/轮）。用完就**正常返回**
 * "本轮删除 N 行，未完下一轮继续"（handleSuccess），因为这是"状态驱动、下一轮补"的预期行为，
 * 报成失败会让人误以为要人工介入。**判据在 t_archive_log.outcome**：
 * DONE（删完了）/ BUDGET_EXHAUSTED（预算用完未完）/ FAILED（异常）。
 *
 * <h3>为什么不再加一个开关</h3>
 * 它是 @XxlJob：XxlJobSpringExecutor 只在 xxl.job.executor.enabled=true 时装配，
 * **executor 不在时这个 handler 根本不注册**，调度中心也调不到——本地/CI/未接调度中心的环境天然不受影响
 * （与 {@link ReleaseTimeoutScheduler} / {@link SlaEscalationScheduler} 同一条理由）。
 *
 * <h3>控制台侧要配的东西（见 deploy/UPGRADE-P6.md）</h3>
 * 路由策略用**分片广播**（否则 getShardTotal() 恒为 1，只有一台机器在删）；
 * 阻塞策略**丢弃后续调度**（清理类任务宁可少跑也不重叠，且它幂等）；
 * 任务超时按 maxBatches x batchSize 估（默认 2 万行/表，给 600s 有余量）；
 * 失败重试 0（下一轮自然补）；调度过期 DO_NOTHING。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ArchiveJob {

    /** 留痕表里的 job_key 前缀；完整值 = archive:&lt;短表名&gt;（表身份编码在这里，留痕表没有独立的表名列） */
    public static final String JOB_KEY_PREFIX = "archive:";

    private final ArchiveMapper archiveMapper;

    /** 调度中心入口。异常一律 handleFail，让控制台看得到；预算用尽**不算异常**。 */
    @XxlJob("archiveJob")
    public void archiveJob() {
        try {
            ArchiveParams params = ArchiveParams.parse(XxlJobHelper.getJobParam());
            int shardTotal = XxlJobShards.normalizeTotal(XxlJobHelper.getShardTotal());
            int shardIndex = XxlJobShards.normalizeIndex(XxlJobHelper.getShardIndex(), shardTotal);

            XxlJobHelper.log("[archive] 开始：{} shard={}/{}", params.describe(), shardIndex, shardTotal);
            String summary = runOnce(params, shardIndex, shardTotal);
            XxlJobHelper.log("{}", summary);
            XxlJobHelper.handleSuccess(summary);
        } catch (Exception e) {
            // 参数写错、表不存在、DB 异常都走这里：**失败要响**（静默失效最贵）
            XxlJobHelper.log("[archive] 执行失败: {}", e.toString());
            XxlJobHelper.handleFail(e.getMessage());
        }
    }

    /**
     * 单轮执行（@XxlJob 入口调用；测试也直接调它，不必起 broker 或 admin）。
     *
     * @param shardIndex 本分片下标（0 基）
     * @param shardTotal 分片总数（1 = 不分片）
     * @return 摘要（含"本轮删除 N 行"与"未完/已删完"，写进调度日志与 handleSuccess）
     */
    public String runOnce(ArchiveParams params, int shardIndex, int shardTotal) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(params.retentionDays());

        long totalDeleted = 0;
        List<String> perTable = new ArrayList<>();
        boolean anyUnfinished = false;

        for (ArchiveTarget target : params.targets()) {
            long startedAt = System.currentTimeMillis();
            long deleted = 0;
            int batches = 0;
            boolean unfinished = false;

            for (int i = 0; i < params.maxBatches(); i++) {
                int rows = deleteBatch(target, cutoff, shardIndex, shardTotal, params.batchSize());
                batches++;
                deleted += rows;
                XxlJobHelper.log("[archive] {} 第 {} 批删除 {} 行（cutoff={} shard={}/{}）",
                        target.key(), batches, rows, cutoff, shardIndex, shardTotal);
                if (rows < params.batchSize()) {
                    // 这一批没删满 = 已经没有符合条件的行了
                    break;
                }
                if (batches >= params.maxBatches()) {
                    // 删满了预算：可能还有，属于预期（下一轮继续），不是失败
                    unfinished = true;
                }
            }

            long durationMs = System.currentTimeMillis() - startedAt;
            String outcome = unfinished ? "BUDGET_EXHAUSTED" : "DONE";
            archiveMapper.insertArchiveLog(JOB_KEY_PREFIX + target.key(), shardIndex, shardTotal,
                    cutoff, deleted, durationMs, outcome);
            log.info("[archive] {} 删除 {} 行（{} 批，{} ms，outcome={}）谓词: {}",
                    target.table(), deleted, batches, durationMs, outcome, target.predicateDescription());

            totalDeleted += deleted;
            anyUnfinished |= unfinished;
            perTable.add(target.key() + "=" + deleted + (unfinished ? "(未完)" : ""));
        }

        String tail = anyUnfinished ? "预算用完，未完下一轮继续" : "已删完（本轮无剩余）";
        String summary = String.format("[archive] shard=%d/%d cutoff=%s 本轮删除 %d 行（%s）；%s",
                shardIndex, shardTotal, cutoff, totalDeleted, String.join(", ", perTable), tail);
        log.info("{}", summary);
        return summary;
    }

    private int deleteBatch(ArchiveTarget target, LocalDateTime cutoff,
                            int shardIndex, int shardTotal, int batchSize) {
        // 三条静态 SQL：表名不来自参数，所以这里不需要（也不允许）做字符串拼接
        return switch (target) {
            case CONSUME_RECORD -> archiveMapper.deleteConsumeRecordBefore(cutoff, shardIndex, shardTotal, batchSize);
            case MESSAGE_RETRY -> archiveMapper.deleteMessageRetryBefore(cutoff, shardIndex, shardTotal, batchSize);
            case OUTBOX_SENT -> archiveMapper.deleteOutboxSentBefore(cutoff, shardIndex, shardTotal, batchSize);
        };
    }

    // 分片归一化在 XxlJobShards（与 DailyReportJob 共用；别在这里再实现一份）
}
