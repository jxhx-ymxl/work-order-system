-- ============================================================
-- 热修：t_work_order 增加 triage_status 列（P5 步骤 1：triage 异步化）
--
-- 背景：提交时缺 type/priority 的工单现在**先落库**（兜底值 OTHER/普通 + triage_status='PENDING'），
--   由消费端异步调 LLM 修正；LLM 不可用则保持 PENDING 并进重试账本。
--
-- 存量工单默认值 = **DONE**（不是 NULL、不是 PENDING），理由：
--   ① 语义正确：存量工单要么是用户填了类型、要么是改造前**已经同步 triage 过**，都属于"已定稿"；
--   ② 不会被误触发：派发查询是 `WHERE triage_status='PENDING'`，默认 DONE 不会让老单被重新分诊；
--   ③ 列是 NOT NULL 三值枚举（PENDING/DONE/FAILED），用 NULL 会多出第四种实际取值，
--      到处要写 `IS NULL OR ...`，与"派发只挑 PENDING"这种简单查询冲突。
--
-- 幂等性：先查 information_schema，缺列才 ALTER；重复执行输出"已存在，跳过"、不报错。
-- 影响面：给 t_work_order 加一列，MySQL 8 对**追加到表尾的列**使用 ALGORITHM=INSTANT（不重建表、不阻塞读写）；
--   存量行填默认值 DONE。线上按 3 万行/月估算，本次操作是毫秒级。
-- 用法：mysql -h127.0.0.1 -P3306 -uroot -p --default-character-set=utf8mb4 work_order < sql/hotfix-p5-triage-status.sql
-- ============================================================

SET @db := DATABASE();
SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 't_work_order' AND COLUMN_NAME = 'triage_status');
SET @c_comment := 'AI 分诊状态: PENDING待分诊(提交时缺 type/priority) / DONE已定稿(用户填了或分诊已完成) / FAILED分诊失败待重试；存量工单默认 DONE（见 sql/hotfix-p5-triage-status.sql 的说明）';

SET @ddl := IF(@has_col = 0,
    CONCAT('ALTER TABLE t_work_order ADD COLUMN triage_status VARCHAR(16) NOT NULL DEFAULT ''DONE'' COMMENT ''', @c_comment, ''' AFTER max_reject'),
    'DO 0');
PREPARE migrate_stmt FROM @ddl;
EXECUTE migrate_stmt;
DEALLOCATE PREPARE migrate_stmt;

SELECT IF(@has_col = 0,
          'hotfix-p5-triage-status: 已执行 ALTER TABLE t_work_order ADD COLUMN triage_status',
          'hotfix-p5-triage-status: 已存在，跳过（影响 0 行）') AS result;

-- 验证（判据）：
--   SHOW COLUMNS FROM t_work_order LIKE 'triage_status';      → 应存在，Default = DONE
--   SELECT COUNT(*) FROM t_work_order WHERE triage_status='PENDING';   → 存量应为 0
