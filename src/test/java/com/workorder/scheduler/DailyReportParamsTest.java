package com.workorder.scheduler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 日报任务的**参数解析与日期区间**单元测试（纯单元：不连库、不起 executor）。
 *
 * <p>为什么这两件事必须测：它们出错都是**静默**的——
 * 参数键写错（{@code form=} 而不是 {@code from=}）会变成"以为在补数、其实什么都没算"；
 * 日期区间算错会变成"漏算一天"或"把今天算进去"（今天的数据还在写，算出来的数字会漂）。
 */
class DailyReportParamsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 27);
    private static final LocalDate YESTERDAY = TODAY.minusDays(1);   // 2026-09-26

    @Test
    @DisplayName("参数解析：留空=正常模式；from/to 缺省 to=from；未知键/非法日期/只给 to 一律拒绝")
    void parsesParams() {
        DailyReportParams normal = DailyReportParams.parse(null);
        assertFalse(normal.isBackfill());
        assertEquals(1, normal.shardTotal());
        assertFalse(DailyReportParams.parse("   ").isBackfill());

        DailyReportParams single = DailyReportParams.parse("from=2026-09-20");
        assertTrue(single.isBackfill());
        assertEquals(LocalDate.of(2026, 9, 20), single.from());
        assertEquals(single.from(), single.to());          // to 缺省 = from

        DailyReportParams range = DailyReportParams.parse("from=2026-09-20;to=2026-09-22;shardTotal=3");
        assertEquals(LocalDate.of(2026, 9, 22), range.to());
        assertEquals(3, range.shardTotal());

        assertThrows(IllegalArgumentException.class, () -> DailyReportParams.parse("form=2026-09-20"));
        assertThrows(IllegalArgumentException.class, () -> DailyReportParams.parse("from=2026/09/20"));
        assertThrows(IllegalArgumentException.class, () -> DailyReportParams.parse("to=2026-09-20"));
        assertThrows(IllegalArgumentException.class, () -> DailyReportParams.parse("from=2026-09-20;shardTotal=0"));
    }

    @Test
    @DisplayName("正常模式：水位 +1 到昨天；首次运行只算昨天；水位已到昨天则无事可做")
    void resolvesNormalRange() {
        DailyReportParams normal = DailyReportParams.parse(null);

        // 首次运行（无水印）：只算昨天，历史不回填
        assertEquals(List.of(YESTERDAY), DailyReportParams.resolveDays(normal, null, TODAY, false));

        // 水位 = 3 天前 → 算 3 天前+1 .. 昨天
        assertEquals(List.of(LocalDate.of(2026, 9, 25), YESTERDAY),
                DailyReportParams.resolveDays(normal, LocalDate.of(2026, 9, 24), TODAY, false));

        // 水位已是昨天 → 空集合（不重算、也不报错）
        assertEquals(List.of(), DailyReportParams.resolveDays(normal, YESTERDAY, TODAY, false));

        // 水位比昨天还新（手工往前跳过）→ 同样空集合，不会被拉回来
        assertEquals(List.of(), DailyReportParams.resolveDays(normal, TODAY, TODAY, false));
    }

    @Test
    @DisplayName("自愈：水位那天没有结果行时，从水位那天重算")
    void repairsMissingWatermarkDay() {
        DailyReportParams normal = DailyReportParams.parse(null);
        List<LocalDate> days = DailyReportParams.resolveDays(normal, LocalDate.of(2026, 9, 24), TODAY, true);
        // 起点回到水位当天（9-24），而不是 9-25
        assertEquals(List.of(LocalDate.of(2026, 9, 24), LocalDate.of(2026, 9, 25), YESTERDAY), days);
    }

    @Test
    @DisplayName("补数模式：from..to 逐日；含今天/未来一律拒绝（今天数据还在写）")
    void resolvesBackfillRange() {
        DailyReportParams backfill = DailyReportParams.parse("from=2026-09-24;to=2026-09-26");
        assertEquals(List.of(LocalDate.of(2026, 9, 24), LocalDate.of(2026, 9, 25), LocalDate.of(2026, 9, 26)),
                DailyReportParams.resolveDays(backfill, null, TODAY, false));

        // 单日补数（= 修某一天）
        DailyReportParams oneDay = DailyReportParams.parse("from=2026-09-25;to=2026-09-25");
        assertEquals(List.of(LocalDate.of(2026, 9, 25)),
                DailyReportParams.resolveDays(oneDay, LocalDate.of(2026, 9, 26), TODAY, false));

        // 含今天：拒绝
        DailyReportParams withToday = DailyReportParams.parse("from=2026-09-26;to=2026-09-27");
        assertThrows(IllegalArgumentException.class,
                () -> DailyReportParams.resolveDays(withToday, null, TODAY, false));

        // from > to：拒绝
        DailyReportParams reversed = DailyReportParams.parse("from=2026-09-26;to=2026-09-24");
        assertThrows(IllegalArgumentException.class,
                () -> DailyReportParams.resolveDays(reversed, null, TODAY, false));
    }
}
