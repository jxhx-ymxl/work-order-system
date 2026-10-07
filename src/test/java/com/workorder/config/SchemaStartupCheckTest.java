package com.workorder.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启动自检里**唯一有判断逻辑**的那段：把"缺了哪些"翻译成"该跑哪个脚本"。
 *
 * <p>为什么值得测：这条自检的全部价值就在"点名"上——缺表不点名脚本，等于让人再去翻一遍文档，
 * 那它和"任务失败后看 handle_msg"没有区别。所以判据是"缺 t_daily_report 必须点出 hotfix-p6-report.sql"。
 */
class SchemaStartupCheckTest {

    /** 全部必需项都在 */
    private static final Set<String> ALL_PRESENT = Set.of(
            "t_event_outbox", "t_consume_record", "t_message_retry", "t_archive_log", "t_job_watermark",
            "t_daily_report", "t_daily_report_part",
            "t_consume_record.idx_consumed_at", "t_message_retry.idx_created_at", "t_event_outbox.idx_status_sent_at",
            "t_work_order.triage_status", "t_notification.event_id",
            // 2026-10-08 部门实体化：t_dept 也是必需项（没跑 hotfix-dept.sql 就必须点名）
            "t_dept");

    @Test
    @DisplayName("结构完整时没有缺失项")
    void completeSchema() {
        assertEquals(List.of(), SchemaStartupCheck.describeMissing(ALL_PRESENT));
    }

    @Test
    @DisplayName("缺一项就点名一项，并给出**对应的**脚本（表 / 索引 / 列三种都要能点对）")
    void namesTheRightScript() {
        // 缺表：DROP TABLE t_daily_report 之后应该点 hotfix-p6-report.sql
        Set<String> missingTable = new HashSet<>(ALL_PRESENT);
        missingTable.remove("t_daily_report");
        List<String> lines = SchemaStartupCheck.describeMissing(missingTable);
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("t_daily_report"), lines.get(0));
        assertTrue(lines.get(0).contains("sql/hotfix-p6-report.sql"), lines.get(0));

        // 缺索引：idx_created_at 是**P6** 的脚本加的（不是 P4 建表脚本），必须点对
        Set<String> missingIndex = new HashSet<>(ALL_PRESENT);
        missingIndex.remove("t_message_retry.idx_created_at");
        List<String> indexLines = SchemaStartupCheck.describeMissing(missingIndex);
        assertEquals(1, indexLines.size());
        assertTrue(indexLines.get(0).contains("sql/hotfix-p6-archive.sql"), indexLines.get(0));

        // 缺列：P5 的提交路径列
        Set<String> missingColumn = new HashSet<>(ALL_PRESENT);
        missingColumn.remove("t_work_order.triage_status");
        List<String> columnLines = SchemaStartupCheck.describeMissing(missingColumn);
        assertEquals(1, columnLines.size());
        assertTrue(columnLines.get(0).contains("sql/hotfix-p5-triage-status.sql"), columnLines.get(0));
    }

    @Test
    @DisplayName("全缺时逐项列出（不是只报第一条）")
    void listsEveryMissingItem() {
        // 2026-10-08：12 → 13（新增 t_dept 一条）
        assertEquals(13, SchemaStartupCheck.describeMissing(Set.of()).size());
    }

    @Test
    @DisplayName("缺 t_dept 时点名 hotfix-dept.sql（部门实体化的必需项，2026-10-08）")
    void missingDeptPointsAtItsHotfix() {
        Set<String> missingDept = new HashSet<>(ALL_PRESENT);
        missingDept.remove("t_dept");

        List<String> lines = SchemaStartupCheck.describeMissing(missingDept);

        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("t_dept"), lines.get(0));
        assertTrue(lines.get(0).contains("sql/hotfix-dept.sql"), lines.get(0));
    }
}
