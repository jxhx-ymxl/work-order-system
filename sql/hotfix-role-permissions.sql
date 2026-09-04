-- ============================================================
-- 热修复脚本：补齐 SUBMITTER/HANDLER/DEPT_ADMIN 角色缺失的权限
-- 执行时机：已有数据库，无需重建
-- 幂等性：使用 INSERT IGNORE，重复执行安全
-- ============================================================

-- SUBMITTER (role_id=2): order:reject（驳回自己的工单时需要）
INSERT IGNORE INTO t_role_permission (role_id, permission_id) VALUES (2, 6);

-- HANDLER (role_id=3): order:accept（抢单需要此权限码）
INSERT IGNORE INTO t_role_permission (role_id, permission_id) VALUES (3, 2);

-- DEPT_ADMIN (role_id=4): order:accept + order:assign + order:stats
INSERT IGNORE INTO t_role_permission (role_id, permission_id) VALUES (4, 2), (4, 7), (4, 8);

-- 确保 admin (user_id=1) 绑定 SYS_ADMIN (role_id=1)
INSERT IGNORE INTO t_user_role (user_id, role_id) VALUES (1, 1);

-- 验证结果
SELECT u.username, r.role_code, r.role_name
FROM t_user u
JOIN t_user_role ur ON u.id = ur.user_id
JOIN t_role r ON ur.role_id = r.id
ORDER BY u.id, r.id;
