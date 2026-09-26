package com.workorder.scheduler;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code dailyReportJob} 的任务参数。
 *
 * <pre>
 * 正常模式（增量）：  （留空）
 * 补数模式（人工）：  from=2026-09-20;to=2026-09-22
 * </pre>
 *
 * <p><b>两种模式的区别只有一条：动不动水位</b>。
 * <ul>
 *   <li><b>正常模式</b>：从"水位日 + 1 天"算到<b>昨天</b>。**今天永远不算**——今天的数据还在写，
 *       现在算出来的数字下一分钟就变了（口径漂移），而且水位推进到今天会把"明天再算今天"的路堵死。</li>
 *   <li><b>补数模式</b>（传了 {@code from}）：照算照写，但**不动水位**。
 *       理由：补数会往回算（把水位往回拉）或往前跳（跳过中间没算的天），两种都会破坏水位的单调性。</li>
 * </ul>
 *
 * <p>{@code shardTotal} 参数**只做本地兜底**：真正生效的分片数来自执行器广播
 * （{@code XxlJobHelper.getShardTotal()}，路由策略=分片广播时才有意义）。两者不一致时以广播为准并打日志。
 *
 * @param from      补数起点（null = 正常模式）
 * @param to        补数终点（null 且 from 非空时 = from，即只补一天）
 * @param shardTotal 分片总数（本步忽略）
 */
public record DailyReportParams(LocalDate from, LocalDate to, int shardTotal) {

    public static final String USAGE = "（正常模式留空）或 from=2026-09-20;to=2026-09-22";

    private static final int MAX_SHARD_TOTAL = 64;

    public boolean isBackfill() {
        return from != null;
    }

    /** 解析任务参数；{@code raw} 为 null/空白 = 正常模式 */
    public static DailyReportParams parse(String raw) {
        Map<String, String> kv = new LinkedHashMap<>();
        if (raw != null) {
            for (String segment : raw.split(";")) {
                String s = segment.trim();
                if (s.isEmpty()) {
                    continue;
                }
                int eq = s.indexOf('=');
                if (eq <= 0) {
                    throw new IllegalArgumentException("参数段无法解析（应为 key=value）: " + s + "；用法: " + USAGE);
                }
                String k = s.substring(0, eq).trim();
                String v = s.substring(eq + 1).trim();
                switch (k) {
                    case "from", "to", "shardTotal" -> kv.put(k, v);
                    // 未知键失败：否则 from 打成 form 就变成"静默不补数"
                    default -> throw new IllegalArgumentException(
                            "未知参数键: " + k + "（允许: from/to/shardTotal）；用法: " + USAGE);
                }
            }
        }
        LocalDate from = parseDate(kv.get("from"), "from");
        LocalDate to = parseDate(kv.get("to"), "to");
        if (from == null && to != null) {
            throw new IllegalArgumentException("只给 to 没给 from：要么都不给（正常模式），要么都给；用法: " + USAGE);
        }
        int shardTotal = parseShardTotal(kv.get("shardTotal"));
        return new DailyReportParams(from, to == null ? from : to, shardTotal);
    }

    private static LocalDate parseDate(String raw, String name) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(name + " 不是合法日期（应为 yyyy-MM-dd）: " + raw);
        }
    }

    private static int parseShardTotal(String raw) {
        if (raw == null || raw.isBlank()) {
            return 1;
        }
        int v;
        try {
            v = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("shardTotal 不是整数: " + raw);
        }
        if (v < 1 || v > MAX_SHARD_TOTAL) {
            throw new IllegalArgumentException("shardTotal 越界（允许 1.." + MAX_SHARD_TOTAL + "）: " + v);
        }
        return v;
    }

    /**
     * 算出本轮要汇总的日期集合（**纯函数，便于单测**）。
     *
     * @param watermark           {@code t_job_watermark} 里 {@code daily-report} 的水位；null = 还没跑过
     * @param today               今天（由调用方给，测试可控）
     * @param watermarkRowMissing 水位那天在 {@code t_daily_report} 里**没有行**（自愈判据，见下）
     */
    public static List<LocalDate> resolveDays(DailyReportParams p, LocalDate watermark,
                                              LocalDate today, boolean watermarkRowMissing) {
        LocalDate yesterday = today.minusDays(1);

        if (p.isBackfill()) {
            LocalDate to = p.to();
            if (p.from().isAfter(to)) {
                throw new IllegalArgumentException("from 晚于 to: " + p.from() + " > " + to);
            }
            if (!to.isBefore(today)) {
                // 今天（含更晚）一律拒绝：今天的数据还在写，算出来的数字会漂
                throw new IllegalArgumentException("补数区间不能包含今天或未来（today=" + today + ", to=" + to + "）");
            }
            return datesBetween(p.from(), to);
        }

        LocalDate start;
        if (watermark == null) {
            // 首次运行：只算昨天。历史不回填——要历史就显式用 from/to，避免"第一次跑就把两年的数据全算一遍"
            start = yesterday;
        } else if (watermarkRowMissing) {
            // 自愈：水位说"算到 D 了"，但 D 的结果行不见了 → 从 D 重算。
            // 这是"同事务"之外的第二道保险：即使有人手工删了行/库被回滚到中间态，下一轮也能恢复
            start = watermark;
        } else {
            start = watermark.plusDays(1);
        }
        if (start.isAfter(yesterday)) {
            return List.of();
        }
        return datesBetween(start, yesterday);
    }

    private static List<LocalDate> datesBetween(LocalDate startInclusive, LocalDate endInclusive) {
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate d = startInclusive; !d.isAfter(endInclusive); d = d.plusDays(1)) {
            days.add(d);
        }
        return days;
    }

    /** 供日志 */
    public String describe() {
        return isBackfill()
                ? "from=" + from + ";to=" + to + ";shardTotal=" + shardTotal
                : "（正常模式）shardTotal=" + shardTotal;
    }
}
