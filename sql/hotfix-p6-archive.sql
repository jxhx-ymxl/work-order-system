-- ============================================================
-- 热修：P6 归档/清理任务的配套 DDL（留痕表 + 预留水位表 + 缺的索引）
--
-- 背景：交付 P6 步骤 1 的 @XxlJob("archiveJob") 之前，库里必须先有：
--   ① t_archive_log     —— 每（表 × 分片 × 轮次）一行的留痕（谁在什么时候删了多少、结果如何）
--   ② t_job_watermark   —— **本步只建表**，给步骤 2 的报表任务预留；归档删除**不用水位线**（理由见 D70）
--   ③ 两个索引 + 一个防缺索引 —— 让归档的 WHERE 走索引而不是全表扫
--   缺表时任务会直接失败（handleFail，红色）；缺索引不会失败，但会在数据量大时拖慢在线库。
--
-- ⚠ 为什么这些表不在 sql/init.sql 里再抄一遍：**两处维护必然漂移**。本文件是这两个表的唯一 DDL 出处，
--   init.sql 里只留了一行指针。**因此：新库也必须跑一次本脚本**（顺序：init.sql → 本脚本）。
--
-- 幂等性：全部可重复执行——建表用 CREATE TABLE IF NOT EXISTS；建索引用 information_schema 先判断再拼 DDL
--   （MySQL 8 不支持 ADD INDEX IF NOT EXISTS），已应用过则输出"已存在，跳过"，影响 0 行、不报错。
--   这是 CLAUDE.md §4 的硬要求：老库的迁移脚本必须能安全重跑。
--
-- 影响面（本机实测 2026-09-27，空库/小表）：
--   · 新建 2 张空表：不触碰既有数据；
--   · t_message_retry ADD INDEX idx_created_at：小表秒级；线上该表量级与 t_consume_record 同级
--     （日均 300–800 单 → 约 1–2.5 万行/月），仍在秒级；建议低峰执行；
--   · t_event_outbox ADD INDEX idx_status_sent_at：同上（该表约 1–2.5 万行/月）。
--
-- 执行顺序：
--   mysql -h127.0.0.1 -P3306 -uroot -p --default-character-set=utf8mb4 work_order < sql/hotfix-p6-archive.sql
--   ⚠ 必须在**重启后端之前**跑完（否则 archiveJob 一被触发就 handleFail）。
-- ============================================================

-- ----------------------------
-- ① 归档留痕表
-- ----------------------------
-- 用途：每次（表 × 分片 × 轮次）执行写一行。它回答三个运维问题：
--   "这个任务还在跑吗"（ran_at）／"每轮删了多少、是不是卡住了"（deleted_rows / duration_ms）／
--   "上一轮是删完了还是预算用完"（outcome）。
-- 表身份编码在 job_key 里（archive:consume_record 之类）：**不设独立的表名列**，避免同一信息两处存。
-- 保留期：本表自身也会长——量级极小（3 表 × 分片数 × 轮次），暂不纳入归档；若日后分片多、频率高，
--   把 archive_log 自己加进白名单即可（它是"只增不减"的同一类表，但这一步先不给自己开口子）。
CREATE TABLE IF NOT EXISTS t_archive_log (
    id           BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '自增主键',
    job_key      VARCHAR(64) NOT NULL COMMENT '任务+表标识: archive:<短表名>，例如 archive:consume_record（本表无表名列，表身份编码在这里）',
    ran_at       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '本轮开始时刻',
    shard_index  INT         NOT NULL DEFAULT 0 COMMENT '本分片下标(0 基)；不分片时为 0',
    shard_total  INT         NOT NULL DEFAULT 1 COMMENT '分片总数；1 = 不分片',
    cutoff       DATETIME    NOT NULL COMMENT '保留期截断点：本轮只删锚点列 < cutoff 的行',
    deleted_rows BIGINT      NOT NULL DEFAULT 0 COMMENT '本（表×分片×轮次）实际删除行数',
    duration_ms  BIGINT      NOT NULL DEFAULT 0 COMMENT '本轮该表的耗时(毫秒)',
    outcome      VARCHAR(32) NOT NULL COMMENT '结果: DONE 已删完 / BUDGET_EXHAUSTED 预算用完未完(下一轮继续，不是失败) / FAILED 异常',
    INDEX idx_job_ran (job_key, ran_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='归档/清理任务的留痕：每(表×分片×轮次)一行';

-- ----------------------------
-- ② 预留：任务水位线表（**本步只建表，归档删除不用它**）
-- ----------------------------
-- 为什么先建着：步骤 2 的报表任务是"跨月区间聚合"，那种"按区间推进"的语义需要水位线；
--   而**归档删除刻意不用**（水位线 + 区间均分会导致"推进了但没删成 = 永久漏删"，见 D70）。
-- 为什么建在这份脚本里：与 t_archive_log 同属 P6 的配套表，一处交付、一处核对。
CREATE TABLE IF NOT EXISTS t_job_watermark (
    job_key        VARCHAR(64) NOT NULL COMMENT '任务标识(如 report:monthly)',
    watermark_date DATE        NOT NULL COMMENT '已处理到的水位（语义由各任务自定）',
    updated_at     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '水位最近推进时刻',
    PRIMARY KEY (job_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务水位线（P6 步骤 2 报表任务用；归档删除不用水位线）';

-- ----------------------------
-- ③ 索引补齐（归档 WHERE 必须走索引）
-- ----------------------------
-- 三条 DELETE 的形状（见 com.workorder.mapper.ArchiveMapper，表名写死在 SQL 里）：
--   t_consume_record : WHERE consumed_at < ? AND MOD(id, ?) = ? LIMIT ?
--   t_message_retry  : WHERE created_at  < ? AND MOD(id, ?) = ? LIMIT ?
--   t_event_outbox   : WHERE status='SENT' AND sent_at < ? AND MOD(id, ?) = ? LIMIT ?
-- 注意 MOD(...) 用不上索引（它是"由谁删"的条件，不是"删什么"的条件）——
--   索引的作用域只到**时间区间**，这一点在 EXPLAIN 里看得最清楚：key=idx_xxx，Extra 里带 filter。
SET @db := DATABASE();

-- t_message_retry.idx_created_at：这张表原本只有 uk_event_consumer 与 idx_retry_dispatch(status, next_retry_at)，
--   **没有 created_at 上的索引** → 按 created_at 清理会全表扫。必须补。
SET @has := (SELECT COUNT(*) FROM information_schema.STATISTICS
             WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 't_message_retry' AND INDEX_NAME = 'idx_created_at');
SET @ddl := IF(@has = 0,
    'ALTER TABLE t_message_retry ADD INDEX idx_created_at (created_at)',
    'SELECT ''t_message_retry.idx_created_at 已存在，跳过'' AS note');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- t_event_outbox.idx_status_sent_at：现有 idx_dispatch(status, next_retry_at) 只能用上 status 前缀，
--   SENT 行累积后（它就是本任务要删的那批）范围会越来越大 → 补一个 (status, sent_at)。
SET @has := (SELECT COUNT(*) FROM information_schema.STATISTICS
             WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 't_event_outbox' AND INDEX_NAME = 'idx_status_sent_at');
SET @ddl := IF(@has = 0,
    'ALTER TABLE t_event_outbox ADD INDEX idx_status_sent_at (status, sent_at)',
    'SELECT ''t_event_outbox.idx_status_sent_at 已存在，跳过'' AS note');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- t_consume_record.idx_consumed_at：init.sql 里本来就有；这里只是**防老库缺**（缺了就是全表扫），
--   同样用 information_schema 先判断，避免"以为有、其实没有"。
SET @has := (SELECT COUNT(*) FROM information_schema.STATISTICS
             WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 't_consume_record' AND INDEX_NAME = 'idx_consumed_at');
SET @ddl := IF(@has = 0,
    'ALTER TABLE t_consume_record ADD INDEX idx_consumed_at (consumed_at)',
    'SELECT ''t_consume_record.idx_consumed_at 已存在，跳过'' AS note');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ----------------------------
-- ④ 验证（判据）
-- ----------------------------
-- ① 两张表建上了：
--      SHOW CREATE TABLE t_archive_log;      -- 期望含 INDEX `idx_job_ran` (job_key, ran_at)
--      SHOW CREATE TABLE t_job_watermark;    -- 期望 PRIMARY KEY (`job_key`)
-- ② 三个索引都在（三列 index_name 应各出现一行）：
--      SELECT TABLE_NAME, INDEX_NAME, COLUMN_NAME FROM information_schema.STATISTICS
--       WHERE TABLE_SCHEMA = DATABASE()
--         AND INDEX_NAME IN ('idx_created_at','idx_status_sent_at','idx_consumed_at');
-- ③ 归档的 DELETE 走索引 —— **必须在有数据的表上跑**：
--      空表（或行数极少）时优化器会认为全表扫更便宜，EXPLAIN 会给 key=NULL，
--      那是**优化器的正常选择，不是索引没建上**。判据认 `key=` 那一列：
--      EXPLAIN DELETE FROM t_consume_record WHERE consumed_at < NOW() - INTERVAL 30 DAY AND MOD(id, 1) = 0 LIMIT 1000;
--      EXPLAIN DELETE FROM t_message_retry  WHERE created_at  < NOW() - INTERVAL 30 DAY AND MOD(id, 1) = 0 LIMIT 1000;
--      EXPLAIN DELETE FROM t_event_outbox   WHERE status='SENT' AND sent_at < NOW() - INTERVAL 7 DAY AND MOD(id, 1) = 0 LIMIT 1000;
--      期望：key = idx_consumed_at / idx_created_at / idx_status_sent_at（type=range）
--
-- ----------------------------
-- ⑤ 回滚（万一要退）
-- ----------------------------
-- 只回滚索引（表可以留着，它们不影响业务）：
--   ALTER TABLE t_message_retry DROP INDEX idx_created_at;
--   ALTER TABLE t_event_outbox  DROP INDEX idx_status_sent_at;
-- 要彻底清掉（注意：留痕一起没了）：
--   DROP TABLE t_archive_log;  DROP TABLE t_job_watermark;
