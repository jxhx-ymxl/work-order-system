-- ============================================================
-- 索引性能分析探针 — 5 条 EXPLAIN 语句
-- 对应 Issue #45: Explain 执行计划对比报告
-- 执行方式: 在 500 万造数完成后逐条执行，截图保存结果
-- ⚠️ 本脚本仅用于分析，严禁通过 Claude Code 直接连接数据库
-- ============================================================

-- ----------------------------
-- 查询 1: 处理人查待处理工单
-- 目标索引: idx_assignee (assignee_id)
-- 预期: type=ref, key=idx_assignee, rows≈几十~几百
-- ----------------------------
EXPLAIN
SELECT * FROM t_work_order
WHERE assignee_id = 5 AND status = 'ACCEPTED';

-- ----------------------------
-- 查询 2: 提交人查自己全部工单 (分页)
-- 目标索引: idx_submitter (submitter_id)
-- 预期: type=ref, key=idx_submitter, rows≈5万 (500万/100人)
-- Extra: Using index condition; Using filesort (ORDER BY created_at 无索引)
-- ----------------------------
EXPLAIN
SELECT * FROM t_work_order
WHERE submitter_id = 10
ORDER BY created_at DESC
LIMIT 20;

-- ----------------------------
-- 查询 3: SLA 超时扫描 (模拟定时任务扫表)
-- 目标索引: idx_sla (status, sla_deadline) 联合索引
-- 预期: type=range, key=idx_sla, rows≈几百~几千 (未完结且超时的工单极少)
-- Extra: Using index condition
-- ----------------------------
EXPLAIN
SELECT id, order_no, status, sla_deadline
FROM t_work_order
WHERE status IN ('PENDING', 'ACCEPTED', 'IN_PROGRESS')
  AND sla_deadline < NOW();

-- ----------------------------
-- 查询 4: 待分配池查询 (PENDING 工单列表)
-- 目标索引: idx_status (status)
-- 预期: type=ref, key=idx_status, rows≈50万 (500万×10%)
-- Extra: Using index condition; Using filesort
-- 注意: assignee_id IS NULL 不在索引中, 需回表过滤
-- ----------------------------
EXPLAIN
SELECT * FROM t_work_order
WHERE status = 'PENDING' AND assignee_id IS NULL
ORDER BY created_at DESC
LIMIT 20;

-- ----------------------------
-- 查询 5: 全局状态统计 (GROUP BY status)
-- 目标索引: idx_status (status)
-- 预期: type=index, key=idx_status
-- Extra: Using index (覆盖索引，仅读索引页不读数据页)
-- 或: Using index for group-by (MySQL 8.0 松索引扫描优化)
-- ----------------------------
EXPLAIN
SELECT status, COUNT(*) AS cnt
FROM t_work_order
GROUP BY status;
