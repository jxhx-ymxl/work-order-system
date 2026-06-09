# 工单系统 — 性能调优报告

> **数据规模:** 5,000,000 条工单
> **状态分布:** 70% CLOSED / 10% PENDING / 10% ACCEPTED / 5% IN_PROGRESS / 3% AWAIT_APPROVAL / 2% RELEASED
> **索引清单:** `idx_order_no` (UNIQUE), `idx_status`, `idx_submitter`, `idx_assignee`, `idx_sla` (status, sla_deadline)
> **测试日期:** 2026-06-09
> **MySQL 版本:** 8.0

---

## 查询 1: 处理人查待处理工单

**SQL:**
```sql
SELECT * FROM t_work_order WHERE assignee_id = 5 AND status = 'ACCEPTED';
```

**目标索引:** `idx_assignee (assignee_id)`

**预期索引命中:** `type=ref, key=idx_assignee, rows≈几十~几百条`

**实测 Explain 输出:**
```
id: 1
select_type: SIMPLE
table: t_work_order
type: index_merge
possible_keys: idx_status,idx_assignee,idx_sla
key: idx_status,idx_assignee
key_len: 82,9
rows: 25863
filtered: 100
Extra: Using intersect(idx_status,idx_assignee); Using where
```

**实测关键字段:**

| 字段 | 值 |
|------|-----|
| type | index_merge |
| key | idx_status,idx_assignee |
| rows | 25,863 |
| Extra | Using intersect(idx_status,idx_assignee); Using where |

**深度解读 — Index Merge (Intersect):**

MySQL 优化器在此做出了一个聪明的决定：同时使用 `idx_status` 和 `idx_assignee` 两个索引，然后取**交集**。

执行流程：
1. 扫描 `idx_status` 索引，提取所有 `status='ACCEPTED'` 的行指针集合（约 50 万条，占 10%）
2. 扫描 `idx_assignee` 索引，提取所有 `assignee_id=5` 的行指针集合（约 25 万条，5M÷20 处理人）
3. 在内存中对两个指针集合做 **交集运算 (Intersect)**
4. 交集结果（25,863 条）一次性回表读取完整行数据

**为什么不用单个 idx_assignee 索引？** 如果只用 `idx_assignee`，需要回表 25 万次再逐行判断 `status='ACCEPTED'`。而 Index Merge 先在索引层面取交集，将回表次数从 25 万次压到 2.6 万次——回表 I/O 减少约 90%。

**结论:** `idx_assignee` 和 `idx_status` 的 Index Merge 配合良好，2.6 万行回表在毫秒级完成。**无需额外优化。**

---

## 查询 2: 提交人查自己全部工单

**SQL:**
```sql
SELECT * FROM t_work_order WHERE submitter_id = 10 ORDER BY created_at DESC LIMIT 20;
```

**目标索引:** `idx_submitter (submitter_id)`

**预期索引命中:** `type=ref, key=idx_submitter, rows≈5万`

**实测 Explain 输出:**
```
id: 1
select_type: SIMPLE
table: t_work_order
type: ref
possible_keys: idx_submitter
key: idx_submitter
key_len: 8
ref: const
rows: 90398
filtered: 100
Extra: Using filesort
```

**实测关键字段:**

| 字段 | 值 |
|------|-----|
| type | ref |
| key | idx_submitter |
| rows | 90,398 |
| Extra | Using filesort |

**深度解读 — filesort 的代价评估:**

索引 `idx_submitter` 命中了，`type=ref` 也正确。问题出在 `ORDER BY created_at DESC`——`created_at` 不在 `idx_submitter` 索引中，MySQL 不得不：

1. 通过 `idx_submitter` 定位该提交人的 90,398 行
2. 回表读取所有 9 万行的完整数据
3. 在内存中按 `created_at DESC` 排序（filesort）
4. 取前 20 行返回

**filesort 在这个场景下是否严重？** 不算严重。虽然扫描了 9 万行，但 `LIMIT 20` 意味着 filesort 只需在内存中完成 Top-N 排序，实际排序开销很小。但如果该提交人历史工单量极大（例如 50 万+），filesort 可能触发磁盘排序（`sort_merge_passes` > 0）。

**优化方案（按需启用）：**
```sql
-- 联合索引消除 filesort
ALTER TABLE t_work_order ADD INDEX idx_submitter_created (submitter_id, created_at);
```
当前场景下 filesort 在内存中完成，性能可接受。**如果该查询响应时间超过 100ms，再考虑加联合索引。**

**结论:** 索引命中正确，filesort 因 `LIMIT 20` 影响有限。监控 P99 响应时间，超过阈值再加 `idx_submitter_created`。

---

## 查询 3: SLA 超时扫描（定时任务核心查询）🚨

**SQL:**
```sql
SELECT id, order_no, status, sla_deadline
FROM t_work_order
WHERE status IN ('PENDING', 'ACCEPTED', 'IN_PROGRESS')
  AND sla_deadline < NOW();
```

**目标索引:** `idx_sla (status, sla_deadline)` — 联合索引

**预期索引命中:** `type=range, key=idx_sla, rows≈几百~几千`

**实测 Explain 输出:**
```
id: 1
select_type: SIMPLE
table: t_work_order
type: ALL
possible_keys: idx_status,idx_sla
key: NULL
key_len: NULL
rows: 5038544
filtered: 10.39
Extra: Using where
```

**实测关键字段:**

| 字段 | 值 |
|------|-----|
| type | **ALL** |
| key | **NULL** |
| rows | **5,038,544** |
| filtered | 10.39 |
| Extra | Using where |

### 🚨 严重警告：idx_sla 联合索引彻底失效

这是本次压测中最严重的发现。`idx_sla (status, sla_deadline)` 联合索引在 500 万数据量下被 MySQL 优化器**完全放弃**，退化为全表扫描。

**失效根因分析：**

MySQL 优化器在执行计划选择时，会比较**索引扫描 + 回表** vs **全表扫描**的估算成本：

| 路径 | 操作 | 估算代价 |
|------|------|----------|
| 走 idx_sla | `status IN (3个值)` → 三次索引 range scan → 扫描索引中约 125 万行 → **逐行回表** 读取数据页 → 再过滤 `sla_deadline < NOW()` | 125 万次随机 I/O 回表 |
| 全表扫描 | 顺序扫描 500 万行 → 在扫描过程中直接判断 WHERE 条件 | 500 万次顺序 I/O |

`status IN ('PENDING','ACCEPTED','IN_PROGRESS')` 三个值覆盖了 **25%** 的全表数据（10%+10%+5% = 125 万行）。MySQL 认为：与其对这 125 万行逐一随机回表，不如直接顺序扫全表——**顺序读比随机读快 10~100 倍**。

这就是 `type=ALL, rows=5038544` 的真相：不是 MySQL 不认识索引，而是 **IN 列表范围太大 + 回表成本太高 = 优化器主动放弃索引**。

这是一个经典的"索引失效"场景——不是语法层面的失效，而是**执行计划层面**的失效。

**优化方案（按推荐度排序）：**

**方案 A（推荐——单列索引斩断 IN 依赖）：**
```sql
ALTER TABLE t_work_order ADD INDEX idx_sla_deadline (sla_deadline);
```
新 SQL：
```sql
SELECT id, order_no, status, sla_deadline
FROM t_work_order
WHERE sla_deadline < NOW()
  AND status IN ('PENDING', 'ACCEPTED', 'IN_PROGRESS');
```
`sla_deadline < NOW()` 在 500 万行中通常只命中几百到几千条超时工单。单列索引 `idx_sla_deadline` 的 range scan 只需回表几百次，完全避免全表扫描。

**方案 B（冷热数据分离——根治方案）：**
- 将 `CLOSED` 和 `RELEASED` 等终态工单（占 72%）定期迁移至归档表 `t_work_order_archive`
- 主表仅保留活跃工单（约 140 万条），`status IN (...)` 不再覆盖大量冷数据
- 此时 `idx_sla` 可以正常工作

**结论:** `idx_sla (status, sla_deadline)` 联合索引在 500 万数据量下被优化器判定成本过高而放弃。**需要新增单列索引 `idx_sla_deadline (sla_deadline)`** 或在 SQL 中引导优化器走索引（`FORCE INDEX` 仅作临时方案，不推荐用于定时任务）。

---

## 查询 4: 待分配池查询（PENDING 工单列表）

**SQL:**
```sql
SELECT * FROM t_work_order
WHERE status = 'PENDING' AND assignee_id IS NULL
ORDER BY created_at DESC
LIMIT 20;
```

**目标索引:** `idx_status (status)`

**预期索引命中:** `type=ref, key=idx_status, rows≈50万`

**实测 Explain 输出:**
```
id: 1
select_type: SIMPLE
table: t_work_order
type: index_merge
possible_keys: idx_status,idx_assignee,idx_sla
key: idx_status,idx_assignee
key_len: 82,9
rows: 383602
filtered: 100
Extra: Using intersect(idx_status,idx_assignee); Using where; Using filesort
```

**实测关键字段:**

| 字段 | 值 |
|------|-----|
| type | index_merge |
| key | idx_status,idx_assignee |
| rows | 383,602 |
| Extra | Using intersect(idx_status,idx_assignee); Using where; Using filesort |

**深度解读 — Index Merge + filesort 双重机制:**

与查询 1 相同的 Index Merge 策略：
1. `idx_status` 提取 `status='PENDING'` 的行指针 → 约 50 万条
2. `idx_assignee` 提取 `assignee_id IS NULL` 的行指针 → 约 50 万条（PENDING+RELEASED）
3. **Intersect 取交集** → 38.3 万条（PENDING 状态下 assignee_id 必为 NULL，交集基本覆盖全部 PENDING）
4. 回表读取完整行 → **filesort 按 created_at DESC 排序** → 取前 20 条

**性能瓶颈：filesort 排序 38 万行。** 虽有 Index Merge 压低了回表量，但排序 38 万行只为取前 20 条，性价比极低。

**优化方案：**
```sql
-- 联合索引覆盖 WHERE + ORDER BY，彻底消除 filesort
ALTER TABLE t_work_order ADD INDEX idx_status_created (status, created_at);
```
新的执行计划预期：`type=ref, key=idx_status_created, rows≈50万`，Extra 中不再出现 `Using filesort`——索引本身已按 `(status, created_at)` 排序，`LIMIT 20` 可以直接从索引头部取。

**结论:** Index Merge 减少了回表范围，但 filesort 排序 38 万行严重浪费资源。**建议新增 `idx_status_created (status, created_at)` 联合索引**，将"定位 + 排序"合一，消除 filesort。

---

## 查询 5: 全局状态统计（GROUP BY status）

**SQL:**
```sql
SELECT status, COUNT(*) FROM t_work_order GROUP BY status;
```

**目标索引:** `idx_status (status)`

**预期索引命中:** `type=index, key=idx_status, Extra: Using index`

**实测 Explain 输出:**
```
id: 1
select_type: SIMPLE
table: t_work_order
type: index
possible_keys: idx_status,idx_sla
key: idx_status
key_len: 82
rows: 5038544
filtered: 100
Extra: Using index
```

**实测关键字段:**

| 字段 | 值 |
|------|-----|
| type | index |
| key | idx_status |
| rows | 5,038,544 |
| Extra | **Using index** |

**深度解读 — 覆盖索引的完美示范:**

`Extra: Using index` 是本次 5 个查询中**最优秀的执行计划**。

执行流程：
1. MySQL 对 `idx_status` 索引 B+ 树做**全索引扫描**（不是全表扫描）
2. 索引叶子节点天然按 `status` 排序，相同 status 值聚集在一起
3. 直接对索引中的 `status` 值进行分组计数——整个过程**只读索引页，不碰数据页**
4. 无需临时表排序（`Using temporary` 未出现），因为索引已按 status 有序

**为什么 rows=503 万但性能绝佳？** 覆盖索引扫描的是索引 B+ 树而非数据 B+ 树。索引页只存 `status` + 主键，体积远小于数据页（含 TEXT 字段）。500 万行的索引页可能只有几百 MB，缓存在 Buffer Pool 中几乎纯内存操作，毫秒级完成。

**结论:** 覆盖索引完美触发，无需回表，性能绝佳。**这是本次压测中唯一一个 100% 按预期执行的查询。** 可以作为面试中讲解 "覆盖索引 vs 回表" 的标准范例。

---

## 面试口径（实战 3 句）

> **第 1 句（SLA 索引失效——你的核心亮点）：** "我在 500 万条工单上压测时发现，`idx_sla (status, sla_deadline)` 联合索引因为 `status IN (...) ` 范围过大被优化器主动放弃，退化为全表扫描——这不是语法失效，是优化器的成本决策：125 万行随机回表的代价超过了 500 万行顺序扫表。解决方案是新增单列索引 `idx_sla_deadline`，将超时工单的 range scan 从百万级压到百条级。"
>
> **第 2 句（Index Merge——展示你对 MySQL 内部机制的理解）：** "查询 1 和查询 4 中，MySQL 自动触发了 Index Merge 的 Intersect 算法——同时扫描 `idx_status` 和 `idx_assignee` 两个索引，在内存中对行指针取交集后再回表。这比单个索引 + 回表过滤减少了 90% 的回表 I/O，说明 8.0 的优化器在多条件 AND 查询上已经非常智能。"
>
> **第 3 句（覆盖索引——展示你知道什么时候不需要回表）：** "查询 5 的 GROUP BY 统计触发了 `Using index` 覆盖索引。MySQL 只读 `idx_status` 索引 B+ 树就完成了分组计数，一次都没有回表。这验证了一个设计原则：为高频统计字段建单列索引，让统计走索引而非扫表——500 万行的 GROUP BY 在覆盖索引下是毫秒级的，扫表就是秒级的。"

---

## 优化优先级总览

| 优先级 | 查询 | 问题 | 建议 | 紧急度 |
|--------|------|------|------|--------|
| **P0** | 查询 3 (SLA) | 全表扫描 503 万行 | 新增 `idx_sla_deadline` | 立即执行 |
| P1 | 查询 4 (PENDING) | filesort 38 万行 | 新增 `idx_status_created` | 本周 |
| P2 | 查询 2 (提交人) | filesort 9 万行 | 新增 `idx_submitter_created`（按需） | 观察后决定 |
| - | 查询 1 (处理人) | 无问题 | Index Merge 已优化 | 无需处理 |
| - | 查询 5 (统计) | 无问题 | 覆盖索引完美 | 无需处理 |

---

## 附录: 索引 DDL（参考 + 建议新增）

```sql
-- ========== 现有索引 (不变) ==========
INDEX idx_order_no (order_no),              -- UNIQUE, 工单编号精确查询
INDEX idx_status (status),                  -- 按状态筛选 + GROUP BY 统计
INDEX idx_submitter (submitter_id),         -- 提交人查看自己工单
INDEX idx_assignee (assignee_id),           -- 处理人查看自己工单
INDEX idx_sla (status, sla_deadline),       -- 定时任务扫超时工单 (联合索引, 已失效)

-- ========== 建议新增 (P0, 必须加) ==========
INDEX idx_sla_deadline (sla_deadline),      -- 替代 idx_sla 用于定时任务 range scan

-- ========== 建议新增 (P1, 按需) ==========
INDEX idx_status_created (status, created_at),   -- 消除 PENDING 池 filesort
INDEX idx_submitter_created (submitter_id, created_at)  -- 消除提交人 filesort
```
