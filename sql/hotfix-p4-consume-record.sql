-- ============================================================
-- 热修：为已有库建 t_consume_record（P4 步骤 1 消费端幂等表）
--
-- 为什么需要：老库（P4 之前建的）没有这张表；新代码的消费者会往它里面写，
--   表不存在时 INSERT 报错 → 消息被"ACK + ERROR 日志"吃掉（有留痕但业务没执行）。
--   所以**升级顺序上，本脚本必须在重启后端之前跑**（见 deploy/UPGRADE-P1.md）。
--
-- 幂等性：CREATE TABLE IF NOT EXISTS —— 已存在则不动、不报错，可安全重跑。
-- 表结构与本仓库当前 sql/init.sql 的 t_consume_record **逐字段一致**（列/类型/可空/默认值/注释/索引）。
--
-- 影响面：新建空表，不触碰既有数据。
-- 用法：mysql -h127.0.0.1 -P3306 -uroot -p --default-character-set=utf8mb4 work_order < sql/hotfix-p4-consume-record.sql
-- ============================================================

CREATE TABLE IF NOT EXISTS t_consume_record (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '自增主键',
    event_id    VARCHAR(128) NOT NULL COMMENT '事件唯一键: {aggregate}:{id}:v{version}:{eventType}',
    consumer    VARCHAR(64)  NOT NULL COMMENT '消费者标识（同一事件可被多个消费者各消费一次）；本项目的释放检查消费者取值 order-release-listener',
    consumed_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '消费时间（与业务写同事务提交：业务失败则本行一并回滚）',
    UNIQUE KEY uk_event_consumer (event_id, consumer),
    INDEX idx_consumed_at (consumed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='消费去重记录（幂等表）：与业务写同事务插入，命中 UNIQUE 即视为已消费';

-- 验证（判据）：
--   ① 唯一约束真的建上了（不要只看 DDL 文件）：SHOW CREATE TABLE t_consume_record;  → 应含 UNIQUE KEY `uk_event_consumer` (event_id, consumer)
--   ② 索引形状：SHOW INDEX FROM t_consume_record;  → uk_event_consumer + idx_consumed_at
--
-- 清理（保留 30 天，依据见 sql/init.sql 第 11 节的注释；由 P6 的归档任务分批执行）：
--   DELETE FROM t_consume_record WHERE consumed_at < NOW() - INTERVAL 30 DAY LIMIT 1000;   -- 反复执行直到影响 0 行
