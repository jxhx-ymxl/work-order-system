-- ============================================================
-- 热修：t_notification 增加 event_id + 唯一键 uk_notification_event_user（P5 步骤 2 提交通知）
--
-- 为什么需要：提交通知由消费端按角色群发（一单 × 处理人数）。要做到"同一条事件不给同一个接收人发两条"，
--   唯一的原子判据是数据库唯一索引——去重表（t_consume_record）只保证"消费者跑一次"，
--   挡不住去重记录被归档清理、或有人绕过消费端直写通知。
--
-- ⚠ **必须在重启后端之前跑**（与 P4/P5 的其他脚本同一条规矩，但后果更重）：
--   实体 Notification 新增了 eventId 字段，MyBatis-Plus 的 INSERT 会带上 event_id 列；
--   列不存在时**所有站内信写入都会报 Unknown column**——不只提交通知，SLA 超时告警与驳回达上限通知
--   （两条 P1 之前就存在的老链路）也会一起失败。
--
-- 幂等性：先查 information_schema 判断"当前缺什么"，再拼出对应 DDL——可重复执行，
--   已应用过的部分不会出现在语句里（重复执行输出 `已应用，跳过`，影响 0 行、不报错）。
--   这是 CLAUDE.md §4 的硬要求（老库迁移脚本必须能安全重跑）。
--
-- 影响面（执行前先跑 `SELECT COUNT(*) FROM t_notification;` 记录行数）：
--   · ADD COLUMN ... AFTER ref_id：MySQL 8 走 INSTANT（不重建表、不阻塞读写）；
--   · ADD UNIQUE KEY：走 INPLACE、允许并发 DML，多数情况不阻塞读写；但会**扫描全表构建索引**，
--     行数越多耗时越长（业务库实测 82 行 → 毫秒级；按提交通知放量后 1–4 万行/月仍在秒级）。
--     存量行的 event_id 全为 NULL，而 NULL 在唯一索引里互不相等 → **不会因历史数据冲突而失败**。
--   · 结构与 sql/init.sql 的 t_notification **逐字段一致**（列位置、类型、可空、注释、索引形状）。
--
-- 用法：mysql -h127.0.0.1 -P3306 -uroot -p --default-character-set=utf8mb4 work_order < sql/hotfix-p5-submit-notification.sql
-- ============================================================

SET @tbl := 't_notification';
SET @db  := DATABASE();
SET @c_event := '触发本通知的事件键: {aggregate}:{id}:v{version}:{eventType}；NULL=该链路未做事件级去重（SLA超时/驳回达上限两条老链路）';

-- ── 现状判定 ──
SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = @db AND TABLE_NAME = @tbl AND COLUMN_NAME = 'event_id');
SET @comment_differ := (SELECT COUNT(*) FROM information_schema.COLUMNS
                        WHERE TABLE_SCHEMA = @db AND TABLE_NAME = @tbl
                          AND COLUMN_NAME = 'event_id' AND COLUMN_COMMENT <> @c_event);
SET @idx_exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
                    WHERE TABLE_SCHEMA = @db AND TABLE_NAME = @tbl
                      AND INDEX_NAME = 'uk_notification_event_user');
SET @idx_cols := (SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM information_schema.STATISTICS
                  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = @tbl
                    AND INDEX_NAME = 'uk_notification_event_user');
SET @idx_wrong := IF(@idx_exists > 0 AND (@idx_cols IS NULL OR @idx_cols <> 'event_id,user_id'), 1, 0);
SET @need_alter := IF(@has_col = 0 OR @idx_exists = 0 OR @idx_wrong = 1 OR @comment_differ > 0, 1, 0);

-- ── 按"当前缺什么"拼 DDL：已满足的部分不出现 ──
-- 注意 MODIFY 只在"列已存在但注释漂移"时出现；ADD COLUMN 带 AFTER ref_id，
-- 与 init.sql 里的列顺序保持一致（否则老库迁移后与新库的列顺序会不同，D51 那套逐项比对就会失败）。
SET @parts := CONCAT_WS(', ',
    IF(@has_col = 0, CONCAT('ADD COLUMN event_id VARCHAR(128) NULL COMMENT ''', @c_event, ''' AFTER ref_id'), NULL),
    IF(@comment_differ > 0, CONCAT('MODIFY COLUMN event_id VARCHAR(128) NULL COMMENT ''', @c_event, ''''), NULL),
    IF(@idx_wrong = 1, 'DROP INDEX uk_notification_event_user', NULL),
    IF(@idx_exists = 0 OR @idx_wrong = 1,
       'ADD UNIQUE KEY uk_notification_event_user (event_id, user_id)', NULL)
);

SET @ddl := IF(@need_alter = 1, CONCAT('ALTER TABLE ', @tbl, ' ', @parts), 'DO 0');
PREPARE migrate_stmt FROM @ddl;
EXECUTE migrate_stmt;
DEALLOCATE PREPARE migrate_stmt;

-- 执行结果（可判定：一行输出，含"已执行"或"已应用，跳过"）
SELECT IF(@need_alter = 1,
          CONCAT('hotfix-p5-submit-notification: 已执行 ALTER TABLE ', @tbl, ' ', @parts),
          'hotfix-p5-submit-notification: 已应用，跳过（影响 0 行）') AS result;

-- 验证（判据）：
--   ① 唯一约束真的建上了（不要只看 DDL 文件）：
--      SHOW CREATE TABLE t_notification;   → 应含 UNIQUE KEY `uk_notification_event_user` (`event_id`,`user_id`)
--   ② 列位置与注释：
--      SHOW COLUMNS FROM t_notification LIKE 'event_id';   → Type=varchar(128), Null=YES, 在 ref_id 之后
--   ③ 行为判据（可选，幂等生效的实证）：
--      INSERT INTO t_notification (user_id,title,content,event_id) VALUES (1,'x','y','t:1:v0:T');
--      重跑同一条 → 期望 ERROR 1062 Duplicate entry（说明第二道防线真的在）。
--      验证完务必删掉这两条测试数据。
