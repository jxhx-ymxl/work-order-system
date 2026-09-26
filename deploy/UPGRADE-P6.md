# P6 步骤 1 操作清单：归档/清理任务（`@XxlJob("archiveJob")`）

**范围**：按保留期**分批删除**三张"只增不减"的表（`t_consume_record` / `t_message_retry` / `t_event_outbox` 的 SENT 行）。
**不含**：`t_work_order` / `t_work_order_log` 的归档**搬运**、`report-generate`、`t_job_watermark` 的实际使用（那是 P6 步骤 2/3）。

---

## 0. 先决条件

- 后端已是含 `archiveJob` 的版本（判据见 §3 的 ①）。
- `XXL_JOB_EXECUTOR_ENABLED=true` 且执行器已注册（见 `UPGRADE-P2.md` §8）。
- **任务只在 xxl-job 里触发**：`archiveJob` 是 `@XxlJob`，executor 关掉时它根本不注册，本地/CI 天然不受影响（无独立开关）。

## 1. 跑迁移脚本（**必须在重启后端之前**）

```bash
cd /opt/workorder/deploy
mysqlq() { docker compose exec -T mysql sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 "$@"' _ "$@"; }

# ⑦ P6 步骤 1：t_archive_log + t_job_watermark + 两个索引
mysqlq work_order < ../sql/hotfix-p6-archive.sql
```

**为什么要跑**：`t_archive_log` 不存在时，`archiveJob` 一被触发就 `handleFail`（**不会**静默）；索引缺失不会报错，
但会在数据量大时把归档变成全表扫，直接顶在线库的 IO。

**幂等**：`CREATE TABLE IF NOT EXISTS` + `information_schema` 判断后拼 DDL，可安全重跑（第二次输出"已存在，跳过"）。
**必须是这条顺序**：`init.sql` → 本脚本。`init.sql` 里**只留了一行指针**（避免两处维护漂移），所以**新库也要跑本脚本**。

### 1.1 判据

```bash
# ① 两张表在
mysqlq -N -B -e "SELECT COUNT(*) FROM information_schema.TABLES
                 WHERE TABLE_SCHEMA='work_order' AND TABLE_NAME IN ('t_archive_log','t_job_watermark');"
#   期望：2

# ② 三个索引在（含防老库缺失的 idx_consumed_at）
mysqlq -N -B -e "SELECT TABLE_NAME, INDEX_NAME FROM information_schema.STATISTICS
                 WHERE TABLE_SCHEMA='work_order'
                   AND INDEX_NAME IN ('idx_created_at','idx_status_sent_at','idx_consumed_at')
                 GROUP BY TABLE_NAME, INDEX_NAME;"
#   期望：t_message_retry.idx_created_at / t_event_outbox.idx_status_sent_at / t_consume_record.idx_consumed_at

# ③ EXPLAIN 走索引 —— ⚠ 判据有口径要求，见下
mysqlq work_order -e "EXPLAIN DELETE FROM t_message_retry WHERE created_at < NOW() - INTERVAL 30 DAY AND MOD(id,1)=0 LIMIT 1000;"
```

> **⚠ EXPLAIN 判据的口径（本机实测踩到，别再踩）**：
> ① **空表或几百行的小表上，优化器会选全表扫**（`key=NULL`、`type=ALL`）——那是正常选择，**不是索引没建上**；
> ② 表里"几乎全是过期行"时，`t_event_outbox` 会选中**旧的 `idx_dispatch` 的 status 前缀**而不是新的 `idx_status_sent_at`；
> ③ 只有在**稳态形状**（例如 2 万行里约 10% 过期）才看得出真实计划：`t_consume_record` → `key=idx_consumed_at`、
> `t_message_retry` → `key=idx_created_at`、`t_event_outbox` → `key=idx_status_sent_at`（`ref=const,const`）。
> 所以判索引要在**有代表性的数据量 + 稳态分布**上做，别拿刚建的库下结论。

## 2. 在控制台建任务

执行器 `work-order-system` → 新增任务：

| 控制台字段 | 值 | 为什么 |
| --- | --- | --- |
| 任务描述 | P6 归档清理（按保留期分批删除） | |
| 调度类型 | **CRON**（例：`0 30 3 * * ?`，凌晨低峰） | 与另两个扫描不同：这是**IO 密集**任务，要错峰，不适合"固定速度全天跑" |
| 运行模式 | BEAN | |
| JobHandler | **`archiveJob`** | 唯一真源 = `@XxlJob` 注解值（`ArchiveJob:71`） |
| 任务参数 | `tables=consume_record,message_retry;retentionDays=30;batchSize=1000;maxBatches=20` | 见下方"参数的三个坑" |
| **路由策略** | **分片广播** | 否则 `getShardTotal()` 恒为 1 —— **只有一台机器在删**，其余实例白跑 |
| **阻塞处理策略** | **丢弃后续调度** | 清理类任务宁可少跑也不重叠；它幂等，下一轮补 |
| **任务超时时间（秒）** | **600** | 上界 = `maxBatches × batchSize` = 2 万行/表；秒级到几十秒足够，600s 留两个数量级余量。不要 0 |
| **失败重试次数** | **0** | 下一轮自然补；重试只放大日志噪音 |
| **调度过期策略** | **DO_NOTHING** | 错过就错过，下一轮按时间谓词重新筛（状态驱动） |
| 子任务ID / 报警邮件 | 留空 | |

### 2.1 参数的三个坑

1. **`tables` 只认白名单短名**：`consume_record` / `message_retry` / `outbox_sent`（写全表名 `t_consume_record` 也认）。
   写别的名字 → **直接失败**（`handleFail`），不会"悄悄跳过"。
2. **`outbox_sent` 必须显式加进 `tables`**，而且它的保留期是 **7 天**（另两张是 30 天）：
   要一起清就写 `retentionDays=7`；写 30 只会更保守（不会误删在途数据）。**不带 `outbox_sent` 时它一行都不删**。
3. **未知键 / 越界数字一律失败**：把 `tables` 打成 `table` 会 `handleFail` 并打出用法——这是刻意的，
   否则表现就是"每轮什么都不删"的静默失效。

## 3. 验证判据（跑一轮之后）

```bash
# ① handler 真的注册了（没有 = 代码没部署，见 UPGRADE-P2.md §9.1）
docker compose logs backend | grep "register jobhandler success" | grep archiveJob

# ② 调度日志：handle_msg 里应有业务摘要（不是只有"执行成功"绿灯）
mysqlq -N -B -e "SELECT id, trigger_code, handle_code, handle_msg FROM xxl_job.xxl_job_log
                 WHERE executor_handler='archiveJob' ORDER BY id DESC LIMIT 3;"
#   期望：trigger_code=200 且 handle_code=200，handle_msg 形如
#         [archive] shard=0/1 cutoff=... 本轮删除 3600 行（consume_record=3000, message_retry=500, outbox_sent=100）；已删完（本轮无剩余）

# ③ 留痕表：每（表 × 分片 × 轮次）一行
mysqlq -N -B -e "SELECT job_key, shard_index, shard_total, deleted_rows, duration_ms, outcome
                 FROM t_archive_log ORDER BY id DESC LIMIT 10;"
#   期望：按 tables 里的表各一行；outcome 为 DONE 或 BUDGET_EXHAUSTED

# ④ 业务事实：过期行应归零，保留期内的行应一行不少
mysqlq -N -B -e "SELECT
  (SELECT COUNT(*) FROM t_consume_record WHERE consumed_at < NOW() - INTERVAL 30 DAY) AS consume_old,
  (SELECT COUNT(*) FROM t_message_retry  WHERE created_at  < NOW() - INTERVAL 30 DAY) AS retry_old,
  (SELECT COUNT(*) FROM t_event_outbox   WHERE status='SENT' AND sent_at < NOW() - INTERVAL 7 DAY) AS outbox_old;"
#   期望：三个都是 0（若 != 0 且 outcome=BUDGET_EXHAUSTED，说明只是这一轮的预算用完，等下一轮）
```

**本机实测基线（2026-09-27，专用库一次性清理，原始输出见 D70）**：
3600 行一轮删净；`maxBatches=1` → 只删 1000 行 + `outcome=BUDGET_EXHAUSTED` + 日志"预算用完，未完下一轮继续"；
紧接着重跑 → `deleted_rows=0`；`shardTotal=3` 三次合计 3000 == 单分片全量 3000，剩余 0。

## 4. 回滚

- **先停任务**（控制台把 `trigger_status` 置 0 / 直接删任务）——删除是**不可逆**的，回滚的第一步永远是"别再删"。
- 代码回滚到不含 `archiveJob` 的提交即可：`t_archive_log` 留着无害（只有写它的任务不存在了）。
- 索引回滚（确认没有别的任务在用）：
  `ALTER TABLE t_message_retry DROP INDEX idx_created_at;`
  `ALTER TABLE t_event_outbox DROP INDEX idx_status_sent_at;`
- **不要**用"把 `retentionDays` 调大"当回滚手段——那是"下一次少删"，删掉的数据回不来。

---

## 5. 日报任务（`@XxlJob("dailyReportJob")`，P6 步骤 2）

### 5.1 先跑迁移脚本（⑧）

```bash
cd /opt/workorder/deploy
mysqlq() { docker compose exec -T mysql sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 "$@"' _ "$@"; }
mysqlq work_order < ../sql/hotfix-p6-report.sql   # ⑧ t_daily_report + t_daily_report_part（水位表复用步骤 1 的）
```

判据：`SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='work_order' AND TABLE_NAME IN ('t_daily_report','t_daily_report_part','t_job_watermark');` → **3**。
漏跑的后果只落在 `dailyReportJob` 上（一触发就 `handleFail`，不静默）。

### 5.2 控制台建任务

| 控制台字段 | 值 | 为什么 |
| --- | --- | --- |
| 任务描述 | P6 日报汇总（每自然日一行） | |
| 调度类型 | **CRON** | 例 `0 30 4 * * ?`（**凌晨 04:30**） |
| 调度时间 | **与归档错峰**：归档 03:30、日报 04:30 | 两者都吃 IO（归档删批量、日报全表聚合），错开可以避免叠峰；不要设在整点或业务高峰 |
| 运行模式 | BEAN | |
| JobHandler | **`dailyReportJob`** | 唯一真源 = `@XxlJob` 注解值 |
| 任务参数 | **留空**（正常模式） | 正常模式 = 从"水位+1"算到**昨天**；历史补数才填 `from=...;to=...` |
| 路由策略 | FIRST（本步不分片） | 分片（分片广播 + `t_daily_report_part`）是步骤 3；本步 `shardTotal` 参数只接受不使用 |
| 阻塞处理策略 | **丢弃后续调度** | 日报幂等（同一天重算覆盖），宁可少跑也不重叠 |
| 任务超时时间（秒） | **600** | 每天一次、只算 1 天（首次补历史才可能多天）；不要 0 |
| 失败重试次数 | **0** | 下一轮自然补（水位没推进，那天会被重算） |
| 调度过期策略 | **DO_NOTHING** | 错过就错过；水位机制保证"下次一起补" |

### 5.3 判据

```bash
# ① handler 注册
docker compose logs backend | grep "register jobhandler success" | grep dailyReportJob

# ② 调度日志里有业务摘要（不是只有"执行成功"绿灯）
mysqlq -N -B -e "SELECT id, trigger_code, handle_code, handle_msg FROM xxl_job.xxl_job_log
                 WHERE executor_handler='dailyReportJob' ORDER BY id DESC LIMIT 3;"
#   期望：handle_code=200，handle_msg 形如
#         [daily-report] （正常模式）shardTotal=1 本轮汇总 1 天（2026-09-26..2026-09-26）；水位 2026-09-25 → 2026-09-26

# ③ 业务事实：日报只算到昨天，且水位跟着推进
mysqlq -N -B -e "SELECT COUNT(*) AS rows_today FROM t_daily_report WHERE report_date = CURDATE();"   -- 期望 0
mysqlq -N -B -e "SELECT * FROM t_daily_report ORDER BY report_date DESC LIMIT 3;"
mysqlq -N -B -e "SELECT watermark_date FROM t_job_watermark WHERE job_key='daily-report';"            -- 期望 = 昨天

# ④ 幂等：再点一次"执行一次"，行数与每列数值都不应变化（第二次正常模式通常是"0 天"，属正常）
# ⑤ 补数（修某一天）：from=2026-09-25;to=2026-09-25 → 该天重算，**水位不动**
# ⑥ 自愈：删掉最后一天的行（水位仍指那天），再跑一次 → 日志出现
#      [daily-report] 自愈：水位日 <D> 没有结果行，从该日重算
```

**本机实测基线（2026-09-27）**：3 天数据集逐列与手写 SQL 一致；`report_date = CURDATE()` 行数 0；
正常连跑两次 + 补数重算一次输出完全一致；删行可自愈；**用触发器让水位写失败时结果行一起回滚**（`row_exists=0`）——
完整原始输出见 `docs/DECISIONS.md` D71。

### 5.4 回滚

- 停任务即可（日报是**可重算**的，重建只需跑一次补数 `from=<起>;to=<止>`）。
- 要清表：`DROP TABLE t_daily_report_part;`（步骤 3 之前删掉无影响）、`DROP TABLE t_daily_report;`。
- ⚠ 别去动 `t_job_watermark` 来"回滚"：水位往后调会让中间的天被跳过（要么用补数、要么把水位设到目标日前一天再让它自己算）。

---

**关联**：`sql/hotfix-p6-archive.sql` / `sql/hotfix-p6-report.sql`（DDL 唯一出处）、
`docs/DECISIONS.md` D70（归档：为什么不用水位线 + EXPLAIN 口径）、D71（日报：口径 / 同事务 / 自愈 + 实测）、
`ASYNC-SCHEDULING-PLAN.md` §5.1/§5.6/§P6、`UPGRADE-P1.md` §1（迁移脚本总顺序：⑦⑧ 就是 P6 两步）。
