# 企业工单流转平台 — 2C4G 服务器部署指南

> 架构：MySQL8 + Redis7 + **RabbitMQ 3.13（自建镜像，含延迟插件）** + Spring Boot(9000) + Nginx(80) = **5 容器**。
> **P1 步骤 3–4 起 RabbitMQ 已真实接入**，承载一条完整链路：接单/指派后写 outbox → 延迟交换机 → `workorder.order.release.queue` → **消费者 `OrderReleaseListener`**（手动 ACK）→ 工单 `RELEASED`。
> **开关**：投递任务与消费者共用 `OUTBOX_DISPATCH_ENABLED`（compose 已置 `true`）；**关闭时监听容器根本不创建**，队列会重新表现为"只增不减"。
> SLA 告警链路仍是 `@Scheduled` 占位，XXL-Job 将在 P2 接入（届时 6 容器，内存基线随之变化）。
> 内存口径：**4 容器**（无 MQ）服务器实测 **653–673 MiB**（`ASYNC-SCHEDULING-PLAN.md` §1.6.6）；**5 容器**本机预演实测 **≈881 MiB**（分项见第五节）。服务器批的 5 容器实测值待补。

---

## 一、目录结构（本部署产物所在）

```
work-order-system/
├── Dockerfile                    # 后端：maven 多阶段构建
├── deploy/
│   ├── docker-compose.yml        # 一键编排（核心）
│   ├── nginx.conf                # 前端反代：/api→backend:9000，SPA history 回退
│   ├── frontend.Dockerfile       # 前端：node 构建 dist → nginx
│   └── README.md                 # 本文档
└── sql/init.sql                  # 建表 + 种子数据（MySQL 首次启动自动导入）
```

前端源码已并入本仓库的 `frontend/` 目录（compose 用 `context: ../frontend` 构建前端镜像），**不再需要仓库外的兄弟目录**。

---

## 二、服务器前置

```bash
# 1. 确认 Docker 已装
docker --version && docker compose version

# 2. 目录布局（推荐统一放 /opt）
#    /opt/workorder                  ← 单仓：后端 + deploy/ + frontend/
#    （前端源码在同一仓库的 frontend/ 下，P0b 起不再需要独立的 /opt/work-order-frontend）
```

> 若无 Docker：装 `docker-ce` + `docker-compose-plugin`（见文末"无 Docker 备选"）。

---

## 三、构建 & 启动

```bash
cd /opt/workorder/deploy

# 0. 环境变量（缺失即报错，不再有默认口令）
cp ../.env.example .env && vi .env          # 必须放在本目录（compose 按 compose 文件目录找 .env；放仓库根目录读不到）
#    必填：MYSQL_ROOT_PASSWORD、RABBITMQ_PASS（RABBITMQ_USER 默认 workorder）
#    注意：RABBITMQ_HOST/PORT **留空**——容器内要用服务名 rabbitmq:5672，写 localhost 会连不上

# 1. 启动全部服务（后台，含镜像构建）
#    会构建 3 个镜像：backend（Maven 多阶段）、frontend（Node 构建 dist）、
#    rabbitmq（官方镜像 + 延迟插件；插件包已随仓库入库，构建期不联网下载）
docker compose up -d --build

# 2. 看启动日志（等 backend 起来约 10-20s）
docker compose logs -f backend
#    看到 "Started WorkOrderApplication" 即成功
#    P1 起还应看到两行与异步链路有关的信息：
#      [outbox] 投递任务已启用：exchange=workorder.delay.exchange ...
#      [outbox] 延迟交换机校验通过：workorder.delay.exchange 类型=x-delayed-message
#    若第二行变成 ERROR，说明 rabbitmq 用的不是本仓库的自建镜像（官方镜像不含延迟插件）

# 3. 健康检查
docker compose ps

# 4. 【固定动作】禁用 swap 的自检：逐个服务核对 mem_limit 与 memswap_limit 是否相等
docker compose config | grep -E 'mem_limit|memswap_limit'
#    两组值必须一一相等（依据：CLAUDE.md §3 第 12 条；不相等就等于给容器开了 swap 配额）

# 5. 【固定动作】延迟交换机是否真的生效（延迟消息怎么观察见 deploy/rabbitmq/README.md）
docker exec workorder-rabbitmq rabbitmqctl list_exchanges name type | grep workorder
#    期望：workorder.delay.exchange   x-delayed-message
```

---

### 升级已有部署（老库没有 `t_event_outbox` 时）

> 完整清单（拉代码 → 迁移 → 补 `.env` → 重建 5 容器 → 三个验证 → 5 容器采样 → 清理压测数据）见
> **[UPGRADE-P1.md](UPGRADE-P1.md)**，可逐条粘贴执行。

**升级脚本共 6 条**（`UPGRADE-P1.md` §3 有完整清单与逐条判据），**顺序不能反、一条都不能漏**：

```bash
cd /opt/workorder/deploy
# 【口令只在容器内读】不要 set -a; . ./.env —— 那会把口令导出进宿主 shell，而 shell 环境变量优先级**高于** .env：
#   此后改 .env 不生效、且完全不报错（本轮 broker 反复崩溃的根因）。详见 UPGRADE-P1.md 文首约定②。
mysqlq() { docker compose exec -T mysql sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 "$@"' _ "$@"; }
mysqlq work_order < ../sql/hotfix-p1-outbox-init.sql        # ① 老库没有 t_event_outbox → 建表
mysqlq work_order < ../sql/hotfix-outbox-sending-state.sql  # ② 补 owner/claimed_at 与索引（幂等）
mysqlq work_order < ../sql/hotfix-p4-consume-record.sql     # ③ 消费端幂等表
mysqlq work_order < ../sql/hotfix-p4-message-retry.sql      # ④ 消费失败重试账本
mysqlq work_order < ../sql/hotfix-p5-triage-status.sql      # ⑤ t_work_order.triage_status
mysqlq work_order < ../sql/hotfix-p5-submit-notification.sql # ⑥ t_notification.event_id + 唯一键
```

- `hotfix-p1-outbox-init.sql`：**老库没有这张表时**用它建（`CREATE TABLE IF NOT EXISTS`，已存在则不动）。
- `hotfix-outbox-sending-state.sql`：把"步骤 2 形态"的表补齐 `owner/claimed_at` 与正确索引；对刚建好的表会打印"已应用，跳过"。
- `hotfix-p4-consume-record.sql` / `hotfix-p4-message-retry.sql` / `hotfix-p5-triage-status.sql`：P4 步骤 1/2 与 P5 步骤 1 新增的两张表与一列，
  都是幂等写法。**必须在重启后端之前跑完**——表和列不存在时，消费者会 INSERT 失败（消息被"ACK + 日志"吃掉）、
  提交工单会直接报 `Unknown column 'triage_status'`。
- `hotfix-p5-submit-notification.sql`：P5 步骤 2 给 `t_notification` 加 `event_id` 与 `UNIQUE(event_id, user_id)`
  （提交通知按角色群发时的第二道幂等防线）。**同样必须在重启后端之前跑**：通知实体新增了该字段，
  列不存在时**所有**站内信写入都会报 `Unknown column`——包括 SLA 超时告警这条老链路。
- **已实测**：`0988ad6` 建的库、`c926472` 建的库，各自跑完这两个脚本后，与"用当前 `init.sql` 全新建库"的
  **列（含注释文本）与索引逐项一致**（71 列；见 `docs/DECISIONS.md` D51）。
- 服务器那份种子数据来自 `0988ad6`，与当前 `init.sql` 的 30 条 INSERT 一致 → **不需要**再跑 `hotfix-p0b-order-type.sql`；
  只有当探针 P5/P11 显示旧类型（`REPAIR/LEAVE/REIMBURSE`）时才补跑（脚本幂等，可安全重跑）。

---

## 四、验证

### 1. 后端 API（本机自测）
```bash
# 登录 admin（种子密码 admin123）
curl -X POST http://localhost:9000/api/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'
# → 应返回 code:200 + token
```

### 2. 前端页面
浏览器访问 `http://<服务器IP>` → 应看到登录页。

### 3. 端到端冒烟（可选，验证整链路）
- admin 登录 → 管理后台建用户/配 SLA
- submitter 登录 → 提交工单 → handler 抢单处理 → submitter 验收
- 造 3 次驳回触发升级 → admin 收到站内信（右上角铃铛角标 +1）

---

## 五、内存监控（2C4G 基线）

> **口径分三种，别混用**（详见 `ASYNC-SCHEDULING-PLAN.md` §1.6.1/§1.6.2）：
> ① **服务器 4 容器实测**（无 MQ，2C4G 真机）→ 这是余量的权威数据；② **本机 5 容器实测**（下表，含 MQ，WSL2）
> → 只用来看"加入 RabbitMQ 后的增量"；③ **按参数上界推算**（§1.6.2）→ 容量规划用它，不用轻载实测值。

**本机 5 容器实测（2026-09-24，目标上界参数：backend `-Xmx512m`、buffer pool 256M、rabbitmq mem_limit 512m）：**

| 组件 | 实测 RSS | 容器上限 | 说明 |
|---|---|---|---|
| mysql 8.0 | **461.2 MiB** | 1g | buffer_pool 256M |
| rabbitmq（自建+延迟插件） | **136.8 MiB** | 512m | 空载、无积压；含 management 与插件 |
| backend（JVM `-Xmx512m`） | **261.5 MiB** | 1g | 刚启动的稳态 |
| frontend（nginx） | 16.3 MiB | 64m | 静态资源 |
| redis 7 | 5.2 MiB | 256m | 仅会话/幂等键 |
| **合计** | **≈881 MiB** | — | 与 4 容器服务器基线 653 MiB 的差值是 **+228 MiB** |

**服务器 4 容器实测（`ASYNC-SCHEDULING-PLAN.md` §1.6.6）**：稳态 653 MiB / 8 小时后 673 MiB，
`MemAvailable` 2.29 GiB；系统底噪 ≈400 MiB（含云厂商 agent 115 MiB）。
**5 容器的服务器实测值待补**——本机 WSL2 的数字不能替代真机（底噪测不准）。

**注意**：本表的 RSS 是**空载稳态**，随负载增长会向 `-Xmx` 上限靠拢；**容量规划必须用 §1.6.2 的上界口径**。

```bash
# 服务器上实时看
docker stats --no-stream
free -m
```

**若内存告急（free -m 偏低）：**
```bash
# 临时看谁吃内存
docker stats --no-stream
# 堆与 buffer pool 的**基线值已定稿**并写在 deploy/docker-compose.yml 里：
#   backend JAVA_OPTS: -Xmx512m -Xms256m -XX:MaxMetaspaceSize=192m -XX:MaxDirectMemorySize=64m
#   mysql: --innodb-buffer-pool-size=256M
# 服务器上部署后用 free -m 与 docker stats 复核；若余量显著低于 1 GiB，再回收（下调堆或 buffer pool），
# 并把改动同步回 ASYNC-SCHEDULING-PLAN.md §1.4（不要只改 compose 不改方案）。
# 调大/调小后：docker compose up -d 重建对应服务
```

---

## 六、延迟消息怎么观察（P1 步骤 3 起）

**核心事实**：延迟消息在到点前**不在队列里**，而在交换机内部。因此延迟窗口内 `list_queues` 显示 0 属正常，不是丢失。

| 想看什么 | 命令 | 期望 |
| --- | --- | --- |
| 消息是否已到点进队 | `docker exec workorder-rabbitmq rabbitmqctl -q list_queues name messages` | 到点后目标队列由 0 变正数 |
| 消息是否被消费者取走 | 同上，连着看两次 | **开开关**时队列深度回到 **0**（消费者立刻取走并处理）；**关开关**时会一直堆 |
| 手动 ACK 是否真的生效 | `curl -u <user>:<pw> http://127.0.0.1:15672/api/consumers` | `ack_required: true`（broker 侧判据，比读代码可靠） |
| 交换机类型对不对 | `docker exec workorder-rabbitmq rabbitmqctl list_exchanges name type \| grep workorder` | `workorder.delay.exchange  x-delayed-message` |
| 还在交换机里等几条 | `docker exec workorder-rabbitmq rabbitmqctl eval 'lists:map(fun(T)-> {T, ets:info(T, size)} end, lists:filter(fun(T)-> is_atom(T) andalso string:find(atom_to_list(T), "delayed") =/= nomatch end, ets:all())).'` | `rabbit_delayed_message...` 的 size = 等待中的条数（**插件内部实现，仅供排查，不作监控指标**） |
| 业务侧"该何时投递" | `SELECT event_id,status,deliver_at,retry_count,next_retry_at FROM t_event_outbox ORDER BY id DESC LIMIT 10;` | `deliver_at` 是唯一真相来源 |

> **不要用 returns 回调判定延迟消息是否丢失**：延迟插件对**每条**延迟消息都会返回 `NO_ROUTE`（管理台 API 同样是 `routed=false`），
> 但消息照常到点入队（实测 t+30s 队列 0 → t+62s 队列 1）。因此 `spring.rabbitmq.template.mandatory` 显式设为 `false`，
> 否则会给每条消息刷一条假 ERROR。详见 `INVARIANTS.md` I11 的两条实测易错点。

---

## 七、生产加固清单（上线前必做）

| 项 | 操作 |
|---|---|
| **改数据库密码** | compose 里 `MYSQL_ROOT_PASSWORD` 换强密码（当前默认 WorkOrder@2026） |
| **改种子 admin 密码** | 首次登录后，或用 SQL 改 `t_user` 的 password（BCrypt） |
| **删/藏演示账号** | `submitter/handler/dept_admin` 是我演示造的，生产可删（`DELETE FROM t_user WHERE id>1`） |
| **DB 只内网暴露** | compose `ports` 的 `3306:3306` 建议删掉或只绑 `127.0.0.1`，避免公网直连 MySQL |
| **MQ 管理台只在内网** | compose 已把 5672/15672 绑到 `127.0.0.1`；需要看管理台时用 SSH 隧道，**绝不要**改成 `0.0.0.0` |
| **MQ 口令** | `RABBITMQ_PASS` 无默认值（缺失 compose 直接报错）；**不要用 guest**——guest 只允许 loopback 登录，容器间连不通 |
| **防火墙** | 只放行 80（HTTP）；9000/3306/6379 不对公网开 |
| **时区** | 已全部设 `TZ=Asia/Shanghai`（MySQL/容器/JVM），SLA 超时判定依赖它 |
| **日志** | `docker compose logs` 落盘或接外部，勿长期堆容器内 |

---

## 八、常见问题（FAQ）

**Q: 前端页面 502 / API 连不上？**
A: 后端容器没起来或没健康。`docker compose logs backend` 看是否 "Started"；`curl localhost:9000/api/login` 自测后端。

**Q: 登录报"Access denied for user 'root'@'...'"？**
A: MySQL 首次初始化密码与 compose 不一致。若改过 `MYSQL_ROOT_PASSWORD`，需删卷重建：`docker compose down -v && docker compose up -d`（会清空业务数据，仅首次）。

**Q: init.sql 没自动导入 / 中文乱码？**
A: 确保 `sql/init.sql` 为 **UTF-8 无 BOM**（Windows 记事本另存为可能带 BOM 导致首行报错）。也可手动导入：
```bash
docker exec -i workorder-mysql mysql -uroot -pWorkOrder@2026 --default-character-set=utf8mb4 work_order < ../sql/init.sql
```

**Q: 重启后数据还在吗？**
A: 在。mysql/redis 数据在命名卷 `mysql-data`/`redis-data`，`docker compose down`（不带 -v）不丢。

**Q: 2C4G 够不够？**
A: **真机口径**（腾讯云轻量 4 vCPU / 4 GiB）：4 容器实测 653–673 MiB + 系统底噪 ≈400 MiB，`MemAvailable` 约 2.29 GiB → **余量 > 2 GiB**（见 `ASYNC-SCHEDULING-PLAN.md` §1.6.6）。加入 RabbitMQ 后本机预演 5 容器 ≈881 MiB（不含底噪），增量约 +228 MiB。**容量规划请用 §1.6.2 的上界口径（≈2.7 GiB、余量 ≈1 GiB）**，不要用轻载实测值反推。

**Q: 队列里一直有消息，是不是消费出问题了？**
A: 先确认开关：**P1 步骤 4 起消费端已存在**（`OrderReleaseListener`），但**只有 `OUTBOX_DISPATCH_ENABLED=true` 才会创建监听容器**——开关关闭时队列会一直堆（这是"没开消费者"，不是投递失败）。开着开关仍堆积时按顺序看三处：① `t_event_outbox.status`（`SENT` = broker 已 ack）；② `GET /api/consumers` 的 `ack_required`（应为 `true`）；③ 后端日志里的 `[release-listener]` 行（`RELEASED`→INFO / `SKIPPED`→DEBUG / 出错→ERROR 但本轮也 ACK）。

**Q: 抢单之后队列/管理台里看不到刚才那条消息？**
A: 这是延迟消息的**设计行为**——消息在到达 `deliver_at` 之前待在**延迟交换机内部**，不在任何队列里，所以 `list_queues` 是 0。观察方式见第六节与 `deploy/rabbitmq/README.md`。

---

## 九、面试口径（部署相关）

> 【待重写】本段原为"未引入 MQ"的口径（论证"2C2G 上不硬塞消息队列是工程取舍"），
> 与 P1 之后的架构方向相反，已废弃并删除。
> 新版口径必须在 P1–P5 落地后重写，且必须基于已实现的事实——现在写会描述尚不存在的能力，
> 又是一次"文档说做了、实际没做"的漂移。

---

## 十、无 Docker 备选（宿主机裸部署）

若服务器装不了 Docker，按此手动：
```bash
# 1. 装依赖
apt install -y mysql-server redis-server nginx openjdk-17-jre-headless

# 2. MySQL：建库 + 导 init.sql（注意 utf8mb4）
mysql -uroot -p --default-character-set=utf8mb4 -e "CREATE DATABASE work_order CHARACTER SET utf8mb4;"
mysql -uroot -p --default-character-set=utf8mb4 work_order < sql/init.sql

# 3. Redis 默认跑 6379

# 4. 后端：mvn package 后跑 jar
mvn clean package -DskipTests
MYSQL_PASSWORD=<pwd> java -Xmx256m -jar target/work-order-system-*.jar   # 环境变量覆盖见 application.yml

# 5. Nginx：把 deploy/nginx.conf 的 proxy_pass backend:9000 改成 localhost:9000，serve 前端 dist
```

---

## 十一、演示动线建议（面试现场 3 分钟）

1. 浏览器开登录页 → admin 登录 → **管理后台建角色/看统计**（展示 RBAC）
2. 退出 → submitter 登录 → 提交一张工单
3. handler 登录 → 工单大厅**抢单** → 开始处理 → 提交验收
4. submitter 验收通过 → 工单关闭，**操作日志时间线 5 条**
5. （加分）再造 3 次驳回 → 触发升级 → 切 admin 看**站内信角标 +1**
