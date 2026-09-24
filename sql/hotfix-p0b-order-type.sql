-- ============================================================
-- P0b 迁移脚本：工单类型枚举替换（旧 → 新）
--   旧: REPAIR / LEAVE / REIMBURSE / OTHER
--   新: NETWORK / UTILITY / DORM / OTHER
--
-- 涉及范围（两者都要迁，缺一不可）：
--   1) t_sla_config 的 8 行配置（4 类 × 2 优先级，行数不变）
--   2) t_work_order 的存量工单类型
--
-- 幂等性：全部使用 WHERE 精确匹配旧值，重复执行影响行数为 0，可安全重跑。
-- 执行顺序：先跑 P5 探针留档 → 执行本脚本 → 再跑 P5 探针（应为空）。
-- 用法：mysql -h127.0.0.1 -P3306 -uroot -p work_order < sql/hotfix-p0b-order-type.sql
-- 命名说明：本文件原名 migration-p0b-order-type.sql，P1 步骤 3 收口时统一到 hotfix- 前缀
--   （仓库里曾同时存在 migration-* 与 hotfix-* 两套前缀，部署者无法判断该跑哪个；见 D41 与 CLAUDE.md §4）。
--
-- 存量映射依据（不做臆测；无法确定的映射到 OTHER）：
--   REPAIR     —— "报修"含糊，无法区分网络/水电/宿舍 → 映射 OTHER
--   REIMBURSE  —— 报销在新场景中不存在对应类型 → 映射 OTHER
--   LEAVE      —— 同上 → 映射 OTHER
--   OTHER      —— 保持
-- ============================================================

-- ---------- 1) SLA 配置：类型重命名 + 按新场景校准时限 ----------
-- 说明：旧集合里 REPAIR/LEAVE/REIMBURSE 三类的时限并不相同，直接改名会造成"新类型继承了
--       不匹配的时限"。因此这里显式给每条配置赋新值，而不是简单 UPDATE type。
--       取值依据见 sql/init.sql 第五节注释（网络/水电最短，宿舍放宽一档，OTHER 兜底最长）。
--
-- 幂等做法：先把旧类型整体改名为新类型（仅命中旧值），再显式覆盖时限；
--           重复执行时第一步影响 0 行，第二步赋的是同值，结果不变。

-- NETWORK / UTILITY / DORM 三类在旧集合中没有等价物，故用旧行"就地升格"：
--   REPAIR     → NETWORK（同为"报修"里最典型的一类）
--   LEAVE      → UTILITY
--   REIMBURSE  → DORM
UPDATE t_sla_config SET type = 'NETWORK'  WHERE type = 'REPAIR';
UPDATE t_sla_config SET type = 'UTILITY'  WHERE type = 'LEAVE';
UPDATE t_sla_config SET type = 'DORM'     WHERE type = 'REIMBURSE';

-- 按新场景校准各类型时限（网络/水电两档都最短，宿舍放宽一档）
UPDATE t_sla_config SET accept_minutes = 30, finish_minutes = 120 WHERE type = 'NETWORK' AND priority = 0;
UPDATE t_sla_config SET accept_minutes = 10, finish_minutes = 60  WHERE type = 'NETWORK' AND priority = 1;
UPDATE t_sla_config SET accept_minutes = 30, finish_minutes = 120 WHERE type = 'UTILITY' AND priority = 0;
UPDATE t_sla_config SET accept_minutes = 10, finish_minutes = 60  WHERE type = 'UTILITY' AND priority = 1;
UPDATE t_sla_config SET accept_minutes = 60, finish_minutes = 240 WHERE type = 'DORM'    AND priority = 0;
UPDATE t_sla_config SET accept_minutes = 30, finish_minutes = 120 WHERE type = 'DORM'    AND priority = 1;

-- OTHER + 普通（480 分钟）是兜底取值来源，显式确认一次（幂等赋值）
UPDATE t_sla_config SET accept_minutes = 120, finish_minutes = 480 WHERE type = 'OTHER' AND priority = 0;
UPDATE t_sla_config SET accept_minutes = 60,  finish_minutes = 240 WHERE type = 'OTHER' AND priority = 1;

-- ---------- 2) 存量工单类型迁移（无法确定的一律 OTHER） ----------
UPDATE t_work_order SET type = 'OTHER' WHERE type IN ('REPAIR', 'REIMBURSE', 'LEAVE');

-- ---------- 3) 迁移后核对（期望：8 行覆盖、无旧类型残留） ----------
SELECT 'sla_config_rows' AS check_item,
       CAST(COUNT(*) AS CHAR) AS actual,
       '期望 8' AS expectation
FROM t_sla_config
WHERE type IN ('NETWORK','UTILITY','DORM','OTHER') AND priority IN (0,1)
UNION ALL
SELECT 'legacy_types_in_sla_config', CAST(COUNT(*) AS CHAR), '期望 0'
FROM t_sla_config WHERE type IN ('REPAIR','LEAVE','REIMBURSE')
UNION ALL
SELECT 'legacy_types_in_work_order', CAST(COUNT(*) AS CHAR), '期望 0'
FROM t_work_order WHERE type IN ('REPAIR','LEAVE','REIMBURSE');
