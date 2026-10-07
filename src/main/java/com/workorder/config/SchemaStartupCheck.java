package com.workorder.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 启动自检（第四类：**迁移是否跑过**）：一次性核对 P4/P5/P6 必需的表、索引与列，
 * 缺哪个就**点名"该跑哪个脚本"**。
 *
 * <p><b>为什么要有它</b>：这两轮连续踩了两次"漏一步"——漏跑迁移时，后台任务一被触发就
 * {@code handleFail}（`Table 'work_order.t_archive_log' doesn't exist`），
 * 而 xxl-job **既不会停任务、也不会自动报警**（见 D72）。等到人发现时，任务已经静默失败了很多轮。
 * 这条自检把"漏跑迁移"从"事后翻日志"变成"启动那一刻就点名"。
 *
 * <p><b>为什么不阻止启动</b>（与 {@link LlmStartupCheck} 同一条理由）：缺表影响的是**后台任务**
 * （归档清理、日报汇总），**不等于服务不可用**——提交工单、接单、释放这些主链路照样能跑。
 * 让服务起来 + 让问题可见，比让整个服务起不来更合适。
 * <b>注意与另外两类语义的区别</b>：
 * <ul>
 *   <li>{@link DataSourceAvailabilityCheck}（连不上库）→ **必须 fail-fast**：连不上库的应用没有价值；</li>
 *   <li>{@link SlaConfigStartupCheck}（配置缺行）→ 记 error、不中止；</li>
 *   <li>本类（缺表/索引/列）→ 记 error、不中止，但**点名脚本**。</li>
 * </ul>
 *
 * <p>构造函数注入 {@link DataSourceAvailabilityCheck} 是**故意的**（与 {@code SlaConfigStartupCheck} 同一手法）：
 * 保证"依赖可用性"先跑，否则这里会先撞上连不上库的异常，报错方向会指向"结构缺失"而不是"连不上库"。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SchemaStartupCheck {

    /** 一项必需的结构：类别 + 键 + 缺失时该跑哪个脚本 */
    private record Requirement(String kind, String key, String script) {
    }

    /**
     * 必需项清单。**加一条就要同时想清楚两件事**：它是谁写的（哪支脚本）、
     * 以及它缺了以后"哪个功能会静默失败"。
     *
     * <p>键的格式：表 = {@code 表名}；索引 = {@code 表名.索引名}；列 = {@code 表名.列名}。
     */
    private static final List<Requirement> REQUIREMENTS = List.of(
            // ── 表：P1 / P4 / P6 ──
            new Requirement("表", "t_event_outbox", "sql/hotfix-p1-outbox-init.sql（+ sql/hotfix-outbox-sending-state.sql）"),
            new Requirement("表", "t_consume_record", "sql/hotfix-p4-consume-record.sql"),
            new Requirement("表", "t_message_retry", "sql/hotfix-p4-message-retry.sql"),
            new Requirement("表", "t_archive_log", "sql/hotfix-p6-archive.sql"),
            new Requirement("表", "t_job_watermark", "sql/hotfix-p6-archive.sql"),
            new Requirement("表", "t_daily_report", "sql/hotfix-p6-report.sql"),
            new Requirement("表", "t_daily_report_part", "sql/hotfix-p6-report.sql"),
            // ── 表：2026-10-08 部门实体化。**刻意加这一条**：没跑 hotfix-dept.sql 的库必须在启动自检里
            //    点名"缺 t_dept、该跑哪支脚本"——那是**判据**，不是故障（服务照常启动，见 D109）。
            new Requirement("表", "t_dept", "sql/hotfix-dept.sql"),
            // ── 索引：归档清理的 WHERE 靠它们走索引（缺了不报错，但会退化成全表扫） ──
            new Requirement("索引", "t_consume_record.idx_consumed_at", "sql/hotfix-p4-consume-record.sql"),
            new Requirement("索引", "t_message_retry.idx_created_at", "sql/hotfix-p6-archive.sql"),
            new Requirement("索引", "t_event_outbox.idx_status_sent_at", "sql/hotfix-p6-archive.sql"),
            // ── 列：P5 的两列是**提交路径**必需的（缺了提交直接 SQL 报错，不是静默降级） ──
            new Requirement("列", "t_work_order.triage_status", "sql/hotfix-p5-triage-status.sql"),
            new Requirement("列", "t_notification.event_id", "sql/hotfix-p5-submit-notification.sql")
    );

    private final DataSource dataSource;

    /** 仅用于强制 Bean 创建顺序：依赖可用性检查必须先跑（见类注释） */
    private final DataSourceAvailabilityCheck dataSourceAvailabilityCheck;

    @PostConstruct
    public void verifySchemaComplete() {
        try (Connection conn = dataSource.getConnection()) {
            Set<String> present = loadPresentKeys(conn);
            List<String> missing = describeMissing(present);
            if (missing.isEmpty()) {
                log.info("[启动自检] 数据库结构完整：{} 项必需的表/索引/列全部就位（P4/P5/P6 迁移已跑过）",
                        REQUIREMENTS.size());
            } else {
                log.error("[启动自检] 数据库结构缺失 {} 项，对应的后台任务会持续失败（**不阻止启动**，"
                                + "但修好之前一直不可用）：\n  {}\n"
                                + "  排查/补齐步骤见 deploy/DEPLOY-RUNBOOK.md（迁移脚本按序跑，每支都幂等，重跑安全）",
                        missing.size(), String.join("\n  ", missing));
            }
        } catch (Exception e) {
            // 不阻止启动：连不上库由 DataSourceAvailabilityCheck 更早、更准确地报错；
            // 这里的异常（例如 information_schema 查询本身出错）只记 error，避免把一个"检查器故障"升级成"服务不可用"。
            log.error("[启动自检] 数据库结构校验未能完成（不阻止启动）：{}", e.getMessage());
        }
    }

    /** 逐项查 information_schema，返回**已存在**的键集合 */
    private Set<String> loadPresentKeys(Connection conn) throws SQLException {
        Set<String> present = new HashSet<>();
        for (Requirement r : REQUIREMENTS) {
            if (exists(conn, r)) {
                present.add(r.key());
            }
        }
        return present;
    }

    private boolean exists(Connection conn, Requirement r) throws SQLException {
        String sql = switch (r.kind()) {
            case "表" -> "SELECT COUNT(*) FROM information_schema.TABLES "
                    + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?";
            case "索引" -> "SELECT COUNT(*) FROM information_schema.STATISTICS "
                    + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?";
            case "列" -> "SELECT COUNT(*) FROM information_schema.COLUMNS "
                    + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?";
            default -> throw new IllegalStateException("未知的检查类别: " + r.kind());
        };
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if ("表".equals(r.kind())) {
                ps.setString(1, r.key());
            } else {
                String[] parts = r.key().split("\\.", 2);
                ps.setString(1, parts[0]);
                ps.setString(2, parts[1]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    /**
     * 生成"缺什么 + 该跑哪个脚本"的清单（**纯函数，便于单测**）。
     *
     * @param presentKeys 已存在的键（格式见 {@link #REQUIREMENTS}）
     * @return 每项一行；为空 = 结构完整
     */
    static List<String> describeMissing(Set<String> presentKeys) {
        List<String> missing = new ArrayList<>();
        for (Requirement r : REQUIREMENTS) {
            if (!presentKeys.contains(r.key())) {
                missing.add(r.kind() + " " + r.key() + " 不存在/缺失 → 跑 " + r.script());
            }
        }
        return missing;
    }
}
