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
-- 执行顺序：先在这一句上确认行数，再执行下面的 ALTER
--   SELECT COUNT(*) FROM t_event_outbox;
-- ============================================================

ALTER TABLE t_event_outbox
    ADD COLUMN owner VARCHAR(64) NULL
        COMMENT '抢占者标识(hostname:pid:随机后缀)，仅 SENDING 期间非空',
    ADD COLUMN claimed_at DATETIME NULL
        COMMENT '被抢占的时间戳(SENDING 起始时刻)，用于回收"抢占后进程崩溃"的遗留记录',
    MODIFY COLUMN status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        COMMENT '投递状态: PENDING待投递 / SENDING已被某实例抢占(投递中，超过回收阈值未收尾会被改回PENDING) / SENT已投递(收到publisher-confirm ack) / FAILED达尝试上限待人工介入',
    MODIFY COLUMN retry_count INT NOT NULL DEFAULT 0
        COMMENT '已失败次数(只在投递未确认时+1；抢占但未尝试发送就退回的记0次)',
    MODIFY COLUMN deliver_at DATETIME NOT NULL
        COMMENT '最早可投递时间 = 事件发生时间 + 该工单 type+priority 的 accept_minutes；投递侧据它计算延迟消息的 x-delay',
    DROP INDEX idx_dispatch,
    ADD INDEX idx_dispatch (status, next_retry_at);

-- 验证：应看到 owner / claimed_at 两列存在，status 注释含 SENDING，索引为 (status, next_retry_at)
--   SHOW COLUMNS FROM t_event_outbox LIKE 'owner';
--   SHOW COLUMNS FROM t_event_outbox LIKE 'claimed_at';
--   SHOW INDEX FROM t_event_outbox WHERE Key_name = 'idx_dispatch';
