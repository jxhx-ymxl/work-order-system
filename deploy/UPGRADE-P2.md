# P2 步骤 1 操作清单：部署 xxl-job-admin（第 6 个容器，**不接执行器**）

> 本轮范围：**只把调度中心（管理台 + `xxl_job` 库）部署起来**。
> **不接执行器**、不改 `@Scheduled`、不动业务链路 —— 所以 backend **刻意不 `depends_on` 它**：
> admin 停摆不影响业务（这正是方案 §P2 那条"可靠性净倒退"的缓解措施之一）。
> 逐条可粘贴执行；判据都要看到预期输出才算过。

## 0. 先决条件（缺一不可）

- 代码已 `git pull` 到含本清单的提交；
- `deploy/.env` 里已有 `MYSQL_ROOT_PASSWORD`（admin 复用它连库）；
- 磁盘/镜像：服务器需要能拉到 `xuxueli/xxl-job-admin:2.4.0`（约 200MB；若拉不动见文末备选）。

## 1. 建库（**升级已有部署必须手动跑这一步**）

```bash
cd /opt/workorder/deploy
# 首次初始化（全新的空卷）不用跑这一步：compose 已把脚本挂进 mysql 的 initdb 目录
#   （../sql/xxl-job/tables_xxl_job.sql → /docker-entrypoint-initdb.d/20-xxl-job.sql）；
# 但**已初始化过的老库不会重跑 initdb**，所以老库要手动导一次（脚本自带 CREATE DATABASE IF NOT EXISTS + USE xxl_job，可安全重跑）。
docker compose exec -T mysql sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4' \
  < ../sql/xxl-job/tables_xxl_job.sql
# 判据：8 张表
docker compose exec -T mysql sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -B -e "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='"'"'xxl_job'"'"';"'
#   期望输出：8
#   并确认默认账号存在（首次登录用 admin/123456，**上线前必须改掉**）：
docker compose exec -T mysql sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -B --default-character-set=utf8mb4 -e "SELECT username FROM xxl_job.xxl_job_user;"'
#   期望输出：admin
```

> 中文列一定要带 `--default-character-set=utf8mb4`：容器内 mysql 客户端默认 latin1，否则中文显示成 `?`（见 D68）。

## 2. 补 `.env`（一个可选键）

```bash
cd /opt/workorder/deploy
grep -q '^XXL_JOB_ACCESS_TOKEN=' .env || cat >> .env <<'EOF'
# 管理台与执行器之间的通信口令。
# **接执行器（P2 步骤 2）之前必须设置成非空，且与后端 xxl.job.access-token 同值**，否则执行器注册会被拒。
# 留空**仅限"只起管理台"的本地/本轮场景**（admin 自己不需要 token 就能起来看控制台）。
XXL_JOB_ACCESS_TOKEN=
EOF
grep '^XXL_JOB_ACCESS_TOKEN=' .env | sed 's/=.*/=***/'
```

## 3. 起服务（只起这一个，不动其它 5 个）

```bash
cd /opt/workorder/deploy
# ⚠ 必须带 --no-deps，见下面的原因
docker compose up -d --no-deps xxl-job-admin
docker compose ps                     # 判据：6 个服务在列，xxl-job-admin 为 Up/healthy
docker compose logs --tail 20 xxl-job-admin | grep -E 'Started XxlJobAdminApplication|Tomcat started'
#   期望：Tomcat started on port(s): 8080 (http) with context path '/xxl-job-admin'
#         Started XxlJobAdminApplication in x.xx seconds
```

**为什么必须 `--no-deps`（否则会把 mysql 一起重建）**：本步骤前一步给 `mysql` 服务的 `volumes:` 加了一行
（把 `sql/xxl-job/tables_xxl_job.sql` 挂进 `/docker-entrypoint-initdb.d/20-xxl-job.sql`）——
**改了 compose 里 mysql 的服务定义**，于是 `docker compose up -d xxl-job-admin` 会按依赖关系把 `mysql` 也纳入
"重建/重启"范围（admin `depends_on: mysql(service_healthy)`）。用 `--no-deps` 就只动 admin 这一个容器。

- **mysql 的数据不会丢**：它落在**命名卷** `mysql-data` 里，重建容器不碰卷（这也是为什么 `docker compose down` 不带 `-v` 是安全的）。
- **但"首次"跑完整 `docker compose up -d` 时 mysql 仍会被重建一次，属预期，不是故障**：
  服务定义变过一次，Compose 会重建该容器（数据仍来自卷）；之后定义的指纹不再变，就不会再重建。
  所以即使你忘了 `--no-deps`，后果也只是"mysql 容器重建 + 业务短暂断开"，**不是数据损坏**——但这仍应避免，
  因为它把一次"加个调度台"的操作变成了"业务中断几秒"。
- 判据：`docker compose ps` 里 `workorder-mysql` 的 `STATUS` 时间戳**没有变**（没被重建）。

**实测证据（2026-09-26，服务器）**：`docker inspect` 的 `CreatedAt` 显示
`workorder-mysql` / `workorder-redis` / `workorder-rabbitmq` **仍是 `2026-09-24 23:22:38`（已运行 38 小时）**——
也就是说这次 `up -d --no-deps xxl-job-admin` **确实没有连带重建它们**。
（反过来说：如果哪天这三个的 `CreatedAt` 变成了"刚刚"，那就说明有人漏了 `--no-deps`，
或者 compose 里又改了它们的服务定义——**这条时间戳就是判据本身**。）

## 4. 验证管理台（**它只绑 127.0.0.1，必须走 SSH 隧道**）

```bash
# 在你自己的机器上开隧道（把 <server> 换成服务器地址）
ssh -L 8080:127.0.0.1:8080 <server>
# 然后浏览器打开（注意**有上下文路径**）：
#   http://127.0.0.1:8080/xxl-job-admin      默认账号 admin / 123456（登录后立刻改）

# 在服务器上也可以直接验（不依赖浏览器）：
curl -s -o /dev/null -w 'HTTP %{http_code}\n' http://127.0.0.1:8080/xxl-job-admin/
#   期望：302（跳到登录页）
curl -s -L -o /dev/null -w 'HTTP %{http_code}\n' http://127.0.0.1:8080/xxl-job-admin/toLogin
#   期望：200
curl -s -L http://127.0.0.1:8080/xxl-job-admin/ | grep -o '<title>[^<]*</title>'
#   期望：<title>任务调度中心</title>
```

## 5. 验证"没有牵连业务"（**这一步别省**）

> **⚠ 本轮这一步是"基线留档"，不是真正的判据**：此刻后端**还没接 admin**（执行器未接），
> 所以"停 admin 业务照常"是**平凡成立**的——它证明不了任何 P2 的设计意图，只把"改造前的基线"记下来。
> **真正的判据在 P2 步骤 2 之后**：那时调度由 admin 驱动，判据变成
> **停止 admin → 调度任务停跑 → 本地 `@Scheduled` 兜底接手**（这正是 §P2 那条"可靠性净倒退"的缓解措施：
> 本地兜底必须默认开启并与 xxl-job 并行）。本步骤先把基线记下，届时好对照。

```bash
# ① backend 不依赖 admin：停掉 admin，业务照常
docker compose stop xxl-job-admin
curl -s -o /dev/null -w 'backend login HTTP %{http_code}\n' -X POST http://127.0.0.1:9000/api/login \
  -H 'Content-Type: application/json' -d '{"username":"admin","password":"admin123"}'
#   期望：200（admin 停着也不影响）
# ② 进程内兜底扫描仍在跑（P1 的承诺不能被 P2 步骤 1 悄悄破坏）
docker compose logs --since 2m backend | grep -E '\[outbox\]|超时释放'
docker compose start xxl-job-admin
```

## 6. 内存（6 容器形态）

```bash
docker stats --no-stream --format '{{.Name}}  {{.MemUsage}}' \
  workorder-mysql workorder-redis workorder-rabbitmq workorder-backend workorder-frontend workorder-xxl-job-admin
free -m
# 本机实测参考值：**xxl-job-admin ≈ 358.8 MiB**（`JAVA_TOOL_OPTIONS=-Xmx256m -XX:MaxMetaspaceSize=128m`，含 metaspace/线程栈）
#   —— 服务器实测参考值：**admin 240.7 MiB / 512 MiB**（admin 启动后约 20 秒、未热身）。
#   ⚠ 两个数**不可混比**（同参数 `-Xmx256m`，但取数时刻/负载不同，见方案 §1.6.8 教训 4）。
#   —— heap 上限必须显式设：JVM 默认按宿主内存取上限（4G 上约 1G），会撞穿 mem_limit=512m 被 OOMKilled。
#   但**不能写 `JAVA_OPTS`**：本镜像 entrypoint 是 `java -jar $JAVA_OPTS /app.jar $PARAMS`，
#     `$JAVA_OPTS` 落在 `-jar` 之后 = 应用参数，**传了也不生效**。compose 用的是 **`JAVA_TOOL_OPTIONS`**。
# 6 容器整栈的真机基线请贴回项目对话，由文档侧写进 ASYNC-SCHEDULING-PLAN.md §1.6（届时 4/5/6 三种形态并列）。
```

**判据（两条，缺一不可）**：

```bash
# ① 生效性：启动日志必须有这一行（JVM 启动器真的吃到了）
docker compose logs xxl-job-admin | grep 'Picked up JAVA_TOOL_OPTIONS'
#   期望：Picked up JAVA_TOOL_OPTIONS: -Xmx256m -XX:MaxMetaspaceSize=128m

# ② 传参位置：看**真正的 java 子进程** argv —— ⚠ 别查 /proc/1/cmdline（PID 1 是 sh，显示的是没展开的脚本文本）
docker compose exec -T xxl-job-admin sh -c 'cat /proc/[0-9]*/cmdline 2>/dev/null | tr "\0" "\n" | head -20'
#   你会看到 `java -jar …`（JAVA_OPTS 那个位置即使有 -Xmx 也只是应用参数）；
#   想拿出最强证据，临时给 JAVA_TOOL_OPTIONS 追加 `-XX:+PrintCommandLineFlags`，日志会出现
#   `-XX:MaxHeapSize=268435456 -XX:MaxMetaspaceSize=134217728`（本机实测原文）——那是"上限真的生效"。
```

## 7. 回滚（万一要退）

```bash
docker compose stop xxl-job-admin && docker compose rm -f xxl-job-admin
# 库可以留着（不影响业务）；要彻底清掉再 DROP DATABASE xxl_job;
```

## 附：镜像拉不动的备选（本地实测遇到过 registry 不可达）

```bash
docker images | grep xxl-job                                     # ① 看本机/服务器是否已有该镜像
docker save xuxueli/xxl-job-admin:2.4.0 | gzip > xxl.tgz          # ② 在能拉动的机器上导出
scp xxl.tgz <server>:~ && ssh <server> 'docker load < ~/xxl.tgz'   # ③ 搬运
```

## 8. 执行器接入（P2 步骤 2a：**只注册，不迁任务**）

> 本轮**不加 `@XxlJob`、不动 `@Scheduled`**——执行器起来、能注册、能心跳即可，业务行为一个字不变（迁移是 2b）。
> compose 里 backend 的三个键都已给好默认值，**服务器上一般不需要额外配置**。

```bash
cd /opt/workorder/deploy
# 连库统一走这个函数（口令只在容器内读；见 UPGRADE-P1.md 文首约定②）
mysqlq() { docker compose exec -T mysql sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 "$@"' _ "$@"; }

# ① 后端容器里执行器开关与 admin 地址是否就位
docker compose exec -T backend sh -c 'echo "enabled=$XXL_JOB_EXECUTOR_ENABLED admin=$XXL_JOB_ADMIN_ADDRESSES token=${XXL_JOB_ACCESS_TOKEN:+已设置}"'
#   期望：enabled=true admin=http://xxl-job-admin:8080/xxl-job-admin

# ② 判据（只看 DB，不看日志）：注册行数 + 心跳时间
mysqlq -N -B -e "SELECT CONCAT('registry_rows=', COUNT(*)) FROM xxl_job.xxl_job_registry WHERE registry_key='work-order-system';"
#   期望：registry_rows=1（首次查为 0 属正常：执行器每 30s 心跳一次，等一会儿再查）
mysqlq -N -B -e "SELECT registry_value, update_time FROM xxl_job.xxl_job_registry WHERE registry_key='work-order-system';"
#   判据：隔 35 秒再查一次，update_time 必须**前进**（证明心跳在刷，不是一条僵尸行）
```

**执行器组不会自动创建**（2.4.0 的行为）：`registry_rows=1` 但组不存在是正常的。
去控制台"执行器管理 → 新增执行器（AppName=`work-order-system`，注册方式=**自动注册**）"，或跑等价的幂等 SQL：

```bash
mysqlq xxl_job -e "INSERT INTO xxl_job_group(app_name, title, address_type, address_list, update_time)
  SELECT 'work-order-system','工单系统（自动注册）',0,NULL,NOW()
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM xxl_job_group WHERE app_name='work-order-system');"
mysqlq -N -B -e "SELECT CONCAT('group_rows=', COUNT(*)) FROM xxl_job.xxl_job_group WHERE app_name='work-order-system';"
#   期望：1
```

### ⚠ 两个最容易查错方向的坑（本机都实测过）

1. **`accessToken` 两侧不同值 → 注册被拒**，执行器日志里的原文是
   `xxl-job registry fail, … registryResult:ReturnT [code=500, msg=The access token is wrong., content=null]`。
   本机第一次就是这么失败的：admin 用了**镜像自带的默认 token**、执行器发空值。
   **判据**：compose 里两侧都取同一个键 `XXL_JOB_ACCESS_TOKEN`（admin 走 `PARAMS --xxl.job.accessToken`，backend 走环境变量），
   **要么都设、要么都空**；看到 "token is wrong" 就直接查这两个值，**别往网络方向查**。
2. **"registry success" 默认打不出来**：2.4.0 里成功是 **DEBUG**、失败才是 INFO。
   所以**判据是 DB 里的 `xxl_job_registry` 行 + `update_time` 是否刷新**。
   想看那行日志，把 `logging.level.com.xxl.job` 临时设为 `debug` 即可。

> **本机实测补充（2026-09-26）**：Windows + Docker Desktop 下"容器内 admin 回连宿主机执行器 9999"
> **在本机是通的**（容器内 `/dev/tcp` 测 `192.168.2.13:9999` 与 `host.docker.internal:9999` 均可达）。
> 也就是说本机把注册链路**完整验过**了；但这条依赖宿主的防火墙与网络形态，**服务器上仍按上面 ①② 复核一次**。

## 9. 两个调度任务的配置（控制台）

**前提**：§8 已通过（`registry_rows=1` 且 `xxl_job_group` 里有 `work-order-system`），且后端容器 `XXL_JOB_EXECUTOR_ENABLED=true`。

**这一节的动作全在控制台里做，仓库里没有落点**——两个 `@XxlJob` 方法只管"被触发之后怎么跑"，
**调度周期、阻塞策略、超时、重试、过期策略都必须在这里显式设**。
字段名以 `sql/xxl-job/tables_xxl_job.sql` 的 `xxl_job_info` 表为准（2.4.0 官方版，随仓库提供，可逐字核对）。

| 控制台字段 | `xxl_job_info` 列 | `releaseTimeoutScan` | `slaEscalationScan` | 说明 |
| --- | --- | --- | --- | --- |
| 执行器 | `job_group` | work-order-system | work-order-system | 即 §8 建的那个执行器组 |
| 任务描述 | `job_desc` | 兜底释放扫描 | SLA 超时升级扫描 | 控制台里唯一能一眼分辨两个任务的地方 |
| 负责人 | `author` | （按实际填） | （按实际填） | 出问题时找谁 |
| 报警邮件 | `alarm_email` | 可空 | 可空 | 本项目不发邮件，留空 |
| 调度类型 | `schedule_type` | **FIX_RATE（固定速度，主口径）** | **FIX_RATE（主口径）** | 与本地 `@Scheduled(fixedRate)` 语义一致（按秒的固定速度）。**不要把 CRON 当主口径**：CRON 要额外核对时区与错峰，换来的只有"对齐到整分"；真要用 CRON，必须与下一行的调度配置成对改（两行的值必须同类型：FIX_RATE 用秒数、CRON 用表达式） |
| 调度配置（Cron / 固定速度） | `schedule_conf` | **60**（FIX_RATE 下是**秒**） | **300**（FIX_RATE 下是**秒**） | 与本地兜底同频（60s / 300s）。**改频须同时改两处**：本地 `@Scheduled(fixedRate)` 与这里。等价 CRON **备选**（非主口径）：`0 * * * * ?` / `0 0/5 * * * ?`。60/300 的出处是本地代码实际值（`ReleaseTimeoutScheduler:74` / `SlaEscalationScheduler:87`），**真机 admin 里也是这两个值（2026-09-27 已读回）** |
| 运行模式 | `glue_type` | BEAN | BEAN | **必须 BEAN**：GLUE 模式不会走 `@XxlJob` 注解 |
| JobHandler | `executor_handler` | `releaseTimeoutScan` | `slaEscalationScan` | **唯一真源 = `@XxlJob` 注解值**（`ReleaseTimeoutScheduler.java:80` / `SlaEscalationScheduler.java:93`）。**大小写敏感**，写错的表现是触发时报 handler 不存在 |
| 任务参数 | `executor_param` | 留空 | 留空 | 两个 handler 都不读参数 |
| 路由策略 | `executor_route_strategy` | FIRST（**建议值，未回读**） | FIRST（**建议值，未回读**） | 全局扫描类任务，只需一个实例执行；未选中的实例仍有进程内兜底在跑。⚠ **本节唯一没有真机回读的行为类字段**，建任务时按实际填 |
| 子任务ID | `child_jobid` | 留空 | 留空 | |
| **阻塞处理策略** | `executor_block_strategy` | **SERIAL_EXECUTION（单机串行）** | **SERIAL_EXECUTION（单机串行）** | **2026-09-26 裁决**（方案 §5.6 已同步）：① 与本地 `@Scheduled` 的 `fixedRate` 语义一致——同一调度线程串行排队、不并发，两路日志与计数才可比；② 扫描幂等（乐观锁 / Redis SETNX），**宁可排队不丢轮**。注意：幂等**不靠**"不重叠"来保证，串行只是让重叠不污染排查 |
| **任务超时时间（秒）** | `executor_timeout` | **120** | **300** | **不要设 0**（永不超时会让卡死的一轮永远占住这个 handler）。取值理由：单轮上限 `BATCH_SIZE=200`，正常一轮秒级，120s 已是两个数量级余量；SLA 扫描每单多两次 Redis 操作，放宽到 300s |
| **失败重试次数** | `executor_fail_retry_count` | **0** | **0** | 扫描本身幂等，下一轮自然再来；重试只放大日志噪音 |
| **调度过期策略** | `misfire_strategy` | **DO_NOTHING** | **DO_NOTHING** | 扫描是状态驱动的（每次都重新查库），错过就错过、下一轮补 |

**四项配置在代码里没有落点，只能在 admin 建任务时显式设**——就是上表加粗的那四个：
阻塞处理策略、任务超时时间、失败重试次数、调度过期策略。
两个类的类注释（`ReleaseTimeoutScheduler` / `SlaEscalationScheduler`）里逐条写了取值理由与代价。

**实测状态（2026-09-27，服务器真机）**：下表这几项**已在 admin 里建好并逐项读回**，且点过执行、由 `xxl_job_log` 验过：
**handler 名**（两个）、**调度类型 FIX_RATE**、**调度配置 60 / 300**、**阻塞策略 SERIAL_EXECUTION**、
**任务超时 120 / 300**、**失败重试 0**、**调度过期 DO_NOTHING**。
判据：`SELECT ... FROM xxl_job_log ORDER BY id DESC LIMIT 2` 的 **`trigger_code=200` 且 `handle_code=200`，且 `handle_msg` 带业务摘要**
（`[release-scan] 触发来源=xxl 本轮释放 N 条（…）` / `[sla-scan] 触发来源=xxl 本轮通知 N 条（…）`）——**"执行成功"的绿灯不等于这个**，绿灯只说明触发被受理。
**仍未实测的**：**路由策略**（FIRST，仍是建议值）与几个纯展示字段（任务描述 / 负责人 / 报警邮件 / 子任务ID）。
⚠ 上述真机输出的**原文尚未入档 → 待贴**（复核命令见本节末）。

建完任务后的回读判据（`mysqlq` 定义见 §8）：

```bash
cd /opt/workorder/deploy
mysqlq() { docker compose exec -T mysql sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 "$@"' _ "$@"; }

mysqlq -N -B -e "SELECT job_desc, executor_handler, glue_type, executor_block_strategy,
                        executor_timeout, executor_fail_retry_count, misfire_strategy, trigger_status
                 FROM xxl_job.xxl_job_info
                 WHERE executor_handler IN ('releaseTimeoutScan','slaEscalationScan');"
#   期望：2 行，且加粗的四项与上表一致（SERIAL_EXECUTION / 120 与 300 / 0 / DO_NOTHING），trigger_status=1（运行中）
#   只出 1 行或 0 行 = 任务还没建完；handler 名写成连字符形式等变体 = 触发时报 handler 不存在（名字大小写与连字符都必须与注解逐字一致）
```

> **两路并行的判据**（建完任务、跑起来之后）：后端日志里应同时出现
> `[release-scan] 触发来源=local …` 与 `[release-scan] 触发来源=xxl …`（SLA 扫描同理，前缀 `[sla-scan]`）。
> 只有其中一种来源 = 另一条路没跑起来（要么 executor 开关没开，要么任务没建）。**这就是"来源标记"的用途。**

### 9.1 `job handler [x] not found` 的两成因（同一个报错，两种病根）

**这一条是本轮踩出来的**：同一个 `code:500, msg: job handler [xxx] not found`，成因和修法完全不同——

| 成因 | 判据（怎么分辨） | 修法 |
| --- | --- | --- |
| **代码没部署**（执行器里根本没有这个 handler） | 执行器**启动日志里没有** `xxl-job register jobhandler success, name:…` 那两行 | 重新部署后端（`docker compose up -d --build backend`），再查那两行 |
| **任务配置里 handler 名写错**（代码有、配置对不上） | 启动日志**有**两行 `register jobhandler success`；读回 `xxl_job_info.executor_handler` 与注解值**逐字对照**不一致（大小写、连字符、甚至**把 `releaseTimeoutScan` 填到了 SLA 那个任务上**） | 改 `executor_handler` 为注解值（唯一真源见上表 JobHandler 行） |

> **本轮两次都遇到了**：先踩"漏部署"（handler 没注册），补部署后又踩"配置错配"（把 `releaseTimeoutScan` 填到了 SLA 任务上）。
> 两者的报错文本**一模一样**，所以**顺序不能颠倒**：
> **① 先部署 → ② 查启动日志的两行 `register jobhandler success` → ③ 再在控制台建任务 → ④ 读回 `executor_handler` 逐字对照 → ⑤ 点一次执行看 `xxl_job_log`**。
> 跳过 ② 会让"漏部署"伪装成"配置写错"，跳过 ④ 会让"配置写错"伪装成"环境/网络问题"——两次都会白查一轮。

### 9.2 真机实测的复核命令（本轮的"待贴"项就靠这几条补）

```bash
cd /opt/workorder/deploy
mysqlq() { docker compose exec -T mysql sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 "$@"' _ "$@"; }

# ① 注册判据：执行器启动日志里必须有两行（没有 = 代码没部署，见 9.1）
docker compose logs backend | grep -E "register jobhandler success" 
#   期望：name:releaseTimeoutScan 与 name:slaEscalationScan 各一行

# ② 执行判据：点一次"执行一次"之后，看调度日志表（**不是**页面的绿灯）
mysqlq -N -B -e "SELECT id, job_id, trigger_code, trigger_msg, handle_code, handle_msg
                 FROM xxl_job.xxl_job_log ORDER BY id DESC LIMIT 4;"
#   期望：trigger_code=200 且 handle_code=200，handle_msg 带业务摘要，例如
#         [release-scan] 触发来源=xxl 本轮释放 N 条（候选 N 跳过 … 出错 … 缺配置 …）
#         [sla-scan]     触发来源=xxl 本轮通知 N 条（候选 N 跳过 … 失败 …）
#   ⚠ 页面的"执行成功"只说明触发被受理（见主表 JobHandler 行的说明）

# ③ 兜底判据：停掉 admin，本地兜底必须仍按 60s 出现（P2 可靠性净倒退是否被堵住）
docker compose stop xxl-job-admin
docker compose logs --since 3m backend | grep -E "触发来源=local" | tail -5
#   期望：至少 3~5 行 [release-scan] 触发来源=local，**相邻两行的间隔约 60s**
docker compose start xxl-job-admin
#   ⚠ SLA 扫描是 300s 一轮，取样窗口要 ≥6 分钟才看得到两行
```

> **⚠ 待贴**：上面 ①②③ 三段的**真机原始输出**（本轮的 `xxl_job_log` 行与两段日志）尚未入档。
> 它们不是"没验过"，而是"验过但凭证不在仓库里"——按本项目的规矩，这种状态**必须显式标注**，
> 不能写成"已留档"。
