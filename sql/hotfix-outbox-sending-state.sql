-- ============================================================
-- 热修：t_event_outbox 增加 SENDING 中间态（P1 步骤 3）
--
-- 背景：步骤 2 建表时只定义了 PENDING / SENT / FAILED 三态，隐含的假设是
--   "扫描到就直接发送、发送完再回写"。步骤 3 的实现改成了**原子抢占**：
--   先条件 UPDATE 把记录置为 SENDING 并写 owner（语句结束即释放行锁），
--   之后再发送、最后回写结果。这样才满足"禁止在发送网络请求时持有数据库行锁"。
--   中间态必须能被回收，所以还需要 claimed_at 这一列来判定"多久没动了"。
--
-- 影响面（本次实测于本机 2026-09-24）：
--   · 表内行数：见执行前 SELECT COUNT(*) 的输出（开发库为个位数；线上口径约 1–2.5 万行/月）
--   · ADD COLUMN：MySQL 8 追加到表尾，ALGORITHM=INSTANT（不重建表、不阻塞读写）
--   · MODIFY status 的 COMMENT：只改列注释，MySQL 8 仍会走一次表重建（小表毫秒级；
--     1 万行级别仍在秒级）。执行前建议在业务低峰，或先只跑 ADD COLUMN 部分。
--   · DROP/ADD INDEX idx_dispatch：随上面的表重建一并完成
--
-- 幂等性：本脚本**可以重复执行**——它先查 information_schema 判断"当前缺什么"，再拼出对应的 DDL，
--   已应用过的部分不会出现在语句里（重复执行输出 `已应用，跳过`，影响 0 行、不报错）。
--   这是 CLAUDE.md §4 的硬要求：老库的迁移脚本必须能安全重跑（部署脚本重试、人工误跑都要能兜住）。
--
-- 执行顺序：先在这一句上确认行数，再执行下面的 ALTER
--   SELECT COUNT(*) FROM t_event_outbox;
-- ============================================================

-- ── 目标定义（改这里必须同时改 sql/init.sql 的对应列；两处不一致就是漂移）──
SET @tbl := 't_event_outbox';
SET @db  := DATABASE();
SET @c_owner   := '抢占者标识(hostname:pid:随机后缀)，仅 SENDING 期间非空';
SET @c_claimed := '被抢占的时间戳(SENDING 起始时刻)，用于回收"抢占后进程崩溃"的遗留记录';
SET @c_status  := '投递状态: PENDING待投递 / SENDING已被某实例抢占(投递中，超过回收阈值未收尾会被改回PENDING) / SENT已投递(收到publisher-confirm ack) / FAILED达尝试上限待人工介入';
SET @c_retry   := '已失败次数(只在投递未确认时+1；抢占但未尝试发送就退回的记0次)';
SET @c_deliver := '最早可投递时间 = 事件发生时间 + 该工单 type+priority 的 accept_minutes；投递侧据它计算延迟消息的 x-delay';
SET @c_sent    := '实际投递成功时间(收到 ack 的时刻)';

-- ── 现状判定 ──
SET @has_owner   := (SELECT COUNT(*) FROM information_schema.COLUMNS
                     WHERE TABLE_SCHEMA = @db AND TABLE_NAME = @tbl AND COLUMN_NAME = 'owner');
SET @has_claimed := (SELECT COUNT(*) FROM information_schema.COLUMNS
                     WHERE TABLE_SCHEMA = @db AND TABLE_NAME = @tbl AND COLUMN_NAME = 'claimed_at');
SET @idx_exists  := (SELECT COUNT(*) FROM information_schema.STATISTICS
                     WHERE TABLE_SCHEMA = @db AND TABLE_NAME = @tbl AND INDEX_NAME = 'idx_dispatch');
SET @idx_cols    := (SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM information_schema.STATISTICS
                     WHERE TABLE_SCHEMA = @db AND TABLE_NAME = @tbl AND INDEX_NAME = 'idx_dispatch');
SET @idx_wrong   := IF(@idx_cols IS NULL OR @idx_cols <> 'status,next_retry_at', 1, 0);
-- sent_at 的注释在步骤 2 之后也改过（"实际投递成功时间" → 补上"收到 ack 的时刻"）：
-- 这一项由 P1 步骤 3 的回读校验发现（迁移脚本漏同步会让"老库"与"新库"留下一条列注释漂移）。
SET @comments_differ := (SELECT COUNT(*) FROM information_schema.COLUMNS
                         WHERE TABLE_SCHEMA = @db AND TABLE_NAME = @tbl
                           AND ((COLUMN_NAME = 'status'     AND COLUMN_COMMENT <> @c_status)
                             OR (COLUMN_NAME = 'retry_count' AND COLUMN_COMMENT <> @c_retry)
                             OR (COLUMN_NAME = 'deliver_at'  AND COLUMN_COMMENT <> @c_deliver)
                             OR (COLUMN_NAME = 'sent_at'     AND COLUMN_COMMENT <> @c_sent)));
SET @need_alter := IF(@has_owner = 0 OR @has_claimed = 0 OR @idx_wrong = 1 OR @comments_differ > 0, 1, 0);

-- ── 按"当前缺什么"拼 DDL：已满足的部分不出现，所以重复执行既不会撞 duplicate column / duplicate key，
--    也不会因为无谓的 MODIFY 触发一次表重建（重建代价见文件头的锁风险说明）──
SET @parts := CONCAT_WS(', ',
    IF(@has_owner = 0,   CONCAT('ADD COLUMN owner VARCHAR(64) NULL COMMENT ''', @c_owner, ''''), NULL),
    IF(@has_claimed = 0, CONCAT('ADD COLUMN claimed_at DATETIME NULL COMMENT ''', @c_claimed, ''''), NULL),
    IF(@comments_differ > 0, CONCAT('MODIFY COLUMN status VARCHAR(16) NOT NULL DEFAULT ''PENDING'' COMMENT ''', @c_status, ''''), NULL),
    IF(@comments_differ > 0, CONCAT('MODIFY COLUMN retry_count INT NOT NULL DEFAULT 0 COMMENT ''', @c_retry, ''''), NULL),
    IF(@comments_differ > 0, CONCAT('MODIFY COLUMN deliver_at DATETIME NOT NULL COMMENT ''', @c_deliver, ''''), NULL),
    IF(@comments_differ > 0, CONCAT('MODIFY COLUMN sent_at DATETIME NULL COMMENT ''', @c_sent, ''''), NULL),
    IF(@idx_exists > 0 AND @idx_wrong = 1, 'DROP INDEX idx_dispatch', NULL),
    IF(@idx_wrong = 1, 'ADD INDEX idx_dispatch (status, next_retry_at)', NULL)
);

SET @ddl := IF(@need_alter = 1, CONCAT('ALTER TABLE ', @tbl, ' ', @parts), 'DO 0');
PREPARE migrate_stmt FROM @ddl;
EXECUTE migrate_stmt;
DEALLOCATE PREPARE migrate_stmt;

-- 执行结果（可判定：一行输出，含"已执行"或"已应用，跳过"）
SELECT IF(@need_alter = 1,
          CONCAT('hotfix-outbox-sending-state: 已执行 ALTER TABLE ', @tbl, ' ', @parts),
          'hotfix-outbox-sending-state: 已应用，跳过（影响 0 行）') AS result;

-- 验证：应看到 owner / claimed_at 两列存在，status 注释含 SENDING，索引为 (status, next_retry_at)
--   SHOW COLUMNS FROM t_event_outbox LIKE 'owner';
--   SHOW COLUMNS FROM t_event_outbox LIKE 'claimed_at';
--   SHOW INDEX FROM t_event_outbox WHERE Key_name = 'idx_dispatch';
