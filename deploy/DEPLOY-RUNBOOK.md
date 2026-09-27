# 部署 Runbook（**一条可重复执行的清单**，P7 步骤 1）

**用途**：把"部署一次要做什么"固定成**顺序不能变**的清单。它治的是本项目连续踩过的"漏一步"：

| 漏的那一步 | 现象 |
| --- | --- |
| 漏 `--build`（只 `up -d`） | 执行器报 `job handler [xxx] not found`（新代码没进容器） |
| 漏跑迁移 | 任务/接口报 `Table 'work_order.x' doesn't exist` / `Unknown column` |
| 新建任务没点"启动" | **什么都没有**：不失败、不告警、一行 `xxl_job_log` 都没有（`trigger_status=0`） |

**前提**：服务器上有 `/opt/workorder`（deploy key 只读，够用）；`deploy/.env` 已按 §2 的键清单存在。
**每一步都有判据** —— 判据不通过**不要进下一步**（否则错误会在很远的地方才炸）。

---

## 0. 顺序总览（唯一权威顺序）

```
git pull  →  按序跑迁移脚本（每支幂等，见 §2）  →  docker compose up -d --build
          →  就绪门（启动日志，§3）  →  冒烟（5 项，§4）  →  巡检（§5）
```

## 1. 拉代码

```bash
cd /opt/workorder
git pull
git log --oneline -1          # 判据：与你预期的提交一致（记下这个 hash）
```

## 2. 迁移脚本（**必须在重建后端之前跑完**）

```bash
cd /opt/workorder/deploy
# 连库统一走这个函数：口令只在 mysql 容器内部展开，宿主 shell 不出现口令
mysqlq() { docker compose exec -T mysql sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 "$@"' _ "$@"; }
```

| # | 脚本 | 什么时候要手动跑 | 幂等机制 | 作用 / 漏跑的现象 |
| --- | --- | --- | --- | --- |
| ⓪ | `sql/init.sql` | **只在全新库**跑 | ⚠ **不幂等**（裸 `CREATE TABLE`，重跑报 1050） | 建全部业务表 + 种子数据。**老库绝不跑**（CLAUDE.md §4） |
| ① | `sql/hotfix-p0b-order-type.sql` | 类型还是旧枚举（`REPAIR/LEAVE/REIMBURSE`）时 | 幂等：`UPDATE ... WHERE type='旧值'`，重跑影响 0 行 | 类型枚举迁移（含 `t_sla_config` 8 行）；漏跑 → 新类型查不到 SLA 配置 |
| ② | `sql/hotfix-role-permissions.sql` | 权限绑定缺失（探针 P7/P8 报错）时 | 幂等：`INSERT IGNORE` | 角色-权限绑定；漏跑 → 权限码对不上 |
| ③ | `sql/hotfix-p1-outbox-init.sql` | 老库没有 `t_event_outbox` | 幂等：`CREATE TABLE IF NOT EXISTS` | outbox 主表；漏跑 → **投递链路直接报错**（不是静默） |
| ④ | `sql/hotfix-outbox-sending-state.sql` | 表是"步骤 2 形态"、缺 `owner/claimed_at` | 幂等：`information_schema` 判定 + `PREPARE` | 补 SENDING 中间态两列与索引 |
| ⑤ | `sql/hotfix-p4-consume-record.sql` | 老库没有 `t_consume_record` | 幂等：`CREATE TABLE IF NOT EXISTS` | 消费去重表（含 `idx_consumed_at`）；漏跑 → 消费者 INSERT 失败，消息被"ACK+日志"吃掉 |
| ⑥ | `sql/hotfix-p4-message-retry.sql` | 老库没有 `t_message_retry` | 幂等：`CREATE TABLE IF NOT EXISTS` | 重试账本；漏跑 → 失败消息既无账本又被 ACK = 静默丢失 |
| ⑦ | `sql/hotfix-p5-triage-status.sql` | 任何库（P5 步骤 1 之前） | 幂等：`information_schema` 判定 + `PREPARE` | `t_work_order.triage_status`；漏跑 → **提交工单直接 SQL 报错** |
| ⑧ | `sql/hotfix-p5-submit-notification.sql` | 任何库（P5 步骤 2 之前） | 幂等：同上 | `t_notification.event_id` + 唯一键；漏跑 → **所有站内信写入**报 `Unknown column` |
| ⑨ | `sql/hotfix-p6-archive.sql` | 任何库（P6 步骤 1 之前） | 幂等：建表 `IF NOT EXISTS` + 判定 + `PREPARE` | `t_archive_log` / `t_job_watermark` / `idx_created_at` / `idx_status_sent_at`；漏跑 → `archiveJob` 一触发就 `handleFail` |
| ⑩ | `sql/hotfix-p6-report.sql` | 任何库（P6 步骤 2 之前） | 幂等：同上（含"表已存在但缺列"的补列） | `t_daily_report` / `t_daily_report_part`；漏跑 → `dailyReportJob` 一触发就 `handleFail` |
| ⑪ | `sql/xxl-job/tables_xxl_job.sql` | **只在新建 `xxl_job` 库**时（首次初始化由 compose 的 initdb 自动跑；**老库要手动导一次**） | ⚠ **不幂等**（8 张表是裸 `CREATE TABLE`，重跑报 1050） | 调度中心自己的库（含示例任务 seed）。库已在就别再跑 |

```bash
# 稳妥做法：**不确定就跑一遍**（除 ⓪⑪ 之外全都幂等，重复执行只打印"已存在，跳过"）
for f in hotfix-p0b-order-type hotfix-role-permissions hotfix-p1-outbox-init hotfix-outbox-sending-state \
         hotfix-p4-consume-record hotfix-p4-message-retry hotfix-p5-triage-status hotfix-p5-submit-notification \
         hotfix-p6-archive hotfix-p6-report; do
  echo "== $f =="; mysqlq work_order < ../sql/$f.sql
done
```

**判据**（跑完立刻查；缺哪个就点名了该跑哪支）：

```bash
# 业务库结构：7 张表 / 3 个索引 / 2 列（与 SchemaStartupCheck 的清单一致）
mysqlq -N -B -e "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA='work_order'
                 AND TABLE_NAME IN ('t_event_outbox','t_consume_record','t_message_retry','t_archive_log',
                                     't_job_watermark','t_daily_report','t_daily_report_part');" | wc -l   # 期望 7
mysqlq -N -B -e "SELECT CONCAT(TABLE_NAME,'.',INDEX_NAME) FROM information_schema.STATISTICS
                 WHERE TABLE_SCHEMA='work_order'
                   AND INDEX_NAME IN ('idx_consumed_at','idx_created_at','idx_status_sent_at')
                 GROUP BY TABLE_NAME, INDEX_NAME;"                                                        # 期望 3 行
mysqlq -N -B -e "SELECT CONCAT(TABLE_NAME,'.',COLUMN_NAME) FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA='work_order'
                   AND ((TABLE_NAME='t_work_order' AND COLUMN_NAME='triage_status')
                     OR (TABLE_NAME='t_notification' AND COLUMN_NAME='event_id'));"                          # 期望 2 行
```

## 3. 起 / 重建容器

```bash
cd /opt/workorder/deploy
docker compose up -d --build          # 6 个服务：mysql / redis / rabbitmq / xxl-job-admin / backend / frontend
docker compose ps --format '{{.Name}}\t{{.Status}}'   # 判据：6 个 Up（且 Restarts=0）
```

- **必须带 `--build`**：只 `up -d` 会用旧镜像 → 执行器报 `job handler [xxx] not found`（见 §6）。
- **只想重建一个服务时**用 `--no-deps`（例：`docker compose up -d --no-deps xxl-job-admin`）：
  否则 compose 会连带重建它依赖的 mysql，而 mysql 的服务定义里挂着 initdb 挂载（数据在命名卷里不会丢，但会白白重启一次）。

## 4. 就绪门（**三条启动日志**，全部出现才算起好了）

```bash
docker compose logs backend  | grep -E "Started WorkOrderApplication"
docker compose logs xxl-job-admin | grep -E "Started XxlJobAdminApplication"
docker compose logs backend  | grep -E "\[启动自检\] LLM 探测通过（triage 可用）"
#   三条都在 = 就绪。⚠ 第 3 条是"triage 可用"的判据：出现 `未配置 LLM_API_URL` 或 `HTTP 400`
#   说明 triage 处于降级态（服务仍可用，但 AI 会把所有单判成 OTHER）——按 README 排障表处理。

# 第 4 条（P7 新增，专治"漏跑迁移"）：
docker compose logs backend | grep -E "\[启动自检\] 数据库结构完整"
#   期望：`数据库结构完整：12 项必需的表/索引/列全部就位（P4/P5/P6 迁移已跑过）`
#   若看到 `数据库结构缺失 N 项`，**逐行**照它点名的脚本去跑（§2），然后重启后端再看一次
```

## 5. 冒烟（5 项，逐项打勾）

```bash
# ① 后端可达 + 管理员能登录（P12a 口径）
curl -s -o /dev/null -w 'backend login HTTP %{http_code}\n' -X POST http://127.0.0.1:9000/api/login \
  -H 'Content-Type: application/json' -d '{"username":"admin","password":"admin123"}'      # 期望 200

# ② 四个 handler 都注册了（**不是**"控制台看着在线"）
docker compose logs backend | grep -c "register jobhandler success"                        # 期望 4
docker compose logs backend | grep "register jobhandler success"                           # 逐条核对四个名字

# ③ 容器数
docker compose ps --format '{{.Name}}' | wc -l                                             # 期望 6

# ④ 调度中心的任务都在、且**已启动**（新建默认 trigger_status=0=停止，不点"启动"就永远不跑）
mysqlq -N -B -e "SELECT id, job_desc, executor_handler, trigger_status FROM xxl_job.xxl_job_info;"
#   期望（2026-09-27 实测形态）：**6 行 = 5 个自有任务 + 1 个平台示例任务**
#     1  测试任务1     demoJobHandler      trigger_status=0   ← 平台自带，停着，建议删除
#     2  超时释放扫描  releaseTimeoutScan  trigger_status=1
#     3  SLA升级扫描   slaEscalationScan   trigger_status=1
#     4  归档（库表）  archiveJob          trigger_status=1
#     5  归档（outbox）archiveJob          trigger_status=1
#     6  日报          dailyReportJob      trigger_status=1
#   ⚠ **archiveJob 有两个任务是正常的**：保留期下限按表算（库表 30 天 / outbox 7 天），一条参数满足不了两者
#   （见 D70 §六、D72 附录 ①）。**判据是"5 个自有任务全为 1"**，不是"handler 去重后 4 个"。

# ⑤ 执行器心跳在刷（判据是 update_time **前进**，不是"控制台显示在线"）
mysqlq -N -B -e "SELECT registry_value, update_time FROM xxl_job.xxl_job_registry WHERE registry_key='work-order-system';"
echo "等 35 秒再查一次，update_time 必须变化"; sleep 35
mysqlq -N -B -e "SELECT registry_value, update_time FROM xxl_job.xxl_job_registry WHERE registry_key='work-order-system';"

# ⑥ 单号计数器对齐（**只在"当天第一次建单之前"做一次**；演示/压测前尤其要做）
#    为什么：OrderNoGenerator 是 `INCR order:seq:<yyyyMMdd>`——**键是当天第一次建单时才创建的**。
#    若"库里当天已有单号、Redis 这个键却不存在/偏小"（清过 Redis、换过实例），生成器会从 1 重发 → 撞唯一键 →
#    现象是 **HTTP 200 + body `code=500`**（D45 的原样事故）。
docker compose exec -T redis redis-cli EXISTS order:seq:$(date +%Y%m%d)      # 0 = 还没有（首单从 1 开始）
mysqlq -N -B -e "SELECT COALESCE(MAX(CAST(RIGHT(order_no,5) AS UNSIGNED)),0) AS db_max_today
                   FROM t_work_order WHERE order_no LIKE CONCAT('WO-', DATE_FORMAT(NOW(),'%Y%m%d'), '-%');"
#   判据：`order:seq:<今日>` 的值 ≥ 库里今天已有单号的最大序号（两个都是 0 就不用动它）
#   不满足就先对齐：docker compose exec -T redis redis-cli SET order:seq:$(date +%Y%m%d) <上一步的 db_max_today>
```

巡检（可选，长期跑起来之后每周看一眼）：见 `README.md` §六 5.6 的两条巡检 SQL。

## 6. 常见失败（**这两轮的真账**）

| 现象 | 根因 | 处置 |
| --- | --- | --- |
| 触发任务 → 接口返回 `code:500, msg: job handler [xxx] not found`；**`xxl_job_log` 里则是 `handle_code=0` + `handle_msg=NULL`** | ① **代码没部署**（忘记 `--build`）；② **任务配置里 handler 名写错**（大小写/连字符/张冠李戴） | 先看启动日志有没有**那四行** `register jobhandler success`：没有 = ①重新 `docker compose up -d --build backend`；有 = ②读回 `xxl_job_info.executor_handler` 与注解逐字对照（唯一真源 = `@XxlJob` 注解值）。⚠ **两种指纹都要认**：这个场景在日志里是 `0/NULL`（触发成功但**没执行**），**不是 500**（实测见 D72 附录 B） |
| 任务 `handle_code=500`，`handle_msg` 里 `Table 'work_order.x' doesn't exist` / `Unknown column` | **漏跑迁移** | 按 §2 跑对应脚本；跑完**重启后端**，看 §4 第 4 条自检转成"结构完整"。⚠ 这种失败**不会自动报警**（`alarm_status=2` 只代表平台告警流程成功，见 D72 ③） |
| 任务在控制台一切正常，但 `xxl_job_log` 一行都没有 | **新建任务没点"启动"**（`trigger_status=0`） | `SELECT id, job_desc, trigger_status FROM xxl_job_info;` → 置为 1（控制台点"启动"） |
| 出现不认识的容器 / 容器名带随机后缀 | `docker run --rm` + `timeout` 组合：**`timeout` 杀掉的只是客户端，容器还在跑** | `docker ps -a` 找出游离容器并 `docker rm -f <name>`；临时脚本一律**不用** `docker run --rm` + `timeout`（要超时就用 `docker run -d` + 自己收尾） |
| **手工起后端进程**（不走 compose）时，忘了带 `DB_NAME` / `MYSQL_PASSWORD` → 进程**直接退出**，日志里是 Hikari 连不上库的堆栈 | `DataSourceAvailabilityCheck` 的 **fail-fast**（连不上库的应用没有价值，故意不让它起来） | 用 §3 的 `docker compose up -d --build`（环境变量由 `.env` 统一注入）；**必须手工起进程时**，把 §1 记下的变量一次带全。**演练实录（2026-09-27）**：我在半破坏演练里漏了这两个变量，进程起不来、日志只有 `NonRegisteringDriver.connect … PoolBase.newConnection` 的堆栈——**这不是"应用坏了"，是"进程没带环境"** |

## 7. 半破坏演练（**P7 步骤 1 的验收动作**，建议部署完顺手做一次）

```bash
# ① 造缺口：删掉日报主表
mysqlq work_order -e "DROP TABLE t_daily_report;"
# ② 重启后端 → 启动自检必须在日志里点名
docker compose restart backend && sleep 25
docker compose logs --since 2m backend | grep -A3 "数据库结构缺失"
#   期望：明确点名 `表 t_daily_report 不存在/缺失 → 跑 sql/hotfix-p6-report.sql`
# ③ 按 runbook 恢复
mysqlq work_order < ../sql/hotfix-p6-report.sql
# ④ 重启后自检转为"结构完整"，且**日报任务真的能跑**
docker compose restart backend && sleep 25
docker compose logs --since 2m backend | grep "数据库结构完整"
#   然后在控制台对 dailyReportJob 点一次"执行一次"，判据在 xxl_job_log：
mysqlq -N -B -e "SELECT id, trigger_code, handle_code, LEFT(handle_msg,120) FROM xxl_job.xxl_job_log
                 WHERE executor_handler='dailyReportJob' ORDER BY id DESC LIMIT 1;"
#   期望：trigger_code=200 且 handle_code=200
```

## 8. 回滚

- **代码**：`git revert <hash>` 或 `git checkout <上一个 hash>` → 重新 `docker compose up -d --build`。
- **库**：迁移脚本都是"只增不改语义"的（建表/加列/加索引），**通常不需要回滚**；
  真要清掉 P6 的两张表见 `sql/hotfix-p6-report.sql` 末尾的注释（附风险：日报可重算，归档留痕删了就没凭证）。
- ⚠ **不要**用 `docker compose down -v` 当回滚：命名卷会被删，MySQL 数据一起没。

---

**关联**：`deploy/UPGRADE-P1.md` §1（迁移脚本总顺序与为什么）、`deploy/UPGRADE-P2.md` §3/§5/§9（admin 与两个扫描任务）、
`deploy/UPGRADE-P6.md` §5（归档与日报）、`README.md` §六 5.6（巡检 SQL）、`docs/DECISIONS.md` D72（三条平台语义）、
`src/main/java/com/workorder/config/SchemaStartupCheck.java`（§4 第 4 条自检的实现与"为什么不阻止启动"）、
`deploy/CLEANUP-BEFORE-DEMO.md`（**演示前整治清单**：统计口径 / 保留集 / 删除集 / 逾期整治 / 报表重算 / 判据 —— 只写不执行，执行时按它逐条来）。
