-- ============================================================
-- 不变量探针脚本（INVARIANTS.md 的 P1–P12 可执行版）
--
-- 用法：
--   mysql -h127.0.0.1 -P3306 -uroot -p work_order < sql/probes.sql
--
-- 输出：每条探针一行，含「期望 / 实际 / 判定」，result 列 PASS/FAIL 一眼可见。
-- 说明：P7（权限码双向一致）、P10（状态机守卫）、P13（测试不污染业务库）
--       无法用纯 SQL 表达，命令见 README「探针用法」。
-- ============================================================

SELECT 'P1' AS probe,
       '至少 1 名启用的 SYS_ADMIN' AS expectation,
       CAST((SELECT COUNT(*) FROM t_user u
             JOIN t_user_role ur ON ur.user_id = u.id
             JOIN t_role r ON r.id = ur.role_id
             WHERE u.status = 1 AND r.role_code = 'SYS_ADMIN') AS CHAR) AS actual,
       IF((SELECT COUNT(*) FROM t_user u
           JOIN t_user_role ur ON ur.user_id = u.id
           JOIN t_role r ON r.id = ur.role_id
           WHERE u.status = 1 AND r.role_code = 'SYS_ADMIN') >= 1, 'PASS', 'FAIL') AS result

UNION ALL
SELECT 'P2', 'ID=1 持有 SYS_ADMIN',
       CAST((SELECT COUNT(*) FROM t_user_role ur JOIN t_role r ON r.id = ur.role_id
             WHERE ur.user_id = 1 AND r.role_code = 'SYS_ADMIN') AS CHAR),
       IF((SELECT COUNT(*) FROM t_user_role ur JOIN t_role r ON r.id = ur.role_id
           WHERE ur.user_id = 1 AND r.role_code = 'SYS_ADMIN') = 1, 'PASS', 'FAIL')

UNION ALL
SELECT 'P3', '4 个内置角色存在 = 4',
       CAST((SELECT COUNT(*) FROM t_role
             WHERE role_code IN ('SUBMITTER','HANDLER','DEPT_ADMIN','SYS_ADMIN')) AS CHAR),
       IF((SELECT COUNT(*) FROM t_role
           WHERE role_code IN ('SUBMITTER','HANDLER','DEPT_ADMIN','SYS_ADMIN')) = 4, 'PASS', 'FAIL')

UNION ALL
SELECT 'P4', '未完结工单 sla_deadline 非空 = 0',
       CAST((SELECT COUNT(*) FROM t_work_order
             WHERE sla_deadline IS NULL AND status NOT IN ('CLOSED','RELEASED')) AS CHAR),
       IF((SELECT COUNT(*) FROM t_work_order
           WHERE sla_deadline IS NULL AND status NOT IN ('CLOSED','RELEASED')) = 0, 'PASS', 'FAIL')

UNION ALL
SELECT 'P5', '工单出现的 (type,priority) 都已配置 = 0',
       CAST((SELECT COUNT(*) FROM (
               SELECT DISTINCT w.type, w.priority FROM t_work_order w
               LEFT JOIN t_sla_config c ON c.type = w.type AND c.priority = w.priority
               WHERE c.id IS NULL AND w.status NOT IN ('CLOSED','RELEASED')) x) AS CHAR),
       IF((SELECT COUNT(*) FROM (
               SELECT DISTINCT w.type, w.priority FROM t_work_order w
               LEFT JOIN t_sla_config c ON c.type = w.type AND c.priority = w.priority
               WHERE c.id IS NULL AND w.status NOT IN ('CLOSED','RELEASED')) x) = 0, 'PASS', 'FAIL')

UNION ALL
SELECT 'P6', 'PENDING 工单 assignee 为空 = 0',
       CAST((SELECT COUNT(*) FROM t_work_order
             WHERE status = 'PENDING' AND assignee_id IS NOT NULL) AS CHAR),
       IF((SELECT COUNT(*) FROM t_work_order
           WHERE status = 'PENDING' AND assignee_id IS NOT NULL) = 0, 'PASS', 'FAIL')

UNION ALL
SELECT 'P8', '库中定义的业务权限码（人工与代码对照，见 README 的 P7 命令）',
       CAST((SELECT COUNT(*) FROM t_permission WHERE perm_code NOT LIKE '%:*') AS CHAR),
       'INFO'

UNION ALL
SELECT 'P9', 'accept_minutes 是否被消费（G5/I8，人工核对：当前应为“未消费”）',
       CAST((SELECT COUNT(*) FROM t_sla_config WHERE accept_minutes IS NOT NULL) AS CHAR),
       'INFO'

UNION ALL
SELECT 'P11', 't_sla_config 覆盖 4 类 × 2 优先级 = 8（事前型）',
       CAST((SELECT COUNT(*) FROM t_sla_config
             WHERE type IN ('NETWORK','UTILITY','DORM','OTHER') AND priority IN (0,1)) AS CHAR),
       IF((SELECT COUNT(*) FROM t_sla_config
           WHERE type IN ('NETWORK','UTILITY','DORM','OTHER') AND priority IN (0,1)) = 8, 'PASS', 'FAIL')

UNION ALL
SELECT 'P12a', '种子 admin 存在且启用',
       CAST((SELECT COUNT(*) FROM t_user WHERE username = 'admin' AND status = 1) AS CHAR),
       IF((SELECT COUNT(*) FROM t_user WHERE username = 'admin' AND status = 1) = 1, 'PASS', 'FAIL')

UNION ALL
SELECT 'P13a', '业务库无 TST- 测试残留 = 0',
       CAST((SELECT COUNT(*) FROM t_work_order WHERE order_no LIKE 'TST-%') AS CHAR),
       IF((SELECT COUNT(*) FROM t_work_order WHERE order_no LIKE 'TST-%') = 0, 'PASS', 'FAIL')

UNION ALL
SELECT 'P13b', '业务库日志无 TST- 残留 = 0',
       CAST((SELECT COUNT(*) FROM t_work_order_log WHERE order_no LIKE 'TST-%') AS CHAR),
       IF((SELECT COUNT(*) FROM t_work_order_log WHERE order_no LIKE 'TST-%') = 0, 'PASS', 'FAIL')

UNION ALL
SELECT 'P14', 'SLA 配置明细（供人工核对 type/priority/accept_minutes/finish_minutes）',
       CONCAT('rows=', CAST((SELECT COUNT(*) FROM t_sla_config) AS CHAR)),
       'INFO';

-- P11 说明：P0b（R4 类型枚举替换）之前，本项必然 FAIL（现有配置是旧类型集合）。
--          它是"事前型"探针：在应用启动前就能发现漏配，与 P5（事后型）配对使用。

-- ------------------------------------------------------------
-- P15 · 时区一致性（I10）：JVM 与 MySQL 必须用同一个时间来源
--
-- 背景：submitOrder 用 JVM 的 LocalDateTime.now() 计算 sla_deadline，
--       而 SLA 扫描 SQL 用 MySQL 的 NOW() 比较。两侧时区不一致时，
--       所有工单会瞬间变成"已超时"（差 8 小时 = 28800 秒）。
--       这与 I4 同属"静默失效"一类，此前无守卫。
-- ------------------------------------------------------------

-- P15a 有两项判定，缺一不可：
--   (a) 未来时间检查（与工单年龄无关，能确定性抓到"JVM 比 DB 快 8 小时"）
--   (b) 新鲜度检查（仅在刚提交过工单时有意义）
-- 注意：单看 (b) 会误判——它测出来的其实是"最新工单已经创建了多久"，
--       而不是时钟偏差（阶段 2 实测踩到：压测结束 41 秒后运行，读数为 41 秒）。
SELECT 'P15a-future' AS probe,
       '最新工单 created_at 不得超前 DB 的 NOW() 超过 5 秒（超前=JVM 时区/时钟快）' AS expectation,
       IFNULL(CAST((SELECT TIMESTAMPDIFF(SECOND, NOW(), created_at)
                    FROM t_work_order ORDER BY id DESC LIMIT 1) AS CHAR), 'no-order') AS actual,
       CASE
         WHEN (SELECT COUNT(*) FROM t_work_order) = 0 THEN 'SKIP（表内没有工单）'
         WHEN (SELECT TIMESTAMPDIFF(SECOND, NOW(), created_at)
               FROM t_work_order ORDER BY id DESC LIMIT 1) <= 5 THEN 'PASS'
         WHEN ABS((SELECT TIMESTAMPDIFF(SECOND, NOW(), created_at)
                   FROM t_work_order ORDER BY id DESC LIMIT 1)) BETWEEN 28700 AND 28900
           THEN 'FAIL（差 8 小时：JVM 与 MySQL 时区不一致）'
         ELSE 'FAIL（created_at 超前 DB 时间）'
       END AS result

UNION ALL
SELECT 'P15a-fresh', '刚提交工单后立即运行：最新工单年龄 ≤ 15 秒（否则本行无意义，请看 P15a-future）',
       IFNULL(CAST((SELECT TIMESTAMPDIFF(SECOND, created_at, NOW())
                    FROM t_work_order ORDER BY id DESC LIMIT 1) AS CHAR), 'no-order'),
       CASE
         WHEN (SELECT COUNT(*) FROM t_work_order) = 0 THEN 'SKIP（表内没有工单）'
         WHEN (SELECT TIMESTAMPDIFF(SECOND, created_at, NOW())
               FROM t_work_order ORDER BY id DESC LIMIT 1) <= 15 THEN 'PASS'
         ELSE 'SKIP（最新工单已较旧，本条不判定；用 P15a-future 判定时区）'
       END

UNION ALL
SELECT 'P15b', 'DB 会话时区偏移 = -28800 秒（即 +08:00，与业务时区一致）',
       CAST(TIMESTAMPDIFF(SECOND, NOW(), UTC_TIMESTAMP()) AS CHAR),
       IF(TIMESTAMPDIFF(SECOND, NOW(), UTC_TIMESTAMP()) = -28800, 'PASS', 'FAIL')

-- P16 · outbox 投递链路健康度（I11，P1 步骤 3 新增）
--   P16a：PENDING 且已过退避时间超过 5 分钟 → 消息投不出去（broker 不可达 / 交换机声明失败）
--   P16b：SENDING 超过回收阈值 5 分钟未收尾 → 投递任务没在跑（回收只在任务轮次里执行）
--   P16c：FAILED → 已达尝试上限，需人工介入（P4 的 retry-replay 接管后本项应保持 0）
UNION ALL
SELECT 'P16a', 'outbox 无长期积压：PENDING 且过退避时间 >5min 的记录数 = 0',
       CAST((SELECT COUNT(*) FROM t_event_outbox
             WHERE status = 'PENDING'
               AND IFNULL(next_retry_at, created_at) < DATE_SUB(NOW(), INTERVAL 5 MINUTE)) AS CHAR),
       IF((SELECT COUNT(*) FROM t_event_outbox
           WHERE status = 'PENDING'
             AND IFNULL(next_retry_at, created_at) < DATE_SUB(NOW(), INTERVAL 5 MINUTE)) = 0, 'PASS', 'FAIL')

UNION ALL
SELECT 'P16b', 'outbox 无卡死中间态：SENDING 且 claimed_at 超过 5min 的记录数 = 0',
       CAST((SELECT COUNT(*) FROM t_event_outbox
             WHERE status = 'SENDING'
               AND claimed_at < DATE_SUB(NOW(), INTERVAL 5 MINUTE)) AS CHAR),
       IF((SELECT COUNT(*) FROM t_event_outbox
           WHERE status = 'SENDING'
             AND claimed_at < DATE_SUB(NOW(), INTERVAL 5 MINUTE)) = 0, 'PASS', 'FAIL')

UNION ALL
SELECT 'P16c', 'outbox 无终态失败：FAILED 记录数 = 0',
       CAST((SELECT COUNT(*) FROM t_event_outbox WHERE status = 'FAILED') AS CHAR),
       IF((SELECT COUNT(*) FROM t_event_outbox WHERE status = 'FAILED') = 0, 'PASS', 'FAIL')

;

-- P14b 明细（单独结果集，便于人工核对取值是否合理）
SELECT type, priority, accept_minutes, finish_minutes FROM t_sla_config ORDER BY type, priority;
