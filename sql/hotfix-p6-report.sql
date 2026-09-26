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
-- 用法（P6 步骤 3 起）：每个分片跑完只算自己那份（MOD(id, shardTotal) = shardIndex）写一行；
--   然后**每个分片都看"该日齐了没"**（齐备判据 = `COUNT(DISTINCT shard_index) == N` **且**所有行的
--   `shard_total` 一致且等于 N —— **不是**比行数，因为行数会被换分片数的残留行、以及并发收尾窗口里
--   败方补写的那一行抬高）；**谁发现齐了谁收尾**：同一事务里删该日 part 行（= 原子认领）
--   → 主表整天重算 → 推水位。没齐的分片打一行"等待其它分片（已有 k/N）"并正常返回（不是失败）。
--   ⚠ 这与"只让 shard 0 收尾"不同：后者在最坏情况下要晚一轮，且 shard 0 缺席就永远收不了尾；
--     并发收尾由"删 part 行"裁决，输家记为"已被其它分片收尾"（详见 docs/DECISIONS.md D71 第六节）。
--   ⚠ part 行是**进度与汇合信号**，不是数据的唯一来源：主表收尾时按整天重算，
--     这样才能保证"分片结果 == 单分片结果"（平均时长不是可加的量，不能把 part 相加）。
CREATE TABLE IF NOT EXISTS t_daily_report_part (
    report_date         DATE          NOT NULL COMMENT '汇总的那一天（+08 自然日）',
    shard_index         INT           NOT NULL COMMENT '分片下标(0 基)',
    shard_total         INT           NOT NULL DEFAULT 1 COMMENT '写这行时的分片总数；收尾时用它校验"齐了没有"——否则上一轮遗留的 part 行会让"行数==分片数"失真',
    created_count       INT           NOT NULL DEFAULT 0 COMMENT '本分片内的当日新增数',
    completed_count     INT           NOT NULL DEFAULT 0 COMMENT '本分片内的当日完结数',
    overdue_count       INT           NOT NULL DEFAULT 0 COMMENT '本分片内的当日时点逾期数',
    triage_done_count   INT           NOT NULL DEFAULT 0 COMMENT '本分片内的当日新增中 DONE 数',
    triage_failed_count INT           NOT NULL DEFAULT 0 COMMENT '本分片内的当日新增中 FAILED 数',
    generated_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '该分片写的时刻',
    PRIMARY KEY (report_date, shard_index)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='日报分片部分结果（进度/汇合信号；主表仍是对外唯一读取口径）';

-- 老库兼容：`CREATE TABLE IF NOT EXISTS` 对"表已存在但缺列"无能为力——
--   本脚本的上一版建的表没有 shard_total。这里按 information_schema 判断后补列（幂等）。
SET @db := DATABASE();
SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 't_daily_report_part' AND COLUMN_NAME = 'shard_total');
SET @ddl := IF(@has_col = 0,
    'ALTER TABLE t_daily_report_part ADD COLUMN shard_total INT NOT NULL DEFAULT 1 COMMENT ''写这行时的分片总数；收尾时用它校验"齐了没有"'' AFTER shard_index',
    'SELECT ''t_daily_report_part.shard_total 已存在，跳过'' AS note');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ----------------------------
-- ③ 验证（判据）
-- ----------------------------
-- ① 两张表在：
--      SHOW CREATE TABLE t_daily_report;        -- 期望 PRIMARY KEY (`report_date`)
--      SHOW CREATE TABLE t_daily_report_part;   -- 期望 PRIMARY KEY (`report_date`,`shard_index`) 且含 shard_total 列
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
