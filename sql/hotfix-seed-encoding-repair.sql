-- ============================================================
-- 热修：修复**种子数据双编码**（角色名 / 权限名乱码）—— 定向 UPDATE 版
--
-- 背景（详见 docs/DECISIONS.md D73）：`mysql < sql/init.sql` **没带** `--default-character-set=utf8mb4` 时，
--   客户端把 utf8mb4 的字节按 latin1 读进 `character_set_client`，于是写入的表里存成了**双编码**字节。
--   表现：**页面角色名 / 权限名乱码，但后端行为完全不受影响**（权限判断照常）——所以探针不会报警。
--
-- 为什么**定向 UPDATE 就够**（不必重建/重导）：
--   · 污染**只可能来自"种子导入"这条路径**（业务运行时写入走 JDBC，连接串里已固定 utf8mb4，不受客户端 locale 影响）；
--   · 所以受影响的行 = **种子行**，范围**已知且很小**：`t_role` 4 行 + `t_permission` 15 行
--     （与 sql/init.sql 的种子行数一致，可用 `SELECT COUNT(*)` 复核）。
--   只有**污染范围未知或很大**时（例如业务行也怀疑被写坏）才考虑按正确字符集重建/重导。
--
-- ❌ `ALTER TABLE ... CONVERT TO CHARACTER SET utf8mb4` 对双编码**无效**：
--   它改的是"列的字符集声明"，而双编码是**字节语义**已经错了（MySQL 认为存的本来就是 utf8mb4，校验不会报错）。
--
-- 幂等：本脚本是"把种子值按 `role_code` / `perm_code` 重写一遍"——健康库上运行等于写回同样的值（无副作用），
--   双编码库上运行即修复。**修复值逐字取自 sql/init.sql 的种子原文**（唯一真源）。
--
-- 用法（**必须带参数**，否则等于再用错字符集写一遍）：
--   mysql -h127.0.0.1 -P3306 -uroot -p --default-character-set=utf8mb4 work_order < sql/hotfix-seed-encoding-repair.sql
-- ============================================================

-- ── ① t_role：4 行（按 role_code 定位；修复值 = init.sql 原文） ──
UPDATE t_role SET role_name = '系统管理员', remark = '拥有全部权限，可管理用户、角色、SLA配置' WHERE role_code = 'SYS_ADMIN';
UPDATE t_role SET role_name = '提交人',    remark = '可提交工单、查看自己的工单'                 WHERE role_code = 'SUBMITTER';
UPDATE t_role SET role_name = '处理人',    remark = '可抢单、处理工单、提交验收'                 WHERE role_code = 'HANDLER';
UPDATE t_role SET role_name = '部门主管',  remark = '可查看本部门工单、手动分配工单'             WHERE role_code = 'DEPT_ADMIN';

-- ── ② t_permission：15 行（按 perm_code 定位；修复值 = init.sql 原文） ──
UPDATE t_permission SET perm_name = '工单管理菜单'      WHERE perm_code = 'order:*';
UPDATE t_permission SET perm_name = '抢单'              WHERE perm_code = 'order:accept';
UPDATE t_permission SET perm_name = '开始处理'          WHERE perm_code = 'order:start';
UPDATE t_permission SET perm_name = '提交验收'          WHERE perm_code = 'order:complete';
UPDATE t_permission SET perm_name = '验收通过'          WHERE perm_code = 'order:approve';
UPDATE t_permission SET perm_name = '验收驳回'          WHERE perm_code = 'order:reject';
UPDATE t_permission SET perm_name = '手动分配工单'       WHERE perm_code = 'order:assign';
UPDATE t_permission SET perm_name = '查看本部门统计'     WHERE perm_code = 'order:stats';
UPDATE t_permission SET perm_name = '查看全局统计'       WHERE perm_code = 'order:stats:all';
UPDATE t_permission SET perm_name = '接管/关闭升级工单'   WHERE perm_code = 'order:manage';
UPDATE t_permission SET perm_name = '系统管理菜单'       WHERE perm_code = 'system:*';
UPDATE t_permission SET perm_name = '用户管理'          WHERE perm_code = 'system:user:manage';
UPDATE t_permission SET perm_name = '角色管理'          WHERE perm_code = 'system:role:manage';
UPDATE t_permission SET perm_name = 'SLA配置菜单'       WHERE perm_code = 'sla:*';
UPDATE t_permission SET perm_name = 'SLA配置管理'       WHERE perm_code = 'sla:config:manage';

-- ── ③ 判据（跑完立刻验；**必须带 `--default-character-set=utf8mb4`**，否则分不清"数据坏"与"显示坏"） ──
--   ① 命中行数：两条 SELECT 的"改动前/后"行数都应为 4 与 15（`ROW_COUNT()` 或直接看受影响行数）
--      SELECT ROW_COUNT() AS roles_touched;        -- 最后一条 UPDATE 的影响行数（期望 1）
--   ② 数据正确（逐字核对，不是"看着像"）：
--      SELECT role_code, role_name FROM t_role ORDER BY id;
--      SELECT perm_code, perm_name FROM t_permission ORDER BY id;
--      期望与 sql/init.sql 的种子原文逐字相同；需要更硬的证据就比 HEX(role_name)。
--   ③ 范围复核（确认"只有种子行受影响"）：
--      SELECT COUNT(*) AS role_rows FROM t_role;        -- 期望 4
--      SELECT COUNT(*) AS perm_rows FROM t_permission;  -- 期望 15
