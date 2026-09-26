package com.workorder.scheduler;

import com.workorder.mapper.DailyReportPartSummary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 收尾条件（{@link DailyReportJob#canFinalize}）的单元测试。
 *
 * <p>为什么这一条必须单独测：它决定"要不要把 part 汇总成主表并推水位"。
 * 判松了 → 少一个分片的数据也会被当成完整（**数字错**）；判紧了 → 永远不收尾（**永远没有日报**）。
 * <p><b>判据用的是"分片下标覆盖"而不是"行数"</b>：part 表主键 (report_date, shard_index) 保证同一分片只有一行，
 * 而"行数"会被①上一轮换了分片数的遗留行、②并发收尾窗口里败方补写的那一行抬高。
 * 所以看 {@code distinctIndexes == N} + {@code shard_total} 唯一且等于 N。
 */
class DailyReportFinalizeTest {

    private static DailyReportPartSummary summary(int parts, int distinctIndexes, int distinctTotals, int minTotal) {
        DailyReportPartSummary s = new DailyReportPartSummary();
        s.setParts(parts);
        s.setDistinctIndexes(distinctIndexes);
        s.setDistinctTotals(distinctTotals);
        s.setMinTotal(minTotal);
        return s;
    }

    @Test
    @DisplayName("齐了才收尾：0..N-1 的下标都到齐 且 shard_total 唯一且等于 N")
    void ready() {
        assertTrue(DailyReportJob.canFinalize(1, summary(1, 1, 1, 1)));
        assertTrue(DailyReportJob.canFinalize(3, summary(3, 3, 1, 3)));
        // 有残留行（行数 4 > 分片数 3），但三个下标都到齐、shard_total 一致 → 仍然可以收尾
        assertTrue(DailyReportJob.canFinalize(3, summary(4, 3, 1, 3)));
    }

    @Test
    @DisplayName("没齐就不收尾：少下标、混了不同 shard_total 的残留行、下标够但 shard_total 不是本轮的分片数")
    void notReady() {
        // 只到了一个分片
        assertFalse(DailyReportJob.canFinalize(3, summary(1, 1, 1, 3)));
        assertFalse(DailyReportJob.canFinalize(3, summary(2, 2, 1, 3)));
        // 遗留行：上一轮 2 分片留下的 shard_index=2，本轮 shard_total=3 只到齐 0/1 → 下标只有两个不同的
        assertFalse(DailyReportJob.canFinalize(3, summary(3, 2, 2, 2)));
        // 三个下标都在，但 shard_total 混了两种取值（换过分辨率）→ 不能收尾
        assertFalse(DailyReportJob.canFinalize(3, summary(4, 3, 2, 2)));
        // 下标齐了，但都是上一轮的分片数（2），本轮是 3
        assertFalse(DailyReportJob.canFinalize(3, summary(3, 3, 1, 2)));
        // 完全没有 part 行（例如所有分片都还没跑）
        assertFalse(DailyReportJob.canFinalize(1, summary(0, 0, 0, 0)));
    }
}
