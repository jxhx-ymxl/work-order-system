package com.workorder.scheduler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 归档任务的**参数与分片**单元测试（纯单元，不起 Spring、不连库）。
 *
 * <p>为什么值得测：这两个东西出错的表现都是**静默**的——
 * 参数键写错（{@code table=} 而不是 {@code tables=}）会变成"每轮什么都不删"；
 * 分片数没给对会让某几个分片永远不干活。所以判据必须是"解析失败要响"，而不是"能跑就行"。
 */
class ArchiveParamsTest {

    @Test
    @DisplayName("不填参数：默认两张 30 天口径的表，outbox 不在默认里（它是 7 天口径，必须显式 opt-in）")
    void defaultsWhenBlank() {
        ArchiveParams p = ArchiveParams.parse(null);
        assertEquals(List.of("consume_record", "message_retry"), p.targetKeys());
        assertEquals(30, p.retentionDays());
        assertEquals(1000, p.batchSize());
        assertEquals(20, p.maxBatches());
        // 空白串与 null 同义
        assertEquals(p.targetKeys(), ArchiveParams.parse("   ").targetKeys());
    }

    @Test
    @DisplayName("解析控制台参数串：短名/全表名都认，outbox 可显式加进来，数字可覆盖")
    void parsesConsoleParam() {
        ArchiveParams p = ArchiveParams.parse(
                "tables=consume_record,t_message_retry;retentionDays=30;batchSize=1000;maxBatches=20");
        assertEquals(List.of("consume_record", "message_retry"), p.targetKeys());

        ArchiveParams withOutbox = ArchiveParams.parse(
                "tables=outbox_sent;retentionDays=7;batchSize=500;maxBatches=1;");
        assertEquals(List.of("outbox_sent"), withOutbox.targetKeys());
        assertEquals(7, withOutbox.retentionDays());
        assertEquals(500, withOutbox.batchSize());
        assertEquals(1, withOutbox.maxBatches());
    }

    @Test
    @DisplayName("未知键 / 未知表名 / 越界数字 / 非法段 一律抛错（不静默跳过）")
    void rejectsBadInput() {
        // 把 tables 打成 table：如果"忽略未知键"，表现是每轮静默不删——必须响
        IllegalArgumentException unknownKey =
                assertThrows(IllegalArgumentException.class, () -> ArchiveParams.parse("table=consume_record"));
        assertTrue(unknownKey.getMessage().contains("table"), unknownKey.getMessage());

        IllegalArgumentException unknownTable =
                assertThrows(IllegalArgumentException.class, () -> ArchiveParams.parse("tables=t_work_order"));
        assertTrue(unknownTable.getMessage().contains("t_work_order"), unknownTable.getMessage());

        assertThrows(IllegalArgumentException.class, () -> ArchiveParams.parse("tables=consume_record;batchSize=0"));
        assertThrows(IllegalArgumentException.class, () -> ArchiveParams.parse("tables=consume_record;maxBatches=99999"));
        assertThrows(IllegalArgumentException.class, () -> ArchiveParams.parse("tables=consume_record;retentionDays=abc"));
        assertThrows(IllegalArgumentException.class, () -> ArchiveParams.parse("consume_record"));
    }

    @Test
    @DisplayName("分片归一化：没有分片上下文按单分片；越界直接失败（不静默不干活）")
    void shardNormalization() {
        // 本地直接调用 runOnce / 不分片部署时，调度中心给的是 -1
        assertEquals(1, ArchiveJob.normalizeShardTotal(-1));
        assertEquals(1, ArchiveJob.normalizeShardTotal(0));
        assertEquals(0, ArchiveJob.normalizeShardIndex(-1, 1));
        assertEquals(2, ArchiveJob.normalizeShardTotal(2));
        assertEquals(1, ArchiveJob.normalizeShardIndex(1, 2));
        assertThrows(IllegalArgumentException.class, () -> ArchiveJob.normalizeShardIndex(2, 2));
    }
}
