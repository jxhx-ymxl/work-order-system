-- ============================================================
-- 热修：为已有库建 t_message_retry（P4 步骤 2 消费失败重试账本）
--
-- 为什么需要：老库没有这张表；新代码在**消费失败**时会往它里面写。
--   表不存在时写入会失败 → 失败消息既没重试记录、又被 ACK 掉 = 静默丢失。
--   与 P4 步骤 1 的 hotfix-p4-consume-record.sql 一样，**必须在重启后端之前跑**。
--
-- 幂等性：CREATE TABLE IF NOT EXISTS —— 已存在则不动、不报错，可安全重跑。
-- 表结构与本仓库当前 sql/init.sql 的 t_message_retry **逐字段一致**（列/类型/可空/默认值/注释/索引）。
--
-- 影响面：新建空表，不触碰既有数据。
-- 用法：mysql -h127.0.0.1 -P3306 -uroot -p --default-character-set=utf8mb4 work_order < sql/hotfix-p4-message-retry.sql
-- ============================================================

CREATE TABLE IF NOT EXISTS t_message_retry (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '自增主键',
    event_id      VARCHAR(128) NOT NULL COMMENT '事件唯一键；重投时必须原样带回 x-event-id，否则消费端去重失效、业务会被重复执行',
    consumer      VARCHAR(64)  NOT NULL COMMENT '消费者标识；本项目的释放检查消费者取值 order-release-listener',
    payload       VARCHAR(512) NOT NULL COMMENT '原始消息体（瘦消息 JSON），重投时原样投出',
    attempt       INT          NOT NULL DEFAULT 0 COMMENT '已失败次数（每次业务失败 +1；SUCCEEDED/SKIPPED/重复都不计）',
    next_retry_at DATETIME     NULL COMMENT '下次重投时间 = 失败时刻 + 阶梯(attempt)；PARKED/SUCCEEDED 时为 NULL',
    status        VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT '状态: PENDING待重投 / SUCCEEDED已成功（或期间业务已成功） / PARKED超上限停车待人工介入',
    last_error    VARCHAR(500) NULL COMMENT '最近一次失败原因（截断到列长以内，便于排障）',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次失败落库时间（清理按它算保留期）',
    UNIQUE KEY uk_event_consumer (event_id, consumer),
    INDEX idx_retry_dispatch (status, next_retry_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='消息重试账本：消费失败时在业务事务之外写入，按阶梯重投';

-- 验证（判据）：
--   ① 唯一约束真的建上了：SHOW CREATE TABLE t_message_retry;  → 应含 UNIQUE KEY `uk_event_consumer` (event_id, consumer)
--   ② 索引形状与重投取数一致：SHOW INDEX FROM t_message_retry; → idx_retry_dispatch (status, next_retry_at)
--
-- 清理（保留 30 天，与 t_consume_record 同口径；由 P6 的归档任务分批执行）：
--   DELETE FROM t_message_retry WHERE created_at < NOW() - INTERVAL 30 DAY LIMIT 1000;   -- 反复执行直到影响 0 行
