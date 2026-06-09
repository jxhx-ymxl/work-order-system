-- ============================================================
-- 工单造数引擎 — 存储过程 + 分布验证
-- 对应 Issue #44: 500 万工单造数
-- 执行方式: 在 DBeaver/Navicat 中选中 CALL 语句执行
-- 预计耗时: 5~20 分钟 (取决于硬件)
-- ⚠️ 本脚本仅生成 SQL，严禁通过 Claude Code 直接连接数据库执行
-- ============================================================

-- ----------------------------
-- 1. 造数存储过程
-- ----------------------------
DELIMITER $$

DROP PROCEDURE IF EXISTS generate_work_orders$$

CREATE PROCEDURE generate_work_orders(IN total INT)
BEGIN
    DECLARE i INT DEFAULT 1;
    DECLARE batch_size INT DEFAULT 1000;
    DECLARE batch_count INT DEFAULT 0;

    -- 随机字段变量
    DECLARE v_order_no    VARCHAR(22);
    DECLARE v_title       VARCHAR(200);
    DECLARE v_content     TEXT;
    DECLARE v_type        VARCHAR(32);
    DECLARE v_priority    TINYINT;
    DECLARE v_status      VARCHAR(20);
    DECLARE v_submitter   BIGINT;
    DECLARE v_assignee    BIGINT;
    DECLARE v_reject      INT;
    DECLARE v_sla         DATETIME;
    DECLARE v_created     DATETIME;

    -- 随机日期范围: 2025-01-01 ~ 2026-06-01 (共 517 天)
    DECLARE v_rand_days   INT;
    DECLARE v_rand_status INT;

    -- 性能优化: 关闭自动提交，手动批量 COMMIT
    SET autocommit = 0;

    WHILE i <= total DO
        START TRANSACTION;

        SET batch_count = 0;
        WHILE batch_count < batch_size AND i <= total DO
            -- 随机日期: 2025-01-01 起偏移 0~516 天
            SET v_rand_days  = FLOOR(RAND() * 517);
            SET v_created    = DATE_ADD('2025-01-01 08:00:00', INTERVAL v_rand_days DAY)
                             + INTERVAL FLOOR(RAND() * 16) HOUR
                             + INTERVAL FLOOR(RAND() * 60) MINUTE
                             + INTERVAL FLOOR(RAND() * 60) SECOND;

            -- 工单编号: WO-YYYYMMDD-XXXXX (序号用 i 保证唯一)
            SET v_order_no = CONCAT('WO-', DATE_FORMAT(v_created, '%Y%m%d'), '-', LPAD(i, 8, '0'));

            -- 标题和内容: 随机拼接避免完全雷同
            SET v_title = CONCAT(
                ELT(1 + FLOOR(RAND() * 4), '报修', '请假', '报销', '其他'),
                '-',
                ELT(1 + FLOOR(RAND() * 10), '空调', '电脑', '打印机', '门禁', '网络',
                                           '水电', '电梯', '办公桌', '投影仪', '电话'),
                '-',
                ELT(1 + FLOOR(RAND() * 5), '故障', '异常', '损坏', '无法使用', '需更换'),
                '-', i
            );
            SET v_content = CONCAT('工单详细描述 - 编号:', i,
                '。位置:', ELT(1 + FLOOR(RAND() * 5), '3楼', '5楼', '1楼', '地下1层', '2楼'),
                ELT(1 + FLOOR(RAND() * 5), '会议室', '办公室', '大厅', '走廊', '机房'),
                '。请尽快处理。');

            -- 类型: 4 种随机
            SET v_type = ELT(1 + FLOOR(RAND() * 4), 'REPAIR', 'LEAVE', 'REIMBURSE', 'OTHER');

            -- 优先级: 0 普通 / 1 紧急
            SET v_priority = FLOOR(RAND() * 2);

            -- 状态: 按 TECHNICAL-PLAN.md 7.1 节比例分配
            --   1-70   → CLOSED         (70%)
            --   71-80  → PENDING        (10%)
            --   81-90  → ACCEPTED       (10%)
            --   91-95  → IN_PROGRESS    (5%)
            --   96-98  → AWAIT_APPROVAL (3%)
            --   99-100 → RELEASED       (2%)
            SET v_rand_status = 1 + FLOOR(RAND() * 100);
            SET v_status = CASE
                WHEN v_rand_status <= 70 THEN 'CLOSED'
                WHEN v_rand_status <= 80 THEN 'PENDING'
                WHEN v_rand_status <= 90 THEN 'ACCEPTED'
                WHEN v_rand_status <= 95 THEN 'IN_PROGRESS'
                WHEN v_rand_status <= 98 THEN 'AWAIT_APPROVAL'
                ELSE 'RELEASED'
            END;

            -- 提交人: 1~100 随机
            SET v_submitter = 1 + FLOOR(RAND() * 100);

            -- 处理人: PENDING/RELEASED 无处理人，其余在 1~20 随机
            IF v_status IN ('PENDING', 'RELEASED') THEN
                SET v_assignee = NULL;
            ELSE
                SET v_assignee = 1 + FLOOR(RAND() * 20);
            END IF;

            -- 驳回次数: 0~3 随机
            SET v_reject = FLOOR(RAND() * 4);

            -- SLA 截止时间: created_at 基础上 + 2~48 小时随机偏移
            SET v_sla = DATE_ADD(v_created, INTERVAL (2 + FLOOR(RAND() * 47)) HOUR);

            INSERT INTO t_work_order (
                order_no, title, content, type, priority, status,
                submitter_id, assignee_id, reject_count, max_reject,
                sla_deadline, version, created_at, updated_at
            ) VALUES (
                v_order_no, v_title, v_content, v_type, v_priority, v_status,
                v_submitter, v_assignee, v_reject, 3,
                v_sla, 0, v_created, v_created
            );

            SET i = i + 1;
            SET batch_count = batch_count + 1;
        END WHILE;

        COMMIT;
    END WHILE;

    SET autocommit = 1;
END$$

DELIMITER ;

-- ----------------------------
-- 2. 执行造数 (500 万条)
-- ----------------------------
-- ⚠️ 重要提示:
--    1. 执行前确认 t_work_order 表为空或确认要追加数据
--    2. 执行前可考虑临时关闭 binlog: SET sql_log_bin = 0; (测试环境)
--    3. 预计执行时间 5~20 分钟
--    4. 执行期间不要关闭连接

-- 清理旧数据（谨慎！确认后取消注释）
-- TRUNCATE TABLE t_work_order;

-- 执行造数
-- CALL generate_work_orders(5000000);


-- ----------------------------
-- 3. 数据分布验证 SQL
-- ----------------------------
-- 造数完成后执行以下语句，验证状态分布是否符合预期比例

SELECT
    status,
    COUNT(*)        AS cnt,
    ROUND(COUNT(*) / (SELECT COUNT(*) FROM t_work_order) * 100, 2) AS pct
FROM t_work_order
GROUP BY status
ORDER BY cnt DESC;

-- 预期输出:
-- | status          | cnt      | pct   |
-- |-----------------|----------|-------|
-- | CLOSED          | ~3,500,000 | 70.00 |
-- | PENDING         | ~500,000   | 10.00 |
-- | ACCEPTED        | ~500,000   | 10.00 |
-- | IN_PROGRESS     | ~250,000   |  5.00 |
-- | AWAIT_APPROVAL  | ~150,000   |  3.00 |
-- | RELEASED        | ~100,000   |  2.00 |

-- ----------------------------
-- 4. 附加验证: 总行数 + 索引使用情况概览
-- ----------------------------
SELECT
    '总工单数'     AS metric,
    COUNT(*)        AS value
FROM t_work_order

UNION ALL

SELECT
    '唯一工单编号',
    COUNT(DISTINCT order_no)
FROM t_work_order

UNION ALL

SELECT
    '无处理人工单(PENDING+RELEASED)',
    COUNT(*)
FROM t_work_order
WHERE assignee_id IS NULL

UNION ALL

SELECT
    '已分配工单',
    COUNT(*)
FROM t_work_order
WHERE assignee_id IS NOT NULL;


DROP PROCEDURE IF EXISTS generate_work_orders;

TRUNCATE TABLE t_work_order;

CALL generate_work_orders(5000000);

