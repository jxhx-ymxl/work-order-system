# 企业工单流转平台 — 2C2G 服务器部署指南

> 架构：**瘦身版** MySQL8 + Redis7 + Spring Boot + Nginx。**不含 RabbitMQ / XXL-Job**（代码真实未接入，是 Mock + `@Scheduled` 兜底，部署无需这俩中间件）。内存实测约 1.3–1.5G，2G 服务器从容。

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

同级还需前端源码目录 `work-order-frontend/`（compose 用它 build 前端镜像）。

---

## 二、服务器前置

```bash
# 1. 确认 Docker 已装
docker --version && docker compose version

# 2. 目录布局（推荐统一放 /opt）
#    /opt/work-order-system          ← 后端仓库（含 deploy/）
#    /opt/work-order-frontend        ← 前端仓库（同级）
```

> 若无 Docker：装 `docker-ce` + `docker-compose-plugin`（见文末"无 Docker 备选"）。

---

## 三、构建 & 启动

```bash
cd /opt/work-order-system/deploy

# 1. 先本地/或服务器上构建前端产物（若走 compose 多阶段 build 可跳过，见下）
#    前端镜像由 compose 自动 build（context=../work-order-frontend），无需手动 npm

# 2. 启动全部服务（后台）
docker compose up -d --build

# 3. 看启动日志（等 backend 起来约 10-20s）
docker compose logs -f backend
#    看到 "Started WorkOrderApplication" 即成功

# 4. 健康检查
docker compose ps
```

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

## 五、内存监控（2G 关键）

**实测数据（2026-09，隔离容器 + 同款 JVM 参数）：**

| 组件 | 实测占用 | 说明 |
|---|---|---|
| mysql 8.0 | **416 MiB** | buffer_pool 已压 128M |
| redis 7 | **~5 MiB** | 近可忽略 |
| backend (JVM -Xmx256m) | **~280 MiB** | jar 直接跑实测 |
| frontend (nginx) | ~25-35 MiB | 估算（标准 nginx 静态） |
| 系统底噪 | ~350-450 MiB | Docker daemon/sshd/内核 |

**合计 ~1.1-1.2 GiB → 2G 服务器余量近 1 GiB，从容。**

```bash
# 服务器上实时看
docker stats --no-stream
free -m
```

**若内存告急（free -m 偏低）：**
```bash
# 临时看谁吃内存
docker stats
# 永久调小：改 deploy/docker-compose.yml 的
#   mysql:   --innodb-buffer-pool-size=96M   (416MiB → ~340MiB)
#   backend: Dockerfile JAVA_OPTS 的 -Xmx256m → -Xmx192m
# 然后 docker compose up -d 重建
```

---

## 六、生产加固清单（上线前必做）

| 项 | 操作 |
|---|---|
| **改数据库密码** | compose 里 `MYSQL_ROOT_PASSWORD` 换强密码（当前默认 WorkOrder@2026） |
| **改种子 admin 密码** | 首次登录后，或用 SQL 改 `t_user` 的 password（BCrypt） |
| **删/藏演示账号** | `submitter/handler/dept_admin` 是我演示造的，生产可删（`DELETE FROM t_user WHERE id>1`） |
| **DB 只内网暴露** | compose `ports` 的 `3306:3306` 建议删掉或只绑 `127.0.0.1`，避免公网直连 MySQL |
| **防火墙** | 只放行 80（HTTP）；9000/3306/6379 不对公网开 |
| **时区** | 已全部设 `TZ=Asia/Shanghai`（MySQL/容器/JVM），SLA 超时判定依赖它 |
| **日志** | `docker compose logs` 落盘或接外部，勿长期堆容器内 |

---

## 七、常见问题（FAQ）

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

**Q: 2G 会不会 OOM？**
A: 瘦身版实测 1.3-1.5G 不会。若同时跑别的大程序，按第五节调小 MySQL/JVM。

---

## 八、面试口径（部署相关，讲真实）

> "我把它部署在一台 **2C2G 云服务器**上，用 Docker Compose 编排。**没有硬塞消息队列**——因为项目里 MQ 是接口隔离的 Mock 实现，超时释放和 SLA 升级走 `@Scheduled` 定时扫表 + Redis 标记兜底，**单库 + 乐观锁 + Redis 已经覆盖并发和时效需求**。2G 上跑 MySQL(压 buffer_pool)+Redis+Spring Boot(-Xmx256m)+Nginx 大约 1.3G，有余量。我评估过：为简历上'有 RabbitMQ'而硬上中间件导致 OOM，是得不偿失的工程决策。"

> 若被追问"为什么没真上 RabbitMQ"："代码已按 **MessagePublishService 接口**解耦，将来要接真实 MQ，替换实现类即可、业务零改动。本地演示用 Mock 打日志 + 定时器兜底跑通全流程，这是**有意的工程取舍**：不为一个当前不需要的中间件付出部署和运维成本。"

---

## 九、无 Docker 备选（宿主机裸部署）

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

## 十、演示动线建议（面试现场 3 分钟）

1. 浏览器开登录页 → admin 登录 → **管理后台建角色/看统计**（展示 RBAC）
2. 退出 → submitter 登录 → 提交一张工单
3. handler 登录 → 工单大厅**抢单** → 开始处理 → 提交验收
4. submitter 验收通过 → 工单关闭，**操作日志时间线 5 条**
5. （加分）再造 3 次驳回 → 触发升级 → 切 admin 看**站内信角标 +1**
