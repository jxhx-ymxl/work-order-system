package com.workorder.mapper;

import lombok.Data;

/**
 * 某一天 {@code t_daily_report_part} 的"齐了没有"摘要（{@code selectPartSummary} 的返回值）。
 *
 * <p><b>齐备判据看的是 {@code distinctIndexes}，不是 {@code parts}</b>：part 表主键是
 * (report_date, shard_index)，同一分片只会有一行；而 {@code parts} 会被两类东西抬高——
 * 上一轮换了分片数的遗留行、以及并发收尾窗口里败方补写的那一行。
 * 所以"0..N-1 每个下标都到齐（{@code distinctIndexes == N}）+ 所有行的 shard_total 一致"才是判据。
 */
@Data
public class DailyReportPartSummary {

    /** 该日 part 行数 */
    private int parts;

    /** 该日出现过几个不同的 shard_index（**齐备判据用这个**） */
    private int distinctIndexes;

    /** 该日 part 行里出现过几种 shard_total（>1 说明混了不同分片数的残留行） */
    private int distinctTotals;

    /** 该日 part 行里最小的 shard_total（没有行时为 0） */
    private int minTotal;
}
