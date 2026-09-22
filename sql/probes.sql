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
