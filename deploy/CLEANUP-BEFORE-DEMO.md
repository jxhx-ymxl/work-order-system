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

-- ⑧ 通知表：`ref_type/ref_id` 全为 NULL（D68 记过），所以只能按 content 里的单号匹配——两个口径都跑
SELECT COUNT(*) AS notif_by_ref  FROM t_notification WHERE ref_id IS NOT NULL;
SELECT COUNT(*) AS notif_by_content FROM t_notification WHERE content LIKE '%WO-%';

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
DELETE FROM t_consume_record WHERE event_id REGEXP CONCAT('^order:([0-9]+,)*(', @ids, '):');   -- 见下方"event_id 匹配说明"
DELETE FROM t_message_retry  WHERE event_id REGEXP CONCAT('^order:([0-9]+,)*(', @ids, '):');
DELETE FROM t_event_outbox   WHERE aggregate_id IN (SELECT id FROM tmp_del_ids);
DELETE FROM t_work_order_log WHERE order_id IN (SELECT id FROM tmp_del_ids);
DELETE FROM t_notification   WHERE ref_id IN (SELECT id FROM tmp_del_ids)                       -- 现有数据全是 NULL，走不到
                               OR EXISTS (SELECT 1 FROM tmp_del_ids t
                                           WHERE t_notification.content LIKE CONCAT('%', t.order_no, '%'));

-- 3) 主表最后
DELETE FROM t_work_order WHERE id IN (SELECT id FROM tmp_del_ids);
```

> **`event_id` 匹配说明**：`t_consume_record` / `t_message_retry` 的 `event_id` 形如
> `order:<聚合id>:v<版本>:<事件类型>`，而 `triage-eval.py` 清理时用的是 `^order:(<ids>):` 这种**聚合 id 列表**写法。
> 直接照抄会漏掉"`order:902:v2:ORDER_TRIAGE`"这类带版本段的键 → 上面的正则用 `^order:([0-9]+,)*(<ids>):`
> **同时覆盖"id 紧跟冒号"与"id 后还有 `,<id>` 段"两种形态**。
> **执行时以 §1⑨ 的统计为准**：先跑统计（不改数据），再跑 `SELECT COUNT(*)` 确认删除范围包含统计值；
> **统计到的行数必须 ≥ 实际删除的行数**，反之说明匹配写窄了。

> **`888` / `902` 的处理**：它们**不在** `@ids` 里（不在任何一组区间、也不是压测单），所以上面的语句天然不会命中；
> 但**删之前必须显式确认**：
> `SELECT COUNT(*) FROM t_work_order WHERE id IN (888, 902);` → **期望 2**（删完再查仍是 2）。
> 账本同理：`SELECT COUNT(*) FROM t_message_retry WHERE event_id LIKE 'order:888:%' OR event_id LIKE 'order:902:%';` → 期望 2（删完仍是 2）。

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
--   期望：三行都是 0（通知的 ref_id 全 NULL，所以第三条天然 0；真正的孤儿排查见 §1⑧ 的 content 口径）

-- ② triage_status 三态齐全
SELECT triage_status, COUNT(*) FROM t_work_order GROUP BY triage_status;
--   期望：PENDING / DONE / FAILED 三行都 ≥1

-- ③ SLA 扫描候选数 **< 200**（不再每轮拉满）
SELECT COUNT(*) FROM t_work_order WHERE status IN ('PENDING','ACCEPTED','IN_PROGRESS') AND sla_deadline < NOW();
--   期望：1..2（刻意留的那两张），且 < 200；顺带看运行日志确认没有"本轮拉满 200 条"

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
`888` **已闭环**（`attempt=2 → SUCCEEDED`，工单 `DONE / OTHER-0`）；`902` 在 09-26 06:49 排到**第 6 次重投**，
而阶梯只有 5 档（1m/5m/15m/1h/6h）——所以它的结局只有两种，**两种都要留痕**：

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
| **待填** | 待填 | 待填 | 待填 | 本次取数 |

---

## 9. 执行记录（2026-09-27 服务器执行；**本节只记已确认的事实，数值/原文缺的标"待贴"**）

> **怎么读这一节**：`🟢 委托方报告` = 执行者口头确认的事实（尚未有可粘贴的原文）；
> `⏳ 待贴` = 需要贴原文/数值才能进仓库的项。**不要把"报告"当作"原文已入档"。**

| # | 项目 | 状态 | 内容 |
| --- | --- | --- | --- |
| ① | **删前统计**（`pre-demo-stats.txt`） | **⏳ 待贴** | §1 的 9 组统计是**删前的唯一凭证**（D19 要求"先统计后删"）。文件原文贴回后**整段照录**在本小节 |
| ② | **删后计数** | **⏳ 待贴** | 六张表整治后的行数（§1④ 的同一组 SQL）；与 ① 的差值就是实际删除量 |
| ③ | **三类孤儿 = 0** | 🟢 委托方报告 | 日志 / outbox / 通知三类关联孤儿均为 **0**（复核 SQL 见 §6①：注意通知那一类因 `ref_id` 全 NULL 需按 content 口径看） |
| ④ | **`888` / `902` 原样保留** | 🟢 委托方报告 | 工单 **2 行** + 账本 **2 行**整治后仍在（判据 SQL 见 §3 的"例外"小节：删前删后各查一次） |
| ⑤ | **Redis 键处理** | 🟢 委托方报告 | 处理了与保留集/逾期单相关的幂等键（`sla_notified:*`），以便演示时**真的会发告警**（§4.2 的判据） |
| ⑥ | **报表重算（补数模式）** | **⏳ 待贴** | 若已按 §5 用 `from=2026-09-24;to=2026-09-26` 重算，贴 `SELECT report_date, created_count, completed_count, avg_accept_minutes, avg_finish_minutes, overdue_count, triage_done_count, triage_failed_count FROM t_daily_report WHERE report_date >= '2026-09-24' ORDER BY report_date;`；**水位应仍停在 09-26** |
| ⑦ | **`902` 结局** | **⏳ 待填** | 见 §8 的双分支表（`SUCCEEDED`+`DONE` 或 `PARKED`） |

### 9.1 ⚠ Redis：**`order:seq:*` 一律不动**（清库时最容易顺手删错的键）

| 键 | 处置 | 理由 |
| --- | --- | --- |
| `sla_notified:<orderId>` | **可以清**（且演示前应当清掉保留集的那些） | 它只是"这张单 24h 内是否已告警过"的去重键。留着会让演示时 SLA 扫描打"已发送过、跳过重复通知"，**演出不来告警**（§4.2） |
| `Authorization:*`（Sa-Token 会话/token） | 可留可清 | 清了只是让人重新登录；**没有任何数据后果** |
| **`order:seq:<yyyyMMdd>`** | **绝对不动** | 它是**每日单号计数器**。清掉之后应用会从 1 重新发号，而库里已有同前缀单号 → 新单号撞唯一键：`Duplicate entry 'WO-<日期>-xxxxx' for key 't_work_order.order_no'`；**现象是"提交看起来成功"（HTTP 200）但 body `code=500`**——本项目栽过这个跟头（D45 / README §5.1）。若真被误清，恢复办法只有一个：`SET order:seq:<今日> <库里今日最大序号>` |

> **为什么单列这一条**：清库脚本最容易"整个 DB 清一遍"或"按前缀批量删"——`order:seq:*` 前缀与该清的东西长得一样，
> 但它**不是缓存、是计数器**（删了就有数据一致性后果）。同族的教训是 D45：**"看起来像缓存的键"里混着状态**。

---

**关联**：`docs/DECISIONS.md` D19（两个口径取最大）、D68（888/902 的来历与清理边界）、D71/D72（日报口径与演示库现状）、
`ASYNC-SCHEDULING-PLAN.md` §P7（收尾项 1/5/6/7）、`deploy/DEPLOY-RUNBOOK.md`（部署与巡检）、`sql/probes.sql`（探针）。
