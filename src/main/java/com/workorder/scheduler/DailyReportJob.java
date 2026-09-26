package com.workorder.scheduler;

import com.workorder.mapper.DailyReportMapper;
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
            if (params.shardTotal() > 1) {
                // 本步不分片：显式说出来，免得有人以为配了 shardTotal=3 就真的分片了
                XxlJobHelper.log("[daily-report] 注意：本步未实现分片，shardTotal={} 只是被记进列里", params.shardTotal());
            }
            LocalDate today = LocalDate.now();
            LocalDate watermark = dailyReportMapper.selectWatermark(JOB_KEY);
            boolean watermarkRowMissing = watermark != null && dailyReportMapper.countReportRow(watermark) == 0;
            List<LocalDate> days = DailyReportParams.resolveDays(params, watermark, today, watermarkRowMissing);
            if (watermarkRowMissing) {
                XxlJobHelper.log("[daily-report] 自愈：水位日 {} 没有结果行，从该日重算", watermark);
            }

            String summary = runDays(params, days, watermark);
            XxlJobHelper.log("{}", summary);
            XxlJobHelper.handleSuccess(summary);
        } catch (Exception e) {
            XxlJobHelper.log("[daily-report] 执行失败: {}", e.toString());
            XxlJobHelper.handleFail(e.getMessage());
        }
    }

    /**
     * 逐日汇总（测试也直接调它，不必起 executor）。
     *
     * @param days 已经算好的日期集合（升序）；空集合 = 没有新的一天要算，属正常情况
     * @return 摘要（写进调度日志与 handleSuccess）
     */
    public String runDays(DailyReportParams params, List<LocalDate> days, LocalDate watermarkBefore) {
        boolean advanceWatermark = !params.isBackfill();
        int written = 0;
        LocalDate lastDay = null;
        for (LocalDate day : days) {
            dailyReportWriter.writeDay(day, JOB_KEY, advanceWatermark, params.shardTotal());
            written++;
            lastDay = day;
            XxlJobHelper.log("[daily-report] date={} 写完（{}）", day, advanceWatermark ? "水位已推进" : "补数，不动水位");
            log.info("[daily-report] date={} 汇总完成 advanceWatermark={}", day, advanceWatermark);
        }

        String tail;
        if (days.isEmpty()) {
            tail = "没有需要汇总的日期（水位=" + watermarkBefore + "，只算到昨天）";
        } else if (advanceWatermark) {
            tail = "水位 " + watermarkBefore + " → " + lastDay;
        } else {
            tail = "补数模式：水位保持 " + watermarkBefore + " 不变";
        }
        String summary = String.format("[daily-report] %s 本轮汇总 %d 天（%s..%s）；%s",
                params.describe(), written,
                days.isEmpty() ? "-" : days.get(0).toString(),
                days.isEmpty() ? "-" : lastDay.toString(), tail);
        log.info("{}", summary);
        return summary;
    }
}
