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
# 管理台与执行器之间的通信口令。本轮只起管理台，允许留空；
# 接执行器时（P2 后续步骤）必须设置，且与后端 xxl.job.access-token 同值。
XXL_JOB_ACCESS_TOKEN=
EOF
grep '^XXL_JOB_ACCESS_TOKEN=' .env | sed 's/=.*/=***/'
```

## 3. 起服务（只起这一个，不动其它 5 个）

```bash
cd /opt/workorder/deploy
docker compose up -d xxl-job-admin
docker compose ps                     # 判据：6 个服务在列，xxl-job-admin 为 Up/healthy
docker compose logs --tail 20 xxl-job-admin | grep -E 'Started XxlJobAdminApplication|Tomcat started'
#   期望：Tomcat started on port(s): 8080 (http) with context path '/xxl-job-admin'
#         Started XxlJobAdminApplication in x.xx seconds
```

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
# 本机实测参考值：**xxl-job-admin ≈ 358.8 MiB**（`JAVA_OPTS=-Xmx256m`，含 metaspace/线程栈）
#   —— 所以 compose 里显式设了 heap 上限：镜像默认不设 -Xmx，4G 机器上 JVM 默认上限约 1G，会撞穿 mem_limit=512m 被 OOMKilled。
# 6 容器整栈的真机基线请贴回项目对话，由文档侧写进 ASYNC-SCHEDULING-PLAN.md §1.6（届时 4/5/6 三种形态并列）。
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
