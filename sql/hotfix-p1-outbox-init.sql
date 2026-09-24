-- ============================================================
-- 热修：为**还没有 t_event_outbox 的老库**建这张表（P1 升级路径的第 1 步）
--
-- 为什么需要它：服务器上的库是用 0988ad6 的 sql/init.sql 建的，**当时还没有 outbox 表**；
--   而 sql/hotfix-outbox-sending-state.sql 是"表已存在时的增量"（ADD COLUMN / MODIFY / DROP INDEX），
--   直接跑会报 "Table 'work_order.t_event_outbox' doesn't exist"。
--   这正是 CLAUDE.md §4 那条规则要防的场景（老库不会重建，必须成套交付迁移脚本）。
--
-- 幂等性：CREATE TABLE **IF NOT EXISTS** —— 已有该表（任何形态）时不报错、不改动。
--   注意它**不会**把"步骤 2 形态"的表升级成当前形态（缺 owner/claimed_at、索引形状不同）；
--   那一步由 sql/hotfix-outbox-sending-state.sql 负责（同样幂等）。两者按顺序跑即可覆盖三种库版本：
--     · 0988ad6 的库（无表）        → 本脚本建表 → sending-state 无操作
--     · c926472 的库（步骤 2 形态） → 本脚本无操作 → sending-state 补齐列与索引
--     · 当前 init.sql 建的库        → 两个脚本都无操作
--
-- 表结构与本仓库当前 sql/init.sql 的 t_event_outbox **逐字段一致**（列/类型/可空/默认值/注释/索引），
-- 依据：D41 要求的判定方式——建临时库分别走"老库升级"与"全新 init.sql"，用 information_schema 逐项比对。
--
-- 影响面：新建空表，不触碰任何既有数据；行数从 0 开始（每张工单每次接单/指派产生 1 行）。
-- 用法：mysql -h127.0.0.1 -P3306 -uroot -p --default-character-set=utf8mb4 work_order < sql/hotfix-p1-outbox-init.sql
-- ============================================================

CREATE TABLE IF NOT EXISTS t_event_outbox (
    id                BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '自增主键',
    event_id          VARCHAR(128) NOT NULL COMMENT '事件唯一键: {aggregate}:{id}:v{version}:{eventType}，同时是消费端幂等键',
    event_type        VARCHAR(64)  NOT NULL COMMENT '事件类型: ORDER_RELEASE_CHECK',
    aggregate_id      BIGINT       NOT NULL COMMENT '聚合根ID(工单ID)',
    aggregate_version INT          NOT NULL COMMENT '事件发生时的聚合版本(工单乐观锁version)，用于区分同一工单的多次合法事件',
    payload           JSON         NULL COMMENT '瘦消息载荷，只带 orderId',
    deliver_at        DATETIME     NOT NULL COMMENT '最早可投递时间 = 事件发生时间 + 该工单 type+priority 的 accept_minutes；投递侧据它计算延迟消息的 x-delay',
    status            VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT '投递状态: PENDING待投递 / SENDING已被某实例抢占(投递中，超过回收阈值未收尾会被改回PENDING) / SENT已投递(收到publisher-confirm ack) / FAILED达尝试上限待人工介入',
    retry_count       INT          NOT NULL DEFAULT 0 COMMENT '已失败次数(只在投递未确认时+1；抢占但未尝试发送就退回的记0次)',
    next_retry_at     DATETIME     NULL COMMENT '下次可重试时间(退避用)，NULL表示立即可投递',
    occurred_at       DATETIME     NOT NULL COMMENT '业务事件发生时间(业务侧时钟)',
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录落库时间(数据库侧时钟)，与 occurred_at 分开以便区分"事件何时发生"与"何时写入"',
    sent_at           DATETIME     NULL COMMENT '实际投递成功时间(收到 ack 的时刻)',
    owner             VARCHAR(64)  NULL COMMENT '抢占者标识(hostname:pid:随机后缀)，仅 SENDING 期间非空',
    claimed_at        DATETIME     NULL COMMENT '被抢占的时间戳(SENDING 起始时刻)，用于回收"抢占后进程崩溃"的遗留记录',
    UNIQUE KEY uk_event_id (event_id),
    INDEX idx_dispatch (status, next_retry_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事件发件箱（outbox）：业务事务内写入，独立任务投递';

-- 验证（应输出 1 行、且列齐全）：
--   SHOW COLUMNS FROM t_event_outbox;
--   SHOW INDEX FROM t_event_outbox;
