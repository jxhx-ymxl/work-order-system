-- ============================================================
-- 热修：P6 步骤 2 的日报表（t_daily_report + 步骤 3 预留的 t_daily_report_part）
--
-- 背景：@XxlJob("dailyReportJob") 每个自然日写一行汇总。缺表时任务一被触发就 handleFail（**不静默**）。
--   水位表 t_job_watermark **已在 P6 步骤 1 建好**（sql/hotfix-p6-archive.sql），这里直接复用，
--   job_key = 'daily-report'，**不重复建**。
--
-- 幂等性：CREATE TABLE IF NOT EXISTS —— 可安全重跑。
-- 影响面：新建两张空表，不触碰既有数据。
-- 执行顺序：必须在**重启后端之前**跑完（否则 dailyReportJob 一被触发就 handleFail）。
--   mysql -h127.0.0.1 -P3306 -uroot -p --default-character-set=utf8mb4 work_order < sql/hotfix-p6-report.sql
-- ============================================================

-- ----------------------------
-- ① 日报主表
-- ----------------------------
-- ══════════════════════════════════════════════════════════════════════════════
-- 【指标口径】为什么必须写在这里：这张表是给人看/给面试讲的，"数字怎么来的"是唯一需要复核的东西。
--   与 mapper/DailyReportMapper.xml 的口径注释**成对维护**，改一处必须改另一处。
--
--   时间归属：所有日界都由应用侧按 LocalDate(+08) 算好，以 [dayStart, dayEnd) 传入 SQL；
--             **SQL 里不用 CURDATE()/NOW() 当日界**（避免 UTC 与 +08 混用）。
--
--   逐列口径：
--     created_count        当日新增，按 t_work_order.created_at 归属
--     completed_count      当日完结，按 t_work_order_log(new_status='COMPLETED').created_at 归属
--                          （工单表没有 completed_at，完结时刻只在日志里）
--     avg_accept_minutes   平均接单时长（分钟）= AVG(接单事件时刻 - 工单创建时刻)，
--                          **分母 = 当日发生接单事件的单数**；当日无接单事件则为 NULL（不是 0）
--     avg_finish_minutes   平均完结时长（分钟），分母 = 当日完结的单数；同理可为 NULL
--     overdue_count        **时点口径**：该日 23:59:59 结束时，已过 sla_deadline 且仍未完结的单数。
--                          ⚠ 同一张长期逾期单会在多天各计一次——这是"每日快照"，不是"当日新增逾期"
--     triage_done_count    当日新增中 triage_status='DONE' 的数量（**按创建日归属**：表里没有分诊完成时刻）
--     triage_failed_count  同上，'FAILED'
--   幂等：同一天重跑 = 整行重算覆盖（INSERT ... ON DUPLICATE KEY UPDATE），不累积、不重复计数
-- ══════════════════════════════════════════════════════════════════════════════
CREATE TABLE IF NOT EXISTS t_daily_report (
    report_date         DATE PRIMARY KEY COMMENT '汇总的那一天（+08 自然日）',
    created_count       INT           NOT NULL DEFAULT 0 COMMENT '当日新增工单数（按 created_at 归属）',
    completed_count     INT           NOT NULL DEFAULT 0 COMMENT '当日完结工单数（按日志 new_status=COMPLETED 的时刻归属）',
    avg_accept_minutes  DECIMAL(10,2) NULL COMMENT '平均接单时长(分钟)=AVG(接单时刻-创建时刻)，分母=当日接单单数；当日无接单事件为 NULL',
    avg_finish_minutes  DECIMAL(10,2) NULL COMMENT '平均完结时长(分钟)=AVG(完结时刻-创建时刻)，分母=当日完结单数；当日无完结为 NULL',
    overdue_count       INT           NOT NULL DEFAULT 0 COMMENT '时点口径：当日 23:59:59 结束时已过 sla_deadline 且仍未完结的单数（同一张单可在多天各计一次）',
    triage_done_count   INT           NOT NULL DEFAULT 0 COMMENT '当日新增中 triage_status=DONE 的数量（按创建日归属）',
    triage_failed_count INT           NOT NULL DEFAULT 0 COMMENT '当日新增中 triage_status=FAILED 的数量（按创建日归属）',
    generated_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '该行最后一次重算时刻',
    shard_total         INT           NOT NULL DEFAULT 1 COMMENT '写入该行时的分片总数（P6 步骤 3 预留；本步固定 1）'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='日报汇总：每个自然日一行，同一天重跑整行重算（幂等）';

-- ----------------------------
-- ② 分片部分结果表（**P6 步骤 3 才用，本步只建表**）
-- ----------------------------
-- 为什么现在就建：步骤 3 的分片汇总要"各分片先写自己的部分结果，再合并成主表那一行"。
--   本步不分片（shardTotal 参数只接受不使用），先建好表，步骤 3 不必再发一次迁移。
-- 与主表的关系：(report_date, shard_index) 唯一；合并后主表仍是同一天一行（对外只读主表）。
CREATE TABLE IF NOT EXISTS t_daily_report_part (
    report_date         DATE          NOT NULL COMMENT '汇总的那一天（+08 自然日）',
    shard_index         INT           NOT NULL COMMENT '分片下标(0 基)',
    created_count       INT           NOT NULL DEFAULT 0 COMMENT '本分片内的当日新增数',
    completed_count     INT           NOT NULL DEFAULT 0 COMMENT '本分片内的当日完结数',
    overdue_count       INT           NOT NULL DEFAULT 0 COMMENT '本分片内的当日时点逾期数',
    triage_done_count   INT           NOT NULL DEFAULT 0 COMMENT '本分片内的当日新增中 DONE 数',
    triage_failed_count INT           NOT NULL DEFAULT 0 COMMENT '本分片内的当日新增中 FAILED 数',
    generated_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '该分片写的时刻',
    PRIMARY KEY (report_date, shard_index)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='日报分片部分结果（P6 步骤 3 用；主表仍是对外唯一读取口径）';

-- ----------------------------
-- ③ 验证（判据）
-- ----------------------------
-- ① 两张表在：
--      SHOW CREATE TABLE t_daily_report;        -- 期望 PRIMARY KEY (`report_date`)
--      SHOW CREATE TABLE t_daily_report_part;   -- 期望 PRIMARY KEY (`report_date`,`shard_index`)
-- ② 水位表复用（步骤 1 已建，本脚本**不应**再出现在建表列表里）：
--      SELECT TABLE_NAME FROM information_schema.TABLES
--       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN ('t_daily_report','t_daily_report_part','t_job_watermark');
--      期望：三行（t_job_watermark 来自步骤 1 的脚本）
-- ③ 汇总 SQL 的执行计划（在有数据的库上跑）：
--      EXPLAIN <把 DailyReportMapper.xml 的 SQL 贴进来，参数换成具体日期>；
--      期望：t_work_order 走 idx_created_at 或 idx_sla（range），t_work_order_log 走 idx_created；
--      ⚠ 与归档那轮同样的口径要求：**空表/小表上 key=NULL 是正常的**（优化器认为全扫更便宜）。
--
-- ----------------------------
-- ④ 回滚
-- ----------------------------
--   DROP TABLE t_daily_report_part;   -- 步骤 3 之前删掉它没有任何影响
--   DROP TABLE t_daily_report;        -- 注意：日报是**可重算**的，删了用补数模式（from/to）能重建
