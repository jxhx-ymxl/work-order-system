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
