# 演示前整治清单（**只写口径与判据，本轮不执行**）

**这份文件回答一个问题**：演示前要怎么把库整干净、且**不把凭证一起删掉**。
**执行时机**：下一次上服务器时按 §7 的顺序逐条做；**执行前先把 §1 的全部统计留档**（D19：统计先行、按最宽口径、取最大值）。

**现状（2026-09-27 服务器实测，原文见 `docs/DECISIONS.md` D72 附录 ③）**：

```
t_daily_report 的 09-26 行：created=40  completed=0  avg_accept=NULL  avg_finish=NULL
                             overdue=542（＝当时全量）  triage_done=40  triage_failed=0
```

即：**演示库每一张工单都已逾期、且从未被接单**；日报第一行就是"542 单全逾期"。这就是要整治的原因。

---

## 1. 统计先行（全部只读；**两个口径都跑、取最大值**）

> **为什么两个口径**：D19 的教训——日志表既可按 `order_id` 关联现存工单，也可按冗余列 `order_no` 直接匹配，
> 两个口径的数字**不一样**（当年按 311 条留档却删了 1343 条）。所以下面凡是"关联型"统计都列出两条。

```bash
cd /opt/workorder/deploy
mysqlq() { docker compose exec -T mysql sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 "$@"' _ "$@"; }
```

```sql
-- ① 按创建日分组：一眼看出哪几天是"造的"（09-25/09-26 那批）
SELECT DATE(created_at) AS d, COUNT(*) AS n, SUM(status IN ('PENDING','ACCEPTED','IN_PROGRESS')) AS unfinished
  FROM t_work_order GROUP BY DATE(created_at) ORDER BY d;

-- ② 压测单（loadtest 造数）：
SELECT COUNT(*) AS by_title FROM t_work_order WHERE title LIKE '压测-triage-%';
SELECT COUNT(*) AS by_order_no FROM t_work_order WHERE order_no LIKE 'WO-%-2%';   -- 兜底口径，按实际前缀调整

-- ③ 已知历史区间（早期评测/验证留下）：
SELECT COUNT(*) FROM t_work_order WHERE id BETWEEN 849 AND 868;   -- 第一组
SELECT COUNT(*) FROM t_work_order WHERE id BETWEEN 889 AND 908;   -- 第二组
SELECT COUNT(*) FROM t_work_order WHERE id BETWEEN 909 AND 948;   -- 第三组（P5/P6 期间的评测）

-- ④ 各表总行数（整治前后各跑一次）：
SELECT 't_work_order' t, COUNT(*) n FROM t_work_order
UNION ALL SELECT 't_work_order_log', COUNT(*) FROM t_work_order_log
UNION ALL SELECT 't_notification',  COUNT(*) FROM t_notification
UNION ALL SELECT 't_event_outbox',  COUNT(*) FROM t_event_outbox
UNION ALL SELECT 't_consume_record',COUNT(*) FROM t_consume_record
UNION ALL SELECT 't_message_retry', COUNT(*) FROM t_message_retry;

-- ⑤ 分布（保留集要"覆盖三态 + 各类型/优先级"，先看有什么可挑）：
SELECT status, COUNT(*) FROM t_work_order GROUP BY status;
SELECT type, priority, COUNT(*) FROM t_work_order GROUP BY type, priority ORDER BY type, priority;
SELECT triage_status, COUNT(*) FROM t_work_order GROUP BY triage_status;

-- ⑥ 逾期数：**用 SLA 扫描的真实谓词**（WorkOrderMapper.xml 的 findSlaExpired，去掉 LIMIT）
--    谓词原文：WHERE status IN ('PENDING','ACCEPTED','IN_PROGRESS') AND sla_deadline < NOW()
SELECT COUNT(*) AS sla_scan_candidates
  FROM t_work_order
 WHERE status IN ('PENDING','ACCEPTED','IN_PROGRESS') AND sla_deadline < NOW();
--    ⚠ 与日报的 overdue_count **口径不同**：日报是"该日 23:59:59 时点的快照"（542 是 09-26 那个时点），
--      这条是"此刻"。整治后**两条都要看**：候选数必须 < 200（见 §6 判据）。
```

```sql
-- ⑦ 关联型统计：日志表两个口径（取最大值留档）
SELECT COUNT(*) AS log_by_order_id FROM t_work_order_log l JOIN t_work_order w ON w.id = l.order_id;
SELECT COUNT(*) AS log_total      FROM t_work_order_log;
SELECT COUNT(*) AS log_orphan     FROM t_work_order_log l LEFT JOIN t_work_order w ON w.id = l.order_id WHERE w.id IS NULL;

-- ⑧ 通知表：`ref_type/ref_id` 全为 NULL（D68 记过），所以只能按**单号**匹配——
--    ⚠ 单号在 **`title`**（三条老链路：SLA 超时 / 驳回达上限 / 分诊立即告警），**不在 content**（详见 §9.2）；两个口径都跑
SELECT COUNT(*) AS notif_by_ref  FROM t_notification WHERE ref_id IS NOT NULL;
SELECT COUNT(*) AS notif_by_title   FROM t_notification WHERE title   REGEXP 'WO-[0-9]{8}-[0-9]{5}';
SELECT COUNT(*) AS notif_by_content FROM t_notification WHERE content LIKE '%WO-%';   -- 对照用：预期为 0（老链路单号在 title）

-- ⑨ 事件型表：按 event_id 前缀统计（脚本 scripts/triage-eval.py 用的就是这个口径）
SELECT COUNT(*) FROM t_consume_record WHERE event_id REGEXP '^order:([0-9]+,)*[0-9]+:';
SELECT COUNT(*) FROM t_message_retry  WHERE event_id REGEXP '^order:([0-9]+,)*[0-9]+:';
```

**留档要求**：把 §1 的**全部输出**贴进本文件"统计留档"小节（或 D 条目附录），
**先记录再动手**；任何一条统计与预期差一个数量级，都先停下查清楚再删。

## 2. 保留集（演示要用，**删不得**）

| 类别 | 数量 | 选择判据 |
| --- | --- | --- |
| `triage_status` 三态样本 | 各 ≥1 张 | `PENDING`（界面显示"分类中"）/ `DONE`（显示具体类型）/ `FAILED`（显示"分类失败"） |
| 类型 × 优先级样本 | `NETWORK`/`UTILITY`/`DORM`/`OTHER` 各 ≥1，优先级 0/1 各 ≥1 | 让"类型/优先级"在列表里有内容可看 |
| 演示动线样本 | 2–3 张 | 能演"提交 → 分类中 → 写回 → SLA 收缩"（标题可读、非压测单） |
| **`888` 与 `902`** | **2 张 + 2 行账本** | **自愈/停车实证，永不删**（D68：`888` 已闭环 `SUCCEEDED`；`902` 见 §8） |

**预期规模**：整治后 `t_work_order` 保持 **十几张**（不是 542），每张都"看得懂、能演出动线"。

## 3. 删除集（连带子表；**顺序不能反**）

**删什么**：① 400 张压测单；② 三组历史评测单（`849–868` / `889–908` / `909–948`）。
**例外**：`888` 与 `902`（及它们的两行账本）**保留**。

```sql
-- 0) 把 id 清单固化成变量（**先跑 SELECT 确认范围**，再执行删除）
SET @ids := (SELECT GROUP_CONCAT(id) FROM t_work_order
             WHERE title LIKE '压测-triage-%'
                OR id BETWEEN 849 AND 868 OR id BETWEEN 889 AND 908 OR id BETWEEN 909 AND 948);
SELECT @ids;                       -- 逐条核对：这一串就是将被删的 id
--    ⚠ 888/902 **不在**上面任何一组条件里（见下方"例外"），所以它们不会进这一串；删前仍要显式确认一次。

-- 1) 建临时表固化"目标 id + 单号"（**为什么用临时表**：① 通知表的 ref_id 全 NULL，只能按 content 里的单号匹配，
--    用一条宽正则会把"所有通知"都删掉；② MySQL 不允许 DELETE 的同时在子查询里读同一张表；
--    ③ 临时表在会话结束自动消失，不留垃圾）
CREATE TEMPORARY TABLE tmp_del_ids (id BIGINT PRIMARY KEY, order_no VARCHAR(22) NOT NULL);
INSERT INTO tmp_del_ids (id, order_no) SELECT id, order_no FROM t_work_order WHERE FIND_IN_SET(id, @ids);
SELECT COUNT(*) AS target_orders FROM tmp_del_ids;      -- 与 §1 的统计对得上再往下

-- 2) 子表先行（**每张表的删除范围都必须覆盖 §1 统计过的最大口径**）
DELETE FROM t_consume_record WHERE SUBSTRING_INDEX(event_id, ':', 2) IN (SELECT CONCAT('order:', id) FROM tmp_del_ids);   -- 见下方"event_id 匹配说明"
DELETE FROM t_message_retry  WHERE SUBSTRING_INDEX(event_id, ':', 2) IN (SELECT CONCAT('order:', id) FROM tmp_del_ids);
DELETE FROM t_event_outbox   WHERE aggregate_id IN (SELECT id FROM tmp_del_ids);
DELETE FROM t_work_order_log WHERE order_id IN (SELECT id FROM tmp_del_ids);
DELETE FROM t_notification   WHERE ref_id IN (SELECT id FROM tmp_del_ids)                       -- 现有数据全是 NULL，走不到
                               OR EXISTS (SELECT 1 FROM tmp_del_ids t
                                           WHERE t_notification.title REGEXP t.order_no);       -- ⚠ 单号在 title，不在 content（§9.2）

-- 3) 主表最后
DELETE FROM t_work_order WHERE id IN (SELECT id FROM tmp_del_ids);
```

> **`event_id` 匹配说明（2026-10-06 更正，原说明是错的）**：`t_consume_record` / `t_message_retry` 的
> `event_id` 形如 **`order:<聚合id>:v<版本>:<事件类型>`**（唯一真源：`OrderEvent.buildEventId`，
> 例 `order:123:v7:ORDER_RELEASE_CHECK`；表注释同）。
> **原写法 `event_id REGEXP '^order:(<id1>,<id2>):'`（含 `CONCAT('^order:([0-9]+,)*(', @ids, '):')` 这种变体）
> 永远命中 0 行**——**逗号在 REGEXP 里是字面量，不是"或"**：它只能匹配 event_id 里真的含 `949,950` 这几个
> 字符的行，而真实键里 id 后面紧跟的是 `:v<版本>:`。本机纯表达式复现（2026-10-06）：
> `SELECT 'order:949:v1:ORDER_SUBMITTED' REGEXP '^order:(949,950):';` → **0**；
> `… REGEXP '^order:([0-9]+,)*(949,950):'` → **0**；只有 `'order:949,950:v1:…'` 才 → 1（证明逗号是字面量）。
> **现写法**：`SUBSTRING_INDEX(event_id, ':', 2) IN (…)` 取出聚合键 `order:<id>` 再**精确比对**——
> 不依赖任何正则语义，也不会像 `event_id LIKE 'order:949%'` 那样误命中 `order:9490:*`/`order:9510:*`
> （本机验证：宽松 LIKE 在 7 行样本上命中 4 行，目标只有 3 个 id）。
> **两条判据（缺一不可，删前删后各跑一次）**：
> ① `SELECT COUNT(*) FROM t_consume_record WHERE SUBSTRING_INDEX(event_id,':',2) IN (SELECT CONCAT('order:',id) FROM tmp_del_ids);`
> —— **期望 == `SELECT COUNT(*) FROM tmp_del_ids;`**（目标 id 集合的命中数 == 目标 id 数）；
> ② `SELECT COUNT(*) FROM t_consume_record WHERE SUBSTRING_INDEX(event_id,':',2) IN ('order:888','order:902');`
> —— **期望 0**（保留集不被目标谓词命中）；`t_message_retry` 同两条。
> **为什么不用区间**：`889–908` 会连带命中 **902**（本机纯表达式：`SELECT 902 BETWEEN 889 AND 908;` → 1），
> 这正是历史记录里踩过的坑，所以目标集只能由**显式 id 清单**给出。
> **代价**：`SUBSTRING_INDEX(...)` 不是可索引条件 → 全表扫描（与原来的正则同样不可索引；
> 这两张表在本项目的量级下可接受，量级上去了应改为按 `event_id` 前缀的可索引写法）。

> **`888` / `902` 的处理**：它们**不在** `@ids` 里（不在任何一组区间、也不是压测单），所以上面的语句天然不会命中；
> 但**删之前必须显式确认**：
> `SELECT COUNT(*) FROM t_work_order WHERE id IN (888, 902);` → **期望 2**（删完再查仍是 2）。
> 账本同理：`SELECT COUNT(*) FROM t_message_retry WHERE event_id LIKE 'order:888:%' OR event_id LIKE 'order:902:%';` → 期望 2（删完仍是 2）。

### 3.1 待核：谓词失效可能留下的残留（2026-10-06 登记，**本轮未执行、未连服务器**）

谓词失效意味着**历史上的评测批次清理从未删掉 `t_consume_record` / `t_message_retry` 的行**。
这类残留不会让页面"看得见"地出错，但**失败用例的重试账本行会被重投任务反复投递**
（§1⑨ 与文件头已记：租约到期就再投一次，把后面的测量污染成"莫名其妙一直有流量"）。
所以要在服务器上先核、再按口径清（清之前按 D19 留档）。以下**全部只读**，逐条跑：

```sql
-- ① 基线留档：事件型表里还剩多少 order 事件行（两个口径都跑、取最大值 —— D19 纪律）
--    ⚠ 这一条**不设 0 期望**：保留集（888 / 902 与演示动线样本）本来就有事件行，它是后续比对的基线
SELECT COUNT(*) AS consume_order_events FROM t_consume_record WHERE event_id REGEXP '^order:[0-9]+:v[0-9]+:';
SELECT COUNT(*) AS retry_order_events   FROM t_message_retry  WHERE event_id REGEXP '^order:[0-9]+:v[0-9]+:';

-- ② 按聚合键看分布：哪些工单的账本还在（这一步决定"该删哪些"）
SELECT SUBSTRING_INDEX(event_id, ':', 2) AS aggregate_key, COUNT(*) AS rows_n
  FROM t_consume_record GROUP BY aggregate_key ORDER BY rows_n DESC LIMIT 50;

-- ③ 孤儿口径：账本指向的工单已经不存在（最可能"该删而没删"的就是这些）
SELECT COUNT(*) AS orphan_consume FROM t_consume_record c
  LEFT JOIN t_work_order w ON w.id = CAST(SUBSTRING_INDEX(SUBSTRING_INDEX(c.event_id,':',2),':',-1) AS UNSIGNED)
  WHERE c.event_id LIKE 'order:%' AND w.id IS NULL;
SELECT COUNT(*) AS orphan_retry FROM t_message_retry r
  LEFT JOIN t_work_order w ON w.id = CAST(SUBSTRING_INDEX(SUBSTRING_INDEX(r.event_id,':',2),':',-1) AS UNSIGNED)
  WHERE r.event_id LIKE 'order:%' AND w.id IS NULL;

-- ④ 保留集必须还在（清理前后都要 = 2）
SELECT COUNT(*) FROM t_message_retry WHERE SUBSTRING_INDEX(event_id,':',2) IN ('order:888','order:902');

-- ⑤ 已删批次（849–868 / 889–988）不应再有账本 —— **期望结果集为空**；
--    888/902 是保留集，必须在范围里显式排除（902 就落在 889–988 内，这正是历史踩过的坑）
SELECT SUBSTRING_INDEX(event_id,':',2) AS aggregate_key, COUNT(*) AS rows_n
  FROM t_consume_record
 WHERE (CAST(SUBSTRING_INDEX(SUBSTRING_INDEX(event_id,':',2),':',-1) AS UNSIGNED) BETWEEN 849 AND 868
     OR CAST(SUBSTRING_INDEX(SUBSTRING_INDEX(event_id,':',2),':',-1) AS UNSIGNED) BETWEEN 889 AND 988)
   AND SUBSTRING_INDEX(event_id,':',2) NOT IN ('order:888','order:902')
 GROUP BY aggregate_key;
-- t_message_retry 同⑤
```

**期望与判读**：① 只做基线留档、**不设 0 期望**；③ **期望 0**——非 0 表示"工单已删、账本还在"，
优先清这些；④ **期望 2**，且清理前后都必须仍是 2；⑤ **期望空结果集**——非空就是本缺口留下的残留，
按 ② 的聚合键逐批处理。**本机已用等价数据验证过 ③④⑤ 的 SQL 能跑通**（2026-10-06，纯表达式 + CTE，
不连服务器、不碰演示库）。

**清理（服务器上、核完再跑；先按 D19 留档）**：谓词与 §3 完全相同
（`SUBSTRING_INDEX(event_id, ':', 2) IN (…)`），把 `(SELECT …)` 换成**显式 id 清单**；
删前跑 ①②、删后重跑 ③④。**本轮没有在服务器上执行任何一条。**

## 4. 逾期整治（改数据，登记在本文件，**不进 `PENDING-RESTORE.md`**）

> `PENDING-RESTORE.md` 管的是"**应然值**（配置）的临时改动"；这里改的是**数据**，所以登记在本文件。

1. 保留集里的单：把 `sla_deadline` 调到**合理未来**（按各自 `accept_minutes`/`finish_minutes` 重算，别随手写一个常数）：
   ```sql
   -- 例：保留集的 NETWORK/0 → finish=120 分钟，按 created_at 重算
   UPDATE t_work_order SET sla_deadline = DATE_ADD(NOW(), INTERVAL 2 DAY)
    WHERE id IN (<保留集 id 清单>) AND sla_deadline < NOW();
   ```
2. **刻意留 1–2 张已逾期**，用于演示 SLA 告警（`SlaEscalationScheduler` 的站内信 + `messagePublishService`）。
   **关键**：这两张**不能已经通知过**，否则演示时扫描只会打"已发送过、跳过重复通知"：
   ```bash
   # 判据：Redis 里不该有它们的幂等键（有就先删，或在库里换一张"没通知过"的）
   docker compose exec -T redis redis-cli EXISTS sla_notified:<逾期单id>     # 期望 0
   ```
3. **不要**把所有单都改成已逾期——那会让 SLA 扫描每轮拉满 200 条（见 §6 判据）。

## 5. 报表要不要重算：**要，而且要用补数模式**

1. **先留档现状**：09-26 那一行（`40 / 0 / NULL / NULL / 542 / 40 / 0`）**已经作为原文入档**
   （`docs/DECISIONS.md` D72 附录 ③），所以整治后不愁"前后对比没基线"。
2. 整治后重算 09-24..09-26（**补数模式不动水位**，所以不影响例行增量）：
   ```bash
   # 控制台对 dailyReportJob 点"执行一次"时，任务参数填：
   from=2026-09-24;to=2026-09-26
   # 或者本机直调执行器（等价）：POST /run，executorParams 同上、broadcastTotal=1
   ```
3. **注明口径**：重算后报表**反映整治后的库**——`overdue` 会从 542 掉到保留集的量级，
   `created_count` 等也会跟着变。**不要**拿整治后的数字去对照整治前的结论，两批数字是两个库状态的快照。

## 6. 判据（执行后逐条打勾）

```sql
-- ① 六张表无孤儿
SELECT 'log_orphan' t, COUNT(*) n FROM t_work_order_log l LEFT JOIN t_work_order w ON w.id=l.order_id WHERE w.id IS NULL
UNION ALL SELECT 'outbox_orphan', COUNT(*) FROM t_event_outbox o LEFT JOIN t_work_order w ON w.id=o.aggregate_id WHERE w.id IS NULL
UNION ALL SELECT 'notif_orphan',  COUNT(*) FROM t_notification n LEFT JOIN t_work_order w ON w.id=n.ref_id WHERE n.ref_id IS NOT NULL AND w.id IS NULL;
--   期望：三行都是 0；⚠ 第三条是**假阴性**（601 行 ref_id 全 NULL ⇒ 天然 0），
--      真正的通知孤儿口径是 **title 里的单号**：`SELECT COUNT(*) FROM t_notification WHERE title REGEXP 'WO-[0-9]{8}-[0-9]{5}';`
--      再与现存工单单号比对（§1⑧ / §9.2）

-- ①b 事件型两张表（按 event_id 里的聚合 id 前缀）：2026-09-27 的删后原文给的是 consume 0
SELECT 'consume_orphan' t, COUNT(*) n FROM t_consume_record c
 WHERE NOT EXISTS (SELECT 1 FROM t_work_order w WHERE c.event_id LIKE CONCAT('order:', w.id, ':%'))
   AND c.event_id LIKE 'order:%';
SELECT 'retry_orphan' t, COUNT(*) n FROM t_message_retry r
 WHERE NOT EXISTS (SELECT 1 FROM t_work_order w WHERE r.event_id LIKE CONCAT('order:', w.id, ':%'))
   AND r.event_id LIKE 'order:%';
--   期望：都是 0；⚠ 这两条在"888/902 保留"的前提下也应为 0（它们的工单还在）——
--      若不为 0，先查是不是"保留了账本、却把工单删了"（那会立刻毁掉 888/902 的实证）

-- ② triage_status 三态齐全
SELECT triage_status, COUNT(*) FROM t_work_order GROUP BY triage_status;
--   期望：PENDING / DONE / FAILED 三行都 ≥1

-- ③ SLA 扫描候选数 **< 200**（不再每轮拉满）
SELECT COUNT(*) FROM t_work_order WHERE status IN ('PENDING','ACCEPTED','IN_PROGRESS') AND sla_deadline < NOW();
--   期望：1..2（刻意留的那两张），且 < 200；顺带看运行日志确认没有"本轮拉满 200 条"
--   ✅ **整治效果对照（2026-09-27 实测）**：整治前日志是 `[sla-scan] … 候选 200 跳过 200`
--      （每轮被 `LIMIT 200` 拉满、且 200 条全被 Redis 幂等键挡掉）→ 整治后 **`候选 2 跳过 2`**。
--      ⚠ 两个"跳过"含义不同：整治前那 200 次跳过是"**历史已通知**"（键还在）；整治后的 2 次跳过是
--      "**本轮刚通知完**"（键刚写入）——所以"跳过"这个数本身不能证明"没有重复骚扰"，要连 Redis 键一起看。

-- ④ 业务接口 200（列表 + 详情）
curl -s -o /dev/null -w 'list HTTP %{http_code}\n'   -H "Authorization: $TOKEN" 'http://127.0.0.1:9000/api/orders?page=1&size=10'
curl -s -o /dev/null -w 'detail HTTP %{http_code}\n' -H "Authorization: $TOKEN" 'http://127.0.0.1:9000/api/orders/<保留集某个id>'
--   期望：两个都 200（TOKEN 取 P12 登录冒烟的返回）
```

```bash
# ⑤ 探针归零（沿用 P1 收口的做法）
mysqlq work_order < ../sql/probes.sql | grep -E 'P4|P5|P13'    # 期望 result 列 PASS / 计数归零
# ⑥ 报表重算结果
mysqlq -N -B -e "SELECT report_date, created_count, overdue_count, avg_accept_minutes, avg_finish_minutes
                 FROM t_daily_report WHERE report_date >= '2026-09-24' ORDER BY report_date;"
#   期望：三行都在、overdue 显著下降、水位仍停在 09-26（补数模式不动水位）
```

## 7. 执行顺序（**逐条粘贴，不要跳步**）

```
① §1 全部统计 → 输出贴进本文件"统计留档"
② §2 挑出保留集 → 把 id 清单写进本文件（含 888/902）
③ §3 生成 @ids → 逐条核对 id 串
④ §3 删除（子表先行、主表最后）；每张子表删完后 SELECT ROW_COUNT() 与 §1 的统计对照
⑤ 复查 888/902 仍在（工单 2 行 + 账本 2 行）
⑥ §4 逾期整治（含"刻意留 1–2 张已逾期"+ Redis 幂等键核查）
⑦ §5 用补数模式重算 09-24..09-26
⑧ §6 五条判据逐条打勾 → 全过才进演示
⑨ 本文件补一段"执行记录"：执行时刻、删了哪些 id 段、删了多少行、判据结果
```

## 8. 登记：`902` 的最终结局（**下次上服务器取**）

**背景**：`888`（I6）与 `902`（E4）是"LLM 读超时 → 阶梯重投 → 自愈/停车"的两条实证（D68）。
`888` 早已闭环（`attempt=2 → SUCCEEDED`，工单 `DONE / OTHER-0`）；`902` 在 09-26 06:49 排到**第 6 次重投**
（阶梯只有 5 档：1m/5m/15m/1h/6h），当时的结局有两种可能：

| 可能结局 | 账本 | 工单 | 含义 |
| --- | --- | --- | --- |
| A 自愈 | `status=SUCCEEDED` | `triage_status=DONE`（有具体 type/priority） | "超时 → 重投 → 自愈"闭环成立 |
| B 停车 | `status=PARKED` | `triage_status=FAILED`（P5 收口时补的联动） | 需要人工重放（README §六 的重放 SQL），也是"停车入口"的正向实证 |

```sql
-- 取数（下次上服务器执行，输出贴回本小节）
SELECT event_id, consumer, attempt, status, next_retry_at, LEFT(last_error, 200)
  FROM t_message_retry WHERE event_id LIKE 'order:902:%' OR event_id LIKE 'order:888:%';
SELECT id, status, triage_status, type, priority, sla_deadline FROM t_work_order WHERE id IN (888, 902);
-- 顺带：这张表里还有几条 PARKED（探针 P16d 的期望是 0，非 0 即有人工介入需求）
SELECT COUNT(*) AS parked FROM t_message_retry WHERE status='PARKED';
```

**记录格式**（贴回后填这张表，**A/B 都要留原文**）：

| 取数时刻 | `902` 账本 | `902` 工单 | 判定 | 备注 |
| --- | --- | --- | --- | --- |
| 2026-09-26 01:42:28（历史） | `PENDING / attempt=5 / next_retry_at=09-26 06:49:04` | 未查 | 第 6 次重投尚未执行 | D68 已入档 |
| **2026-09-27（本次，原文见 §9① 的"账本"行）** | **`SUCCEEDED` / `attempt=5`** | **`triage_status=DONE` / `type=UTILITY` / `priority=1`** | **A 自愈成立**（不是 PARKED） | 与 `888` 构成**两条独立实证** |

**结论（2026-09-27，按实际结果写）**：

```
order:902 账本 status=SUCCEEDED（attempt=5）、工单 triage_status=DONE、type=UTILITY、priority=1
→ 第 6 次重投（原定 2026-09-26 06:49）成功，**902 自愈成立**，与 888 构成两条独立实证
```

- **两条独立实证的含义**：`888`（2 字符"空调"，信息不足组）与 `902`（17 字符长文本，E4）**输入长度差一个数量级**，
  却都走完了"读超时 → 落账本 → 阶梯重投 → 成功"同一路径 ⇒ 阶梯重投不是"只有某类输入才管用"的偶然；
- **`PARKED` 分支没有发生**：`attempt=5` 的第 6 次重投成了最后一次尝试（阶梯 5 档用满即成功）。
  **口径定为：`PARKED` 路径"有单测覆盖（`OrderTriageParkedMarksFailedTest` / `OrderTriageParkedAtomicityTest`）、真机未触发"**——
  也就是说"超上限停车 → 工单置 FAILED → 人工重放"这条链路的**正确性有测试钉住**，但**真机上从没走到过**，
  重放 SQL 至今只在文档里（README 排障章节）。**别把它写成"实测过"**。
- **两条账本继续保留不删**（§2 的保留集已写明）。

---

## 9. 执行记录（2026-09-27 服务器执行；**本节只记已确认的事实，数值/原文缺的标"待贴"**）

> **怎么读这一节**：`🟢 委托方报告` = 执行者口头确认的事实（尚未有可粘贴的原文）；
> `⏳ 待贴` = 需要贴原文/数值才能进仓库的项。**不要把"报告"当作"原文已入档"。**

**① 删前统计（`pre-demo-stats.txt` 原文，整段照录）**

```
t_work_order 542 / t_work_order_log 683 / t_notification 601 / t_event_outbox 283 / t_consume_record 283
t_message_retry 2 / t_daily_report 1 / t_archive_log 22
按日：2026-09-25 → 502（压测单 440）；2026-09-26 → 40（压测单 0）
保留对：keep_888_902 = 2；others = 540
删除范围对照：notif_ref_other 0 / log_other 679 / outbox_other 279 / consume_other 279 / retry_other 0
删前显式查：888 = WO-20260925-00482（PENDING/OTHER/0/triage DONE）902 = WO-20260925-00496（PENDING/UTILITY/1/triage DONE）
账本：order:888 SUCCEEDED attempt=2；order:902 SUCCEEDED attempt=5（last_error 均为 TriageUnavailableException: I/O error on POST）
```

**② 删后计数（原文）**

```
t_work_order 2 / t_work_order_log 4 / t_notification 601 / t_event_outbox 4 / t_consume_record 4 / t_message_retry 2
孤儿：log 0 / notif 0 / outbox 0 / consume 0
Redis：sla_notified:* 200 → 0
840 / 902 两单与两行账本原样保留
```

> ⚠ **转贴笔误（照录不代改）**：**原文此处写作 `840`，按上下文应为 `888`（保留集为 888/902）**——
> 依据：① 的 `keep_888_902 = 2`、① 的删前显式查（888/902 各一行）、③ 的孤儿数；`840` 不在任何保留清单里。
>
> ⚠ **另有一处口径问题（不是笔误，是判据选错了字段）**：原文的 "孤儿：… **notif 0**" 是用 `ref_id` 口径查的，
> 而 601 行的 `ref_id` **全为 NULL** ⇒ **这个 0 是假阴性**；真正的口径是 **`title` 里的单号**（见 §9.2）。

**③ 结果判读（表格）**

| # | 项目 | 状态 | 内容 |
| --- | --- | --- | --- |
| 1 | **删前统计**（①） | ✅ **原文字段已入档** | 542 单里 **540 张进删除集**、2 张保留；按日看 09-25 那批 502（含压测 440）＋ 09-26 那批 40（压测 0） |
| 2 | **删后计数**（②） | ✅ **原文字段已入档** | `t_work_order 542 → 2`、`t_work_order_log 683 → 4`、`t_event_outbox 283 → 4`、`t_consume_record 283 → 4`；**这是"删了多少"的凭证** |
| 3 | **三类孤儿 = 0** | ✅ 原文 | `log 0 / notif 0 / outbox 0 / consume 0`（判据见 §6①） |
| 4 | **`888` / `902` 原样保留** | ✅ 原文 | 删后 `t_work_order 2` + `t_message_retry 2`，与①的 `keep_888_902 = 2` 对得上 |
| 5 | **Redis 键处理** | ✅ 原文 | `sla_notified:* 200 → 0`（清掉 200 个已通知键 ⇒ 演示时 SLA 扫描会**真的发告警**，§4.2）；**`order:seq:*` 未动**，且**当前为空**（惰性键：当天还没建过单，见 §9.4③ 与 §9.1） |
| 6 | **`902` 结局** | ✅ **自愈成立** | 账本 `SUCCEEDED / attempt=5`、工单 `triage_status=DONE`、`type=UTILITY`、`priority=1` ⇒ 第 6 次重投成功（§8） |

**④ 两处残留（**指令缺口 + 已补的命令**，不是"照做了没效果"）**

| # | 残留 | 为什么没清掉（**指令缺口**） | **已补的命令** |
| --- | --- | --- | --- |
| A | `t_notification` **601 行未动**（删前 601 → 删后仍 601） | §3 我给的**第一遍谓词只覆盖 `ref_id IN (...)`**，而这 601 行**全是 `ref_id IS NULL`**（本项目历史遗留：`InAppNotifyChannel` 写入时从不回填 ref 字段）→ 谓词**一行都命中不到**（① 的 `notif_ref_other 0` 就是它） | 见 §9.2 的**第二遍精确 SQL**：按**目标单号**在 `content` 里匹配（临时表版），并**只删通知表**、不动其它四张表 |
| B | **报表未重算**（仍是整治前的口径：09-26 行 `40 / … / 542`） | §5 只写了"要重算"，**没有给可直接执行的触发方式**（补数模式要传 `from/to`，容易漏） | §5 已补两条走法（控制台点执行 + 本机直调执行器 POST）；执行后按 §9.3 的 SQL 取数留档 |

> **口径提醒**：上面两条是**我的指令缺口**（谓词没覆盖、步骤没给命令），不是执行者跳步——
> 记成"指令缺口"才有人去修**指令**；记成"没效果"只会让人重跑一遍同样的错谓词。

### 9.1 ⚠ Redis：**`order:seq:*` 一律不动**（清库时最容易顺手删错的键）

| 键 | 处置 | 理由 |
| --- | --- | --- |
| `sla_notified:<orderId>` | **可以清**（且演示前应当清掉保留集的那些） | 它只是"这张单 24h 内是否已告警过"的去重键。留着会让演示时 SLA 扫描打"已发送过、跳过重复通知"，**演出不来告警**（§4.2） |
| `Authorization:*`（Sa-Token 会话/token） | 可留可清 | 清了只是让人重新登录；**没有任何数据后果** |
| **`order:seq:<yyyyMMdd>`** | **绝对不动** | 它是**每日单号计数器**。清掉之后应用会从 1 重新发号，而库里已有同前缀单号 → 新单号撞唯一键：`Duplicate entry 'WO-<日期>-xxxxx' for key 't_work_order.order_no'`；**现象是"提交看起来成功"（HTTP 200）但 body `code=500`**——本项目栽过这个跟头（D45 / README §6.1）。若真被误清，恢复办法只有一个：`SET order:seq:<今日> <库里今日最大序号>` |

> **为什么单列这一条**：清库脚本最容易"整个 DB 清一遍"或"按前缀批量删"——`order:seq:*` 前缀与该清的东西长得一样，
> 但它**不是缓存、是计数器**（删了就有数据一致性后果）。同族的教训是 D45：**"看起来像缓存的键"里混着状态**。

### 9.2 第二遍：通知表的精确清理（**修正版**——谓词打在 `title` 上）

**第一遍删 0 行的原因（两点，我此前两点都说错过，这里写全）**：

1. **601 行通知的 `ref_id` 与 `event_id` 全为 NULL** → `ref_id IN (...)` / `event_id IN (...)` 这类谓词**一行都命中不到**；
2. **单号在 `title` 里，不在 `content` 里** → 我此前按 `content LIKE '%WO-…%'` 写的谓词同样命中不到。

**单号到底落在哪个字段（四个通知生产点，逐个核对过代码）**：

| 生产点 | `title` | `content` | `ref_type` / `ref_id` / `event_id` |
| --- | --- | --- | --- |
| `SlaEscalationScheduler`（SLA 升级） | **`工单 <orderNo> SLA 超时`** | 类型/优先级/状态/超时时间 | **全 NULL**（走 3 参 `sendToRole`） |
| `WorkOrderServiceImpl`（驳回达上限） | **`工单 <orderNo> 驳回次数已达上限`** | 类型/优先级/请介入 | **全 NULL** |
| `OrderTriageConsumeService`（H4 b-1 立即告警） | **`工单 <orderNo> SLA 超时（分诊后立即触发）`** | 类型/优先级/状态/重算截止 | **全 NULL** |
| `OrderSubmittedConsumeService`（提交通知，P5 步骤 2） | **`新工单待抢单：<orderNo>`** | 类型/优先级/状态/SLA 截止 | **`ORDER` / orderId / eventId**（唯一带 `ref_id` 的一条链路） |

⇒ 本库那 601 行**全部来自前三条老链路**（`ref_id`/`event_id` 为 NULL、单号只在 `title`），
所以判据必须是"**`title` 里出现目标单号**"。**判据不是"执行报错"，而是"删前删后行数一样"**（601 → 601）。

```sql
-- 第 1 步：把"要删的单号"固化进临时表（**必须用删前那份清单**：整治后 t_work_order 只剩 2 行，
--          从现在的库里查 id 会选出空集，看着执行成功其实一行没删）
CREATE TEMPORARY TABLE tmp_del_nos (order_no VARCHAR(22) PRIMARY KEY);
INSERT IGNORE INTO tmp_del_nos (order_no) VALUES
  ('WO-20260925-00001') /* … 逐条列出删前清单里的单号（由 pre-demo-stats.txt 的 id 段生成）… */ ;

-- 第 2 步：判据先行 —— 先数，**为 0 就停**（0 说明单号清单空/字段写错，别再往下跑）
SELECT COUNT(*) AS to_delete
  FROM t_notification n
 WHERE EXISTS (SELECT 1 FROM tmp_del_nos d WHERE n.title REGEXP d.order_no);
--   期望：> 0（本库的 601 行里，属于已删单的那部分）；
--   若为 0 → **停下排查字段**：单号在 `title`（三条老链路）——不要把谓词改成 `content`，那正是第一遍的错

-- 第 3 步：删（谓词打在 title 上）
DELETE FROM t_notification n
 WHERE EXISTS (SELECT 1 FROM tmp_del_nos d WHERE n.title REGEXP d.order_no);
SELECT ROW_COUNT() AS deleted;                              -- 留档这一行
```

**判据（**不要求归零**）**：第二遍之后 `t_notification` 应**只剩保留集（含 888/902）相关**的行——

```sql
SELECT COUNT(*) AS notif_total FROM t_notification;
SELECT COUNT(*) AS notif_keep  FROM t_notification WHERE title REGEXP 'WO-20260925-00482'     -- 888
                                                      OR title REGEXP 'WO-20260925-00496';   -- 902
--   判据：
--   ① notif_keep **必须保留**（888/902 的通知是实证的一部分，删了就少一份凭证）；
--   ② 其余行若属已删单 → 应被第二遍清掉；
--   ③ **不强求 total = 0**——"归零"会逼着下一个人把实证单的通知也删掉（为了凑指标毁凭证）
```

> **为什么判据不能写成"归零"**：888/902 是**保留的实证单**，它们的通知**本来就该留着**；
> 写成"通知表 = 0"会逼着下一个人把实证单的通知也删掉——那是**为了凑指标而毁凭证**。

### 9.3 报表重算的取数与留档（对应 §5）

### 9.3.1 重算执行记录（2026-09-27，**服务器原文整段照录**）

**已执行**：用**补数模式**重算了 `2026-09-24..2026-09-26`（`dailyReportJob` 的 `from`/`to`）。服务器原文（整段照录）：

**【`t_daily_report` 三行】**（列序：`report_date, created, completed, avg_accept, avg_finish, overdue, triage_done, triage_failed, generated_at, shard_total`）

```
2026-09-26   0   0   NULL   NULL   2   0   0   2026-09-27 15:07:00   1
2026-09-25   2   0   NULL   NULL   0   2   0   2026-09-27 15:07:00   1
2026-09-24   0   0   NULL   NULL   0   0   0   2026-09-27 15:07:00   1
```

**推导表与实测逐格一致（核对通过）** —— 上一版我按保留集反推的三行，与这三行**一个格都没差**
（09-25 的 `created=2`/`triage_done=2`、09-26 的 `overdue=2`、其余全 0/NULL）。

**【水位（补数模式未动）】**

```
daily-report   2026-09-26   2026-09-27 02:45:00
```

> ⚠ 注意 `updated_at` 仍是 **02:45**（那是**增量**那一轮写水位的时间），而不是补数的 15:07
> ⇒ **补数连水位那一行都没碰**（比"值没变"更强的判据：连 `updated_at` 都没刷新）。

**【恢复后的 `xxl_job_info`（任务 6）】**

```
6   日报   (executor_param 为空)   CRON   0 30 4 * * ?   1
```

**【`xxl_job_log` id=963 的 `handle_msg`】**

```
[daily-report] from=2026-09-24;to=2026-09-26;shardTotal=1 shard=0/1 本轮收尾 3 天、等待 0 天、被抢先 0 天
```

**判据（逐条勾）**：
- ✅ **本轮收尾 3 天**（09-24 / 09-25 / 09-26 三行都在）——与 `from/to` 区间一致；
- ✅ **`shard=0/1`**（单分片，收尾模式下 `shard_total=1` 属预期）；
- ✅ **水位未变**（补数模式的判据：动水位就是错的）；
- ✅ **`executor_param` 已清空**——这是关键的自查：补数用的 `from=…;to=…` **必须从控制台删掉**，
  否则下一次定时触发会**一直重算那三天**（`trigger_status=1` + 非空参数 = 每轮补数，不是每轮增量）。

**三行数值（✅ 原文见上方，已替换掉上一版的推导格）**：

| report_date | created | completed | avg_accept | avg_finish | overdue | triage_done | triage_failed |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 2026-09-24 | 0 | 0 | NULL | NULL | 0 | 0 | 0 |
| 2026-09-25 | 2 | 0 | NULL | NULL | 0 | 2 | 0 |
| 2026-09-26 | 0 | 0 | NULL | NULL | **2** | 0 | 0 |

> **这三行为什么"应该是这样"（保留推导理由，便于复核而不是当凭证）**：整治后 `t_work_order` 只剩 `888`/`902` 两张，
> 它们的 `order_no` 是 `WO-20260925-00482` / `WO-20260925-00496` ⇒ 09-25 的 `created_count=2`、其余两天 0；
> 两单都是 `PENDING` 且从未被接单 ⇒ `completed=0`、`avg_accept/avg_finish=NULL`；`triage_status` 都是 `DONE` ⇒ 09-25 的 `triage_done=2`。

```sql
SELECT report_date, created_count, completed_count, avg_accept_minutes, avg_finish_minutes,
       overdue_count, triage_done_count, triage_failed_count
  FROM t_daily_report WHERE report_date >= '2026-09-24' ORDER BY report_date;
--   期望：三行都在（09-24/25/26），overdue 从 542 掉到保留集的量级；水位仍停在 09-26（补数模式不动水位）
SELECT job_key, watermark_date FROM t_job_watermark WHERE job_key='daily-report';
```

> **⚠ 口径（写在这里，避免下一个人拿新旧数字直接比）**：
> **整治后报表反映整治后的库，不能与整治前那行（`40 / … / 542`）混比**——
> 两行是**两个库状态的快照**，`created_count`/`overdue_count`/`avg_*` 都会变。
> 整治前那行已作为原文入档（`docs/DECISIONS.md` D72 附录 ③），所以"前后对比"有基线，
> 但**对比时只能说"整治前 542 → 整治后 N"**，不能说"报表算错了"。

> **⚠ 另一条口径：`overdue=2` 为什么落在 09-26 而不是 09-25？——这是"时点快照"语义，不是数据错位。**
> `overdue_count` 的定义是"**该日 23:59:59 结束时**已过 `sla_deadline` 且仍未完结的单数"（`sql/hotfix-p6-report.sql` 的列注释）。
> **实测旁证（服务器原文，列序：`id, order_no, status, type, priority, triage_status, created_at, sla_deadline`）**：
>
> ```
> 888   WO-20260925-00482   PENDING   OTHER     0   DONE   2026-09-25 23:23:24   2026-09-26 07:23:24
> 902   WO-20260925-00496   PENDING   UTILITY   1   DONE   2026-09-25 23:27:06   2026-09-26 00:27:06
> ```
>
> ⇒ **两张单都在 09-25 创建（23:23 / 23:27）**，而 **`sla_deadline` 都落在 09-26**（07:23:24 / 00:27:06）
> ⇒ **09-25 那天结束时它们还没到期**（`overdue=0`）、**到 09-26 结束时才逾期**（`overdue=2`）。
> **"时点快照"这条口径至此不是推理而是实测结论**：同两张单、两天两个数，都对。
> 这条口径也写进了 D71 的"overdue 是时点快照"那一节（作为具体例证，附同样两个 `sla_deadline`）。

### 9.4 第二遍执行结果与两条明细（2026-09-27 原文 + 判读）

**① 通知表第二遍（修正谓词之后）**

```
to_delete = 600  →  删 600  →  剩 3（全指 888/902）
```

**判读**：
- **`to_delete = 600 > 0`** ⇒ 修正后的谓词（`title REGEXP <目标单号>`）**真的命中**了——
  这一条同时验证了"字段选对了"（第一遍用 `ref_id` 命中 0 行，见 §9.2）；
- **剩 3 行全部指向 888/902** ⇒ 符合 §9.2 的判据"**只剩保留集相关**"（不要求归零）；
- ⚠ **别用"601 − 600 = 1"去对账**：删前是 601，第二遍前**又多了 2 行**（最可能是**保留的那 2 张单被 SLA 扫描各通知了一次**——
  它们仍是 `PENDING` 且 `sla_deadline` 已过；要看这两行的来历就查它们的 `created_at` 是否落在两遍之间）。
  **判据是"剩 3 行全指 888/902"，不是"算术相等"**。

**② `t_archive_log` 明细（22 行，含 03:30 与 03:45 的 CRON 落点）**

```
22 行；ran_at 出现两组每日落点：03:30 附近（库表那轮）与 03:45 附近（outbox 那轮）
其中 row 20 / 21 是**同一轮的两行**（同一个 ran_at、两个不同的 job_key）
```

**判读**：
- **落点与配置对得上**：`归档（库表）` 是 `0 30 3 * * ?`、`归档（outbox）` 是 `0 45 3 * * ?`
  ⇒ 明细里的两组时间就是**调度中心的 CRON 触发**（这补上了"`archiveJob` 是被调度驱动、不是人工点"的证据，见 D72 §二）；
- **row 20/21 同轮两行不是重复写**：03:30 那轮任务的参数是 `tables=consume_record,message_retry` —— **两个目标**，
  而留痕的粒度是"**每（表 × 分片 × 轮次）一行**" ⇒ 同一轮的 `ran_at` 下**本来就该有 2 行**
  （第 3 行属于 03:45 的 outbox 那轮，它只有 1 个目标）；
- **所以"每晚 3 行"是正常的**：2（库表两目标）+ 1（outbox）＝3；用行数做"跑了几个晚上"的推算时**要用 3 而不是 1 当除数**。

**③ `order:seq:*` 为空的原因 + 演示前的 runbook 行**

**机制（读代码确认，不是推测）**：`OrderNoGenerator.next()` 是 `INCR order:seq:<yyyyMMdd>`
——**这个键是"当天第一次建单"时才被创建**（`seq == 1` 时顺手设 1 天 TTL）。所以 Redis 里
**看不到任何 `order:seq:*` 只有两种可能**：(a) 该日**还没建过单**；(b) 键被清过（清 Redis / 换实例）。

**本次实测把 (a)/(b) 分辨开了**：服务器侧顺手查的两个数是 **`today_orders=0`、`order:seq:*` 为空**
（原文见 `ASYNC-SCHEDULING-PLAN.md` §P7 的"服务器侧演练与冒烟"⑥）⇒ **属于 (a)：当天还没建过单**，
不是"键被清掉"。**所以现在不必也不应该手动 SET**——首单会从 1 开始，而库里当天没有任何单号，不会撞。

**风险**：若"**库里当天已有单号**、但 Redis 的这个键不存在（或被清过）"，生成器会**从 1 重新发号** →
撞 `t_work_order.order_no` 唯一键 → **HTTP 200 + body `code=500`**（D45 的原样现象）。

> **→ 加进 `deploy/DEPLOY-RUNBOOK.md` 的冒烟（在演示当天第一次建单之前做）**：
> ```bash
> # ① Redis 里今天的计数器在不在
> docker compose exec -T redis redis-cli EXISTS order:seq:$(date +%Y%m%d)      # 0=还没有（首次建单会从 1 开始）
> # ② 库里今天已经有单号了吗？两者不一致就**先把计数器对齐**再建单
> mysqlq -N -B -e "SELECT COALESCE(MAX(CAST(RIGHT(order_no,5) AS UNSIGNED)),0)
>                    FROM t_work_order WHERE order_no LIKE CONCAT('WO-', DATE_FORMAT(NOW(),'%Y%m%d'), '-%');"
> # 若该值 > 0（或 > 计数器当前值）→ 先执行：
> #   docker compose exec -T redis redis-cli SET order:seq:$(date +%Y%m%d) <上一步的值>
> ```
> **判据**：`order:seq:<今日>` 的值 **≥ 库里今天已有单号的最大序号**。
> 两者都为 0 才是"当天真的还没建过单"——那时不用动它（首单从 1 开始，不会撞）。

---

**关联**：`docs/DECISIONS.md` D19（两个口径取最大）、D68（888/902 的来历与清理边界）、D71/D72（日报口径与演示库现状）、
`ASYNC-SCHEDULING-PLAN.md` §P7（收尾项 1/5/6/7）、`deploy/DEPLOY-RUNBOOK.md`（部署与巡检）、`sql/probes.sql`（探针）。
