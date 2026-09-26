package com.workorder.scheduler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code archiveJob} 的任务参数（xxl-job 控制台"任务参数"里填的那串）。
 *
 * <pre>
 * tables=consume_record,message_retry;retentionDays=30;batchSize=1000;maxBatches=20
 * </pre>
 *
 * <p><b>解析规则</b>：
 * <ul>
 *   <li>键值对用 {@code ;} 分隔，键与值用 {@code =} 分隔；空段忽略（容忍结尾多一个 {@code ;}）。</li>
 *   <li><b>未知键、未知表名、越界数字一律直接失败</b>（{@link IllegalArgumentException}）。
 *       这条是刻意的：一个把 {@code tables} 打成 {@code table} 的任务，如果"忽略未知键"，
 *       表现就是**每轮静默什么都不删**——正是本项目反复吃亏的那种静默失效。</li>
 *   <li>不填参数时用默认值：{@code tables=consume_record,message_retry}（两张 30 天口径的表）、
 *       {@code retentionDays=30}、{@code batchSize=1000}、{@code maxBatches=20}。
 *       **outbox 默认不在列表里**——它是 7 天口径且必须显式 opt-in（见 {@link ArchiveTarget#OUTBOX_SENT}）。</li>
 * </ul>
 *
 * @param targets      本轮要清理的白名单表（非空）
 * @param retentionDays 保留期（天）：只删锚点列早于 {@code now - retentionDays} 的行
 * @param batchSize    单批 {@code LIMIT}（1..5000）
 * @param maxBatches   单表单轮批数上限（1..1000）；用完就正常返回"未完，下一轮继续"
 */
public record ArchiveParams(List<ArchiveTarget> targets, int retentionDays, int batchSize, int maxBatches) {

    public static final String USAGE =
            "tables=consume_record,message_retry;retentionDays=30;batchSize=1000;maxBatches=20";

    private static final int MAX_BATCH_SIZE = 5000;
    private static final int MAX_MAX_BATCHES = 1000;
    private static final int MAX_RETENTION_DAYS = 3650;

    private static final List<ArchiveTarget> DEFAULT_TARGETS =
            List.of(ArchiveTarget.CONSUME_RECORD, ArchiveTarget.MESSAGE_RETRY);

    public ArchiveParams {
        targets = List.copyOf(targets);
    }

    /** 解析任务参数；{@code raw} 为 null/空白时全用默认值 */
    public static ArchiveParams parse(String raw) {
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
                    case "tables", "retentionDays", "batchSize", "maxBatches" -> kv.put(k, v);
                    // 未知键直接失败：见类注释"解析规则"
                    default -> throw new IllegalArgumentException(
                            "未知参数键: " + k + "（允许: tables/retentionDays/batchSize/maxBatches）；用法: " + USAGE);
                }
            }
        }

        List<ArchiveTarget> targets = parseTargets(kv.get("tables"));
        int retentionDays = parseBounded(kv.get("retentionDays"), "retentionDays", 30, 1, MAX_RETENTION_DAYS);
        int batchSize = parseBounded(kv.get("batchSize"), "batchSize", 1000, 1, MAX_BATCH_SIZE);
        int maxBatches = parseBounded(kv.get("maxBatches"), "maxBatches", 20, 1, MAX_MAX_BATCHES);
        return new ArchiveParams(targets, retentionDays, batchSize, maxBatches);
    }

    private static List<ArchiveTarget> parseTargets(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_TARGETS;
        }
        Set<ArchiveTarget> set = new LinkedHashSet<>();
        for (String name : raw.split(",")) {
            String n = name.trim();
            if (n.isEmpty()) {
                continue;
            }
            set.add(ArchiveTarget.byKey(n).orElseThrow(() -> new IllegalArgumentException(
                    "不在白名单里的表: " + n + "（允许: " + String.join(",", ArchiveTarget.allKeys()) + "）")));
        }
        if (set.isEmpty()) {
            throw new IllegalArgumentException("tables 解析后为空；用法: " + USAGE);
        }
        return new ArrayList<>(set);
    }

    private static int parseBounded(String raw, String name, int defaultValue, int min, int max) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        int v;
        try {
            v = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " 不是整数: " + raw);
        }
        if (v < min || v > max) {
            throw new IllegalArgumentException(name + " 越界（允许 " + min + ".." + max + "）: " + v);
        }
        return v;
    }

    /** 供日志/摘要里打印 */
    public String describe() {
        return "tables=" + String.join(",", targets.stream().map(ArchiveTarget::key).toList())
                + ";retentionDays=" + retentionDays + ";batchSize=" + batchSize + ";maxBatches=" + maxBatches;
    }

    /** 便于测试与日志：把 {@code tables=...} 还原成逗号分隔串 */
    public List<String> targetKeys() {
        return Arrays.stream(targets.toArray(new ArchiveTarget[0])).map(ArchiveTarget::key).toList();
    }
}
