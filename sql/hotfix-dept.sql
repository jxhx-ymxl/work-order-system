-- ============================================================
-- 热修：部门实体化（新增 t_dept + 播种 + system:dept:manage 权限）
--
-- 背景：dept_id 一直是**裸数字**（t_user.dept_id）——改不了名、看不出有没有停用、注册页让用户手填数字
--   （填 999 能注册成功并把自己孤立）。本脚本把"部门"实体化成一张字典表，并补一条管理权限。
--
-- 影响面：① 新增一张空表 t_dept（毫秒级，不锁存量行）；② 按 t_user 里 DISTINCT dept_id 播种占位行
--   （行数 = 现有部门数，通常个位数），名称统一是「部门 {id}」，管理员随后在「部门管理」里改名；
--   ③ 新增一条权限 system:dept:manage（id=16，parent_id=10 即 system:* 菜单）并绑给 SYS_ADMIN（role_id=1）。
--   **不动任何存量数据**：t_user.dept_id 与工单里的部门数字一律保留——工单/用户只存 id，
--   部门改名**自动生效**，**不需要数据迁移**。
--
-- 幂等性：CREATE TABLE IF NOT EXISTS + INSERT IGNORE + 按 perm_code 定向 INSERT
--   （**不整体重导种子**，避免 D73 的双编码坑）；重复执行影响 0 行、输出"已存在，跳过"、不报错。
--
-- 用法：mysql -h127.0.0.1 -P3306 -uroot -p --default-character-set=utf8mb4 work_order < sql/hotfix-dept.sql
-- ============================================================

SET @db := DATABASE();

-- ① 建表（幂等）
SET @has_table := (SELECT COUNT(*) FROM information_schema.TABLES
                   WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 't_dept');

CREATE TABLE IF NOT EXISTS t_dept (
                        id         BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '自增主键',
                        name       VARCHAR(64) NOT NULL UNIQUE COMMENT '部门名称',
                        enabled    TINYINT(1) NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
                        created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                        updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='部门表';

-- ② 播种：t_user 里出现过的 dept_id 全部补一行占位名（重复执行影响 0 行）
INSERT IGNORE INTO t_dept (id, name)
SELECT DISTINCT u.dept_id, CONCAT('部门 ', u.dept_id) FROM t_user u WHERE u.dept_id IS NOT NULL;
SET @seed_rows := ROW_COUNT();

-- ③ 权限：system:dept:manage（按 perm_code 定向判断，不整体重导种子）
SET @has_perm := (SELECT COUNT(*) FROM t_permission WHERE perm_code = 'system:dept:manage');

INSERT IGNORE INTO t_permission (id, perm_code, perm_name, parent_id)
SELECT 16, 'system:dept:manage', '部门管理', 10 FROM DUAL WHERE @has_perm = 0;

-- 绑定给 SYS_ADMIN（按 perm_code 定位，不写死 id）
INSERT IGNORE INTO t_role_permission (role_id, permission_id)
SELECT 1, p.id FROM t_permission p WHERE p.perm_code = 'system:dept:manage';

-- 结果（判据：第二遍应全是"已存在，跳过"）
SELECT IF(@has_table = 0, 'hotfix-dept: t_dept 已创建', 'hotfix-dept: t_dept 已存在，跳过（影响 0 行）') AS table_result;
SELECT CONCAT('hotfix-dept: 本次播种部门占位行数 = ', @seed_rows) AS seed_result;
SELECT IF(@has_perm = 0, 'hotfix-dept: 权限 system:dept:manage 已创建并绑定 SYS_ADMIN',
          'hotfix-dept: 权限 system:dept:manage 已存在，跳过（影响 0 行）') AS perm_result;

-- 验证（判据）：
--   SHOW COLUMNS FROM t_dept;                                         → id/name/enabled/created_at/updated_at
--   SELECT * FROM t_dept;                                             → 存量 dept_id 都有一行（占位名「部门 N」）
--   SELECT * FROM t_permission WHERE perm_code = 'system:dept:manage'; → 一行（id=16, parent_id=10）
--   SELECT * FROM t_role_permission WHERE role_id = 1 AND permission_id = 16; → 一行
