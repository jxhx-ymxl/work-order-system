-- ============================================================
-- 热修：恢复内置角色的权限绑定（**管理员被锁死时的急救包**）
--
-- 背景：2026-10-08 在服务器上发生过一次**真实的自锁**——把 SYS_ADMIN 角色的权限清到只剩一条，
--   管理员权限当场失效（"角色管理 / 用户管理"全部打不开），**恢复只能直接写库**。
--   根因是两侧不对称：用户侧改角色/停用自己有**四道**防线（不能删自己、不能移除自己最后一个角色、
--   admin 不允许无角色…），而**角色侧**只有"内置角色不许删"一条，`assignPermissions` 是
--   "先 delete 全部、再按传入列表 insert"，一道防线都没有（详见 `docs/DECISIONS.md` D110）。
--
--   **现在后端已硬保护**（`assignPermissions` 对 SYS_ADMIN 直接 400，且**在 delete 之前**返回），
--   但保护只对"改完之后"生效——**救不了已经锁死的库**，所以本脚本仍然必须存在。
--
-- 用法：mysql -h127.0.0.1 -P3306 -uroot -p --default-character-set=utf8mb4 work_order < sql/hotfix-restore-sysadmin-perms.sql
--   ⚠ 跑完**必须退出登录再重新进入**：前端把权限码缓存在 localStorage 里，
--     不重新登录的话页面上还是旧的权限（会以为脚本没生效）——见 D110 的"放大器"一节。
--
-- 幂等性：`INSERT IGNORE` + 按 `role_code` / `perm_code` 定位（**不写死 id**），重复执行影响 0 行、不报错。
-- 影响面：最多给 SYS_ADMIN 补回 `t_permission` 里现有的全部权限绑定（当前 16 条）；
--   **不动**其它角色、**不动**用户与角色分配、**不动**任何权限定义本身。
-- ============================================================

-- ① 主恢复：SYS_ADMIN = 现有全部权限（CROSS JOIN 权限表，按 role_code 定位）
INSERT IGNORE INTO t_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM t_role r CROSS JOIN t_permission p WHERE r.role_code = 'SYS_ADMIN';

-- 判据：与 t_permission 的总数相等，说明 SYS_ADMIN 已恢复成"全部权限"
SELECT CONCAT('hotfix-restore-sysadmin-perms: SYS_ADMIN 绑定数 = ',
              (SELECT COUNT(*) FROM t_role_permission rp JOIN t_role r ON r.id = rp.role_id
                WHERE r.role_code = 'SYS_ADMIN'),
              ' / t_permission 总数 = ',
              (SELECT COUNT(*) FROM t_permission)) AS result;

-- ② 参照（**默认注释掉**）：另外三个内置角色的最小绑定——只在"它们的绑定也被误删"时才执行。
--    与上面同一条口径：按 `role_code` + `perm_code` 定位，不写死任何 id。
-- INSERT IGNORE INTO t_role_permission (role_id, permission_id)
-- SELECT r.id, p.id
--   FROM t_role r
--   JOIN t_permission p
--     ON (r.role_code = 'SUBMITTER'  AND p.perm_code = 'order:reject')
--     OR (r.role_code = 'HANDLER'    AND p.perm_code = 'order:accept')
--     OR (r.role_code = 'DEPT_ADMIN' AND p.perm_code IN ('order:accept', 'order:assign',
--                                                        'order:stats', 'order:manage'));

-- 验证（判据）：
--   SELECT COUNT(*) FROM t_role_permission rp JOIN t_role r ON r.id = rp.role_id
--     WHERE r.role_code = 'SYS_ADMIN';        → 应等于 SELECT COUNT(*) FROM t_permission
--   用 admin 登录 → 退出登录 → 重新登录：角色管理/用户管理应重新可见（localStorage 缓存必须刷新）
