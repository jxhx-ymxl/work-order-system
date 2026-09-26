package com.workorder.scheduler;

import com.workorder.mapper.DailyReportMapper;
import com.workorder.mapper.DailyReportPartSummary;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * P6 步骤 2：**日报汇总**（{@code @XxlJob("dailyReportJob")}）。
 *
 * <pre>
 * 正常模式（留空）：   从水位日 + 1 天算到昨天
 * 补数模式（人工）：   from=2026-09-20;to=2026-09-22   （照算照写，**不动水位**）
 * </pre>
 *
 * <h3>三条硬约束</h3>
 * <ol>
 *   <li><b>今天永远不算</b>：今天的数据还在写，现在算出来的数字下一分钟就变（口径漂移）；
 *       而且一旦把水位推到今天，"明天再算今天"这条路就被堵死了。补数模式同样拒绝含今天的区间。</li>
 *   <li><b>水位与结果行同事务</b>（{@link DailyReportWriter}）：先写结果、后推水位。
 *       反过来的话，崩在中间 = 那天永久漏算且没人会再算它。</li>
 *   <li><b>日期归一天只有一处</b>：所有"哪一天"都由本类用 {@link LocalDate} 算好，
 *       以 {@code [dayStart, dayEnd)} 传给 SQL；SQL 里**不出现 CURDATE()/NOW() 当日界**
 *       （那会把 UTC 与 +08 混进来）。DB 与应用都已设 +08（见 D71 的口径说明）。</li>
 * </ol>
 *
 * <h3>自愈：水位那天没有行时，从那天重算</h3>
 * 正常模式的起点本来是"水位 + 1 天"，但开跑前会查一次 {@code t_daily_report} 里**水位那天有没有行**；
 * 没有（有人手工删了行、或库被回滚到中间态）就从水位那天重算——这是"同事务"之外的第二道保险。
 * 注意范围：它只补**缺失的行**；"行在但值被改错"要靠补数模式（{@code from=to=那天}）重算，那是设计内的用法。
 *
 * <h3>与归档任务的错峰</h3>
 * 两个任务都吃 IO：`archiveJob` 建议 03:30、`dailyReportJob` 建议 04:30（见 `deploy/UPGRADE-P6.md`）。
 *
 * <h3>分片：写 part → 谁发现"齐了"谁收尾（P6 步骤 3）</h3>
 * <ol>
 *   <li><b>每个分片</b>只算 {@code MOD(id, shardTotal) = shardIndex} 的那一份，UPSERT 自己的
 *       {@code t_daily_report_part} 行（带 {@code shard_total}）。</li>
 *   <li><b>每个分片都检查"齐了没"</b>：对每个待汇总日期校验
 *       "该日 part 行 {@code shard_total} 一致且 {@code COUNT(*) == shardTotal}"。
 *       不满足 → 打一行 {@code 等待其它分片（已有 k/N）} 并**正常返回**（不是失败，下一轮再来）；
 *       满足 → **按整天重算主表那行 + 删该日 part + 推水位（三者同一事务）**。</li>
 * </ol>
 *
 * <p><b>这里没有 barrier，这不是妥协而是设计</b>：xxl-job 的分片广播是"同时触发 N 个执行器、各自跑完即返回"，
 * **没有汇合点**，所以收尾只能靠"轮询式汇合"：每个分片写完后看一眼齐了没，齐了就收尾。
 * 由此推出三条本设计要守住的性质：
 * <ol>
 *   <li><b>谁发现齐了谁收尾</b>（不固定某个分片）：同时触发时，<b>最后跑完的那个分片</b>当场收尾，
 *       一轮就收敛；若固定"只有 shard 0 收尾"，最坏情况要等下一轮，而且**shard 0 那台机器缺席就永远收不了尾**。</li>
 *   <li><b>并发收尾由"删 part 行"裁决</b>（见 {@link DailyReportWriter#finalizeDay}）：两个分片同时看到齐了，
 *       也只有一个能删满 N 行，另一个当"已被别人收尾"跳过——**不是失败**。</li>
 *   <li><b>不齐绝不收尾</b>：宁可晚一轮，也不把少分片的数字写进主表（缺一个分片的 part 只写了一半的数据）。</li>
 * </ol>
 * 所以看到"等待其它分片"是正常日志，不是告警。
 *
 * <p><b>换分片数时的残留</b>：某天若有上一轮遗留的 part 行（比如上一轮 3 分片、这一轮 2 分片），
 * 齐备校验会**永远不满足**（行数与 shard_total 都对不上）。此时人工清一下那一天：
 * {@code DELETE FROM t_daily_report_part WHERE report_date = '<那天>';} 再跑一轮即可。
 *
 * <h3>为什么不再加开关</h3>
 * 它是 {@code @XxlJob}：executor 未装配时 handler 不注册，本地/CI 天然不受影响
 * （与 {@link ArchiveJob} / {@link ReleaseTimeoutScheduler} / {@link SlaEscalationScheduler} 同一条理由）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DailyReportJob {

    /** 水位表的 job_key（与 P6 步骤 1 建好的那张表共用） */
    public static final String JOB_KEY = "daily-report";

    private final DailyReportMapper dailyReportMapper;
    private final DailyReportWriter dailyReportWriter;

    /** 调度中心入口 */
    @XxlJob("dailyReportJob")
    public void dailyReportJob() {
        try {
            DailyReportParams params = DailyReportParams.parse(XxlJobHelper.getJobParam());
            // 分片数的唯一来源是执行器广播（路由策略=分片广播）；参数里的 shardTotal 只在本地直调时兜底
            int shardTotal = XxlJobShards.normalizeTotal(XxlJobHelper.getShardTotal());
            int shardIndex = XxlJobShards.normalizeIndex(XxlJobHelper.getShardIndex(), shardTotal);
            if (params.shardTotal() != shardTotal) {
                XxlJobHelper.log("[daily-report] 注意：分片数以执行器广播为准（{}），任务参数里的 shardTotal={} 被忽略",
                        shardTotal, params.shardTotal());
            }
            LocalDate today = LocalDate.now();
            LocalDate watermark = dailyReportMapper.selectWatermark(JOB_KEY);
            boolean watermarkRowMissing = watermark != null && dailyReportMapper.countReportRow(watermark) == 0;
            List<LocalDate> days = DailyReportParams.resolveDays(params, watermark, today, watermarkRowMissing);
            if (watermarkRowMissing) {
                XxlJobHelper.log("[daily-report] 自愈：水位日 {} 没有结果行，从该日重算", watermark);
            }

            String summary = runDays(params, days, watermark, shardIndex, shardTotal);
            XxlJobHelper.log("{}", summary);
            XxlJobHelper.handleSuccess(summary);
        } catch (Exception e) {
            XxlJobHelper.log("[daily-report] 执行失败: {}", e.toString());
            XxlJobHelper.handleFail(e.getMessage());
        }
    }

    /**
     * 逐日跑：**每个分片都写自己的 part 行；只有 shard 0 尝试收尾**。
     *
     * @param days 已经算好的日期集合（升序）；空集合 = 没有新的一天要算，属正常情况
     * @return 摘要（写进调度日志与 handleSuccess）
     */
    public String runDays(DailyReportParams params, List<LocalDate> days, LocalDate watermarkBefore,
                          int shardIndex, int shardTotal) {
        boolean advanceWatermark = !params.isBackfill();
        int finalized = 0;
        int waiting = 0;
        int lostRace = 0;
        LocalDate lastDay = null;
        for (LocalDate day : days) {
            // ① 所有分片都写自己那一份 part
            dailyReportWriter.writePart(day, shardIndex, shardTotal);
            // ② 齐了就收尾（不齐就等下一轮）
            DailyReportPartSummary summary = dailyReportMapper.selectPartSummary(day);
            if (!canFinalize(shardTotal, summary)) {
                waiting++;
                XxlJobHelper.log("[daily-report] date={} 等待其它分片（已有 {}/{}）", day, summary.getParts(), shardTotal);
                log.info("[daily-report] date={} 等待其它分片（已有 {}/{}, distinctTotals={}）",
                        day, summary.getParts(), shardTotal, summary.getDistinctTotals());
                continue;
            }
            try {
                dailyReportWriter.finalizeDay(day, JOB_KEY, advanceWatermark, shardTotal);
                finalized++;
                lastDay = day;
                XxlJobHelper.log("[daily-report] date={} 收尾完成（{}，分片 {}/{}）",
                        day, advanceWatermark ? "水位已推进" : "补数，不动水位", shardIndex, shardTotal);
                log.info("[daily-report] date={} 收尾完成 advanceWatermark={} shard={}/{}",
                        day, advanceWatermark, shardIndex, shardTotal);
            } catch (DailyReportWriter.PartClaimLostException e) {
                // 另一个分片同时看到"齐了"并抢先认领：正常结局，不是失败
                lostRace++;
                XxlJobHelper.log("[daily-report] date={} 已被其它分片收尾（本分片跳过）", day);
                log.info("[daily-report] date={} 已被其它分片收尾: {}", day, e.getMessage());
            }
        }

        String tail;
        if (days.isEmpty()) {
            tail = "没有需要汇总的日期（水位=" + watermarkBefore + "，只算到昨天）";
        } else if (waiting > 0 && finalized == 0) {
            tail = "等其它分片，本轮未收尾（下一轮再来）";
        } else if (finalized == 0 && lostRace > 0) {
            tail = "本轮有 " + lostRace + " 天已被其它分片收尾（本分片无新进展）";
        } else if (advanceWatermark) {
            tail = "水位 " + watermarkBefore + " → " + (lastDay == null ? "-" : lastDay);
        } else {
            tail = "补数模式：水位保持 " + watermarkBefore + " 不变";
        }
        String summary = String.format("[daily-report] %s shard=%d/%d 本轮收尾 %d 天、等待 %d 天、被抢先 %d 天（%s..%s）；%s",
                params.describe(), shardIndex, shardTotal, finalized, waiting, lostRace,
                days.isEmpty() ? "-" : days.get(0).toString(),
                days.isEmpty() ? "-" : days.get(days.size() - 1).toString(), tail);
        log.info("{}", summary);
        return summary;
    }

    /**
     * 收尾条件（**纯函数**，便于单测）：该日 part 行必须
     * ① **出现过的 shard_index 个数**等于本轮分片数（0..N-1 都到齐）；② {@code shard_total} 只有一种取值；
     * ③ 那个取值就是本轮分片数。
     *
     * <p>为什么是"下标个数"而不是"行数"：part 表主键是 (report_date, shard_index)，同一分片只会有一行；
     * 行数则会被①上一轮换了分片数的遗留行、②并发收尾窗口里败方补写的那一行抬高。用下标覆盖比用行数准。
     */
    static boolean canFinalize(int shardTotal, DailyReportPartSummary summary) {
        return summary.getDistinctIndexes() == shardTotal
                && summary.getDistinctTotals() == 1
                && summary.getMinTotal() == shardTotal;
    }
}
