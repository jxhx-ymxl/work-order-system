# 企业工单流转平台（高校后勤 / IT 报修）

> 项目定位：**临江理工大学东湖校区后勤与 IT 报修平台**——把"电话 + 微信群 + Excel 台账"的报修流程，替换为有状态、有时限、可追溯的工单闭环。
> 一句话架构：**Spring Boot 3 单体后端 + Vue3 前端 + MySQL/Redis + RabbitMQ**（5 容器，Docker Compose 跑在 2C4G 单机）；异步与调度由 RabbitMQ + XXL-Job 承载——**RabbitMQ 已于 P1 步骤 3 真实接入**（当前只承载"接单后到点释放检查"一条链路，消费端在步骤 4），XXL-Job 仍是 `@Scheduled` 占位（P2 接入）。改造过程见 `ASYNC-SCHEDULING-PLAN.md`。
> 文档权威顺序（引自 `CLAUDE.md`）：`BUSINESS-SCOPE.md`（业务基线）→ `ASYNC-SCHEDULING-PLAN.md`（技术方案）→ `CONTEXT.md`（术语）→ `TECHNICAL-PLAN.md`（原始设计）→ 代码；冲突时以「最近一次明确决策」为准并立即上报。

---

## 一、技术栈

**已定栈（允许清单，不是强制清单）**：Java 17、Spring Boot 3.3.x、MyBatis-Plus 3.5.x、MySQL 8.0、Redis 7、RabbitMQ 3.13、XXL-Job 2.4.x、Sa-Token、Knife4j。

**本阶段明确不引入**：Kafka、RocketMQ、PowerJob、MongoDB、Elasticsearch、Seata、Nacos 或任何配置中心、服务网格。

> 注意：上面是"可用能力清单"，**不是必须接入的东西**。RabbitMQ 只用于事件驱动与延迟触发，XXL-Job 只用于批作业与兜底扫描；**禁止为了"用上中间件"而在抢单、状态流转等强一致路径引入它们**（`CLAUDE.md` §2）。

---

## 二、本地启动

**方式一：Docker Compose（推荐，完整栈）**

详细步骤见 **[deploy/README.md](deploy/README.md)**（含 2C2G/2C4G 内存参数、构建方式、无 Docker 备选方案）。

```bash
cd deploy
docker compose up -d --build          # 默认端口：前端 80 / 后端 9000 / MySQL 3306 / Redis 6379
```

若本机已有 MySQL/Redis 占用默认端口，用本机隔离 override（换端口与容器名、独立数据卷）：

```bash
cd deploy
docker compose -f docker-compose.yml -f docker-compose.local.yml up -d --build
# 端口：前端 18080 / 后端 19000 / MySQL 13306 / Redis 16379
```

**方式二：只起依赖，后端本地跑**

```bash
# 1) 起 MySQL + Redis（可只依赖 compose 中的这两个服务）
# 2) 初始化库表与种子数据
mysql -h 127.0.0.1 -P 3306 -u root -p < sql/init.sql
# 3) 启动后端（默认读 application.yml，可用 MYSQL_HOST/REDIS_HOST 等环境变量覆盖）
mvn clean compile && mvn spring-boot:run
```

`sql/init.sql` 使用 `CREATE TABLE` 直接建表（非幂等），重复执行会因表已存在而失败；重建请先清库或使用带 `IF NOT EXISTS` 的变体。已有数据库补权限绑定用 `sql/hotfix-role-permissions.sql`。

---

### 为什么禁用容器 swap（`memswap_limit` = `mem_limit`）

`deploy/docker-compose.yml` 给 4 个服务（mysql / redis / backend / frontend）都写了
`memswap_limit`，值**等于各自的 `mem_limit`**。Compose 里 `memswap_limit` 表示"内存 + swap 的总上限"，
因此等于 `mem_limit` 就是**不给 swap 配额**。

**为什么显式禁用**：

1. **Docker 的默认行为是"隐式允许等量 swap"**——不设 `memswap_limit` 时，容器可用与 `mem_limit` 等量的 swap。
   结果是内存超限时容器**不会立刻 OOM，而是悄悄换出**。
2. **换出的代价比被杀更高**：JVM 与 MySQL 的工作集被换出后，一次页错误恢复要几毫秒到几十毫秒，
   表现为偶发长尾（而 `docker stats` 看不到原因）。
3. **它会污染泄漏观察**：观察 RSS 时若内存悄悄进了 swap，会出现"**RSS 不涨但 swap 在涨**"，
   把两种信号混在一起，泄漏判定协议（`ASYNC-SCHEDULING-PLAN.md` §1.6.4）直接失效。
4. **超限行为保持可判定**：禁用后超限就是 `OOMKilled`，问题立刻暴露，而不是变成慢速抖动。

**宿主机的 swap 保留给非容器进程**：宿主 swap 仍有价值（内核、sshd、构建期瞬时峰值等），
所以我们只从**容器**这一侧收回 swap 配额，不要求在宿主机上关掉 swap。

## 三、演示入口

| 入口 | 地址（默认部署） | 地址（local override） |
| --- | --- | --- |
| 前端 Web | http://localhost/ | http://localhost:18080/ |
| 后端 API | http://localhost:9000/api/ | http://localhost:19000/api/ |
| 接口文档（Knife4j） | http://localhost:9000/doc.html | http://localhost:19000/doc.html |

**演示账号**：超管 `admin`（其余角色需由超管在"用户管理"中创建并分配角色）。

**初始密码：`admin123`**（用户名 `admin`）。

该明文与 `sql/init.sql` 中唯一那条 `INSERT INTO t_user` 的 BCrypt 哈希一一对应，已用 BCrypt 校验通过。

**需要留痕的反转**：收口 5 第一次处理时，交付报告写的是"已删除覆盖用 UPDATE"，但**该 UPDATE 实际仍然留在文件末尾**——文件里当时有两段 `UPDATE t_user SET password`，只删掉了前面那段。造成报告与文件不符。第二轮才真正删除尾部那段，现在 `t_user.password` 只有一个写入点（可用 `Select-String -Pattern 'INSERT INTO t_user'` 复核，注意 `INSERT INTO t_user_role` 是同名前缀的另一张表）。这件事直接催生了 `CLAUDE.md` §6 新增的第 5 项「回读校验」。

部署冒烟探针 **P12** 就是"清库导入 `sql/init.sql` 后用该账号密码登录一次"，判断标准是**必须能登录**。

**演示脚本**：见 `BUSINESS-SCOPE.md` §6.1 业务动线（5 分钟，含 AI 异步分类的完整可观测时序）与 §6.2 技术动线（3 分钟，故障注入；需 P1–P5 完成后才可演示）。

---

## 四、已知缺口清单

这一节是 `BUSINESS-SCOPE.md` R6 与 R7 要求的"已知缺口标注"落点。**每条都必须带代价，不允许只写"不做"。**

### P1 进展与后续（2026-09-24）

**已完成（P1 步骤 1–5）**：事件模型与 outbox 表 → 事务内写路径 → 真实 RabbitMQ 投递（自建延迟插件镜像，5 容器）
→ 消费端三态 + 手动 ACK 契约 → **释放时限收敛到 `t_sla_config.accept_minutes`**（兜底扫描与 MQ 路径同源，修 G5/I8）。
真机已验：到点进队并被消费（工单转 `RELEASED`、队列回 0）、停 broker 不误标 SENT、broker 恢复后补投、
延迟消息扛 SIGKILL、后端强杀后 outbox 补投。

**升级已有部署**：老库（`0988ad6` 时期建的）只差 `t_event_outbox` 一张表 —— 跑 `sql/hotfix-p1-outbox-init.sql`
再跑 `sql/hotfix-outbox-sending-state.sql` 即可；顺序与判据见 **[deploy/UPGRADE-P1.md](deploy/UPGRADE-P1.md)**。

**仍待做（按执行顺序）**：

| 顺序 | 阶段 | 内容 |
| --- | --- | --- |
| 1 | **P4 幂等 / 死信 / 退避** | **已完成**：① `t_consume_record` 消费去重表（步骤 1，D52，与业务写同事务）；② `t_message_retry` 重试账本 + 阶梯 **1m/5m/15m/1h/6h** + 超 5 次 `PARKED`（步骤 2，D53，**在业务事务之外**写）。**仍待做**：DLX + 停车队列（步骤 3，届时消费者失败改成 NACK，不再靠账本重投）、扫描 SQL 直接排除已通知工单（替掉 SLA 调度器里的 Redis 粗粒度去重）、通知表回填 `ref_type/ref_id`（当前 82/82 全 NULL，第二道防线建不起来） |
| 2 | **P2 xxl-job** | 接入调度中心，迁移两个 `@Scheduled`（兜底释放扫描、SLA 扫描），保留进程内 `@Scheduled` 作为并行兜底；容器数 5 → 6，内存基线随之更新 |
| 3 | **P5 triage 异步化 + 提交通知** | **全部完成**（2026-09-25）。① 提交不再同步等 LLM（`ORDER_TRIAGE` 事件 + 消费端写回，D55）；② 提交通知异步化（`ORDER_SUBMITTED` + 角色群发 + `UNIQUE(event_id,user_id)`，D59）；③ 前端放开 `type`/`priority` 必填 + 三态（分类中/已分类/分类失败）显示。**该步顺带修掉三个缺陷**：`WorkOrderVO`/控制器两处 `toVO` 都要带 `triage_status`、账本转 PARKED 时同事务把工单置 FAILED、以及"LLM 失败被洗成一次成功"（`triage()` 改为抛 `TriageUnavailableException`）——见 D61/D62 与 §9.2 |

### 已知缺口逐条状态

| 编号 | 缺口 | 现状 | 代价（谁受影响、影响成什么样） |
| --- | --- | --- | --- |
| **N1** | 工单附件 / 照片上传 | **不做（R6 已裁决）** | 报修人**仍需在微信群里补图**；P4 的"分类靠人读"只能依赖文字描述，AI 分类对"只有照片才说得清"的故障（如墙面渗水、设备烧毁）判断力下降。做它的代价是对象存储、上传鉴权、内容类型校验、孤儿文件清理与备份体积翻倍，而目标机器只有一块 2C4G 的盘 |
| **N2** | 短信 / 邮件通道 | **不做（R7 已裁决收窄）** | 只在"外部渠道群机器人 webhook（F5-6）"这一条通道上满足非登录触达；短信与邮件不做，代价是**夜间与现场作业时，没有手机短信兜底**，若群机器人凭据失效则退化为只能登录看站内信 |
| **N2-补充** | 外部渠道 webhook（F5-6） | **尚未实现（C 类，排在 P5 之后）** | 当前非登录触达能力为**零**：师傅在现场作业时不登录系统就看不到任何超时告警（站内信只在登录后可见）。实现后若无可用凭据，必须在本文件把该通道标注为**「已实现未启用」**，不得留下半成品 |
| **G1–G5** | 五处已核实的代码缺口（`RELEASED` 死路、日志越权、三个权限码不生效、接管权限层与服务层不一致、SLA 接单时限界面在骗人） | 详见 `BUSINESS-SCOPE.md` §1.6 与 `INVARIANTS.md` | 每条缺口都有承接功能编号（F1-6 / F2-4 / F3-5 / F4-1 / F5-2）与验收标准，未修复前不要对外宣称这些能力已具备 |
| **I4 / I5** | `sla_deadline` 为 NULL 的静默失效（NULL 与任何值比较均为 unknown，永不进入 SLA 扫描） | 已定稿修复方案（a-2 兜底配置 + type 枚举校验 + 启动自检 `ensureSlaConfigComplete()`），实施在 P0 | 未修复前，漏配的 `type+priority` 组合会让该类工单**永远不告警且无任何报错**。2026-09-23 实测：未完结 NULL 工单 104 行，全部为测试残留（详见 `INVARIANTS.md` §2(c)） |
| **N3** | **outbox → MQ 的消费端** | **已实现（P1 步骤 4）**：`OrderReleaseListener` 监听 `workorder.order.release.queue`，手动 ACK，按 `ReleaseResult` 三态决定 ACK（RELEASED→INFO / SKIPPED→DEBUG / ERROR→ERROR 日志后**也 ACK**，P4 改 NACK）。**注意开关**：与投递任务共用 `workorder.outbox.dispatch.enabled`，**默认关闭**——关闭时监听容器根本不创建，队列会重新表现为"只增不减" | ① 演示/部署时必须确认开关是 `true`，否则点④（错误）与点⑤（重试）都不存在，队列会一直堆；② 本轮**没有去重表、没有死信、没有重试**：幂等靠状态守卫（`WHERE status='ACCEPTED'`），投递失败的消息本轮不会被自动重试，只能靠日志与探针发现（P4 补）；③ `ReleaseTimeoutScheduler` 的 30 分钟仍是硬编码（F4-1 未做），所以 `accept_minutes≠30` 的类型在 MQ 与兜底两条路径上的时限**仍不一致** |

---

## 五、本地与服务器部署的环境变量清单

样例文件见 [`.env.example`](.env.example)（只含键名与占位说明，**不含真实值**）。`.env` 已被 `.gitignore` 覆盖（`.gitignore:23-24`，实测 `git check-ignore -v .env` 命中），**真实值只写在本地或服务器的 `.env` 里，永不入库**。

> **`.env` 放哪里（实测踩过）**：放 **`deploy/.env`**（与 `deploy/docker-compose.yml` 同级）。
> Docker Compose 按 **compose 文件所在目录**找 `.env`，放在仓库根目录会读不到——现象是
> `required variable MYSQL_ROOT_PASSWORD is missing a value`（明明填了却起不来，见 D47）。

### 5.1 应用消费的变量（改这些才有效果）

| 变量 | 用途 | 代码里的默认值 | 是否必须外部提供 |
| --- | --- | --- | --- |
| `MYSQL_HOST` / `MYSQL_PORT` / `DB_NAME` | 后端连接 MySQL | `localhost` / `3306` / `work_order` | 否（compose 内会是服务名 `mysql`） |
| `MYSQL_USER` | MySQL 账号 | `root` | 否（compose 下由 `MYSQL_ROOT_PASSWORD` 决定后端口令） |
| ~~`MYSQL_PASSWORD`~~ | **已移除：写了不生效的死配置** | —— | **不要设**。compose 里后端的环境变量 `MYSQL_PASSWORD` 取自 `${MYSQL_ROOT_PASSWORD}`，你另设的会被覆盖；要改后端连库口令请改 `MYSQL_ROOT_PASSWORD`。**这也意味着后端是用 MySQL root 账号连库的**——演示环境的取舍，**生产应改为最小权限的专用用户**（只授予 `work_order` 库所需权限） |
| `REDIS_HOST` / `REDIS_PORT` | Redis 连接与 Sa-Token 会话 | `localhost` / `6379` | 否 |
| `LLM_API_URL` / `LLM_API_KEY` | LLM 智能分诊（triage） | **空** | 否（生产建议配）。为空时 triage 降级为 `OTHER`/普通，**提交仍然成功**（设计好的降级路径）。⚠ 这两键**必须经 compose 传进容器**（`${LLM_API_URL:-}` / `${LLM_API_KEY:-}`，见 D57）：只在宿主机 export 对容器无效。为空时启动期打一条 WARN，部署冒烟项之一是"容器内这两个变量非空" |
| `LLM_MODEL` | triage 调用的模型名 | **无默认值**（故意） | **必须显式配置**，且要与 `LLM_API_URL` 所属供应商支持的模型名一致（例：DeepSeek → `deepseek-flash`）。留空 → 启动自检报 `未配置 LLM_MODEL`；写成别家的模型名 → 启动自检报 `HTTP 400（模型名可能不被支持…）`。**为什么不给默认值**：默认值必然指向某一家供应商，换一家就 400——"指向错误目标的默认值比没有默认值更糟"（D58） |
| `RABBITMQ_HOST` / `RABBITMQ_PORT` | 后端连 MQ 的地址（P1 步骤 3 起**已生效**） | `localhost` / `5672` | 否。**compose 部署时留空**：compose 会填服务名 `rabbitmq`/`5672`（写 `localhost` 会连不上自己） |
| `RABBITMQ_USER` / `RABBITMQ_PASS` | MQ 账号口令（P1 步骤 3 起**已生效**） | `guest` / `guest` | **口令必须提供**。默认 `guest` 只在"应用与 broker 同机（loopback）"时可用——RabbitMQ 拒绝 guest 从非 loopback 登录，容器里连服务名会被明确拒绝，因此它不会悄悄漏到生产 |
| `OUTBOX_DISPATCH_ENABLED` | outbox 投递任务开关（P1 步骤 3 起**已生效**） | **`false`** | 生产必须显式设 `true`（compose 已设）。默认关闭是为了让本地与 CI 在**没有 broker** 的环境也能启动与跑测试 |
| `TEST_DB_NAME` / `TEST_REDIS_DB` | 测试专用库与 Redis DB（仅 `mvn test`） | `work_order_test` / `1` | 否，但**不要与业务库/业务 Redis DB 相同**（见 §六 与 `docs/DECISIONS.md` D24） |

### 5.2 Compose 消费的变量（容器编排用）

| 变量 | 用途 | 默认值 | 是否必须外部提供 |
| --- | --- | --- | --- |
| `MYSQL_ROOT_PASSWORD` | MySQL 容器 root 口令 + 后端连库口令 | **已移除默认值**（原先的 `WorkOrder@2026` 是真实感口令，不应随仓库公开） | **必须提供**。未设置时 compose 会以空值启动，MySQL 容器会直接失败 |
| `RABBITMQ_USER` / `RABBITMQ_PASS` | MQ 容器默认账号口令（同时传给后端） | 用户默认 `workorder`；**口令无默认值** | **口令必须提供**。未设置时 compose 直接报错退出（`${RABBITMQ_PASS:?...}`），不会用空口令起一个能被登录的 broker |

### 5.3 保留但尚未生效的变量

**当前为空**。P1 步骤 3 已把 `RABBITMQ_*` 参数化并接入（原记载的"硬编码、不接受环境变量覆盖"已不成立，移入 §5.1），本应用其余配置项均已生效。

### 5.4 部署步骤（一句话版）

1. 本地：`cp .env.example deploy/.env` → 填 `MYSQL_ROOT_PASSWORD` 与 `RABBITMQ_PASS`（后端连库口令取自前者；**不要**再设 `MYSQL_PASSWORD`，那是死配置）→ 按 §二 启动。
2. 服务器：在服务器上新建 **`deploy/.env`**（**不要从本地拷**，避免把本地口令带上去）→ 填必备值 → `docker compose up -d --build`。
3. 生产必须同时替换：`MYSQL_ROOT_PASSWORD`、`RABBITMQ_PASS`、种子 `admin` 口令（`sql/init.sql` 里的 `admin123` 仅用于本地演示）。**注意没有 `MYSQL_PASSWORD` 这一项**（它是已移除的死配置，见 §5.1）。

**部署清单固定一环 · 自检容器 swap 已禁用**（改过任何 `mem_limit` 之后必须重跑）：

```bash
# 逐组比对：每个服务的 mem_limit 与紧随其后的 memswap_limit 必须完全相等
docker compose -f deploy/docker-compose.yml config \
  | grep -E '^\s*(mem_limit|memswap_limit):' \
  | awk -F': ' '{ if ($1 ~ /mem_limit/) { prev=$2 } else { if (prev != $2) { print "MISMATCH -> mem_limit=" prev " memswap_limit=" $2; bad=1 } prev="" } } END { if (!bad) print "OK: mem_limit == memswap_limit（容器 swap 已禁用）"; exit bad }'
```

判据：输出 `OK:` 才算通过；出现 `MISMATCH` 说明某个服务的 `memswap_limit` 没跟上 `mem_limit`——**此时容器会重新获得等量 swap**，必须先修再部署（依据：`CLAUDE.md` §3 架构不变量第 12 条）。

### 5.5 本地开发怎么提供 `MYSQL_PASSWORD`（**已无默认值**）

`application.yml` 从 2026-09-24 起写作 `${MYSQL_PASSWORD}`（**去掉默认值**）：不会再悄悄用 `123456` 这类弱口令连库。

> **实测行为（2026-09-24，务必知情）**：缺这个值时应用**仍然能正常启动**（Tomcat 起、`Started WorkOrderApplication`），失败发生在**首次访问数据库**时——日志是 `CannotGetJdbcConnectionException: Failed to obtain JDBC Connection`，不是启动期拦截。也就是说它能挡住"用弱口令连上不该连的库"，但**不会在启动阶段就把问题摆出来**。若需要"启动即失败"，要另加一处启动期校验（当前没有；这是一条待裁决项，不在本轮做）。

三种提供方式，任选其一：

| 方式 | 做法 | 适用 |
| --- | --- | --- |
| **A. `.env` 文件（推荐，compose 与本地都吃）** | `cp .env.example deploy/.env`（**必须在 `deploy/` 下**），填 `MYSQL_ROOT_PASSWORD` 与 `RABBITMQ_PASS`。`.env` 已被 `.gitignore` 覆盖 | 用 Docker Compose 起全栈、或本地起后端 |
| **B. 环境变量（一次性）** | PowerShell：`$env:MYSQL_PASSWORD='你的口令'; mvn spring-boot:run`<br>bash：`MYSQL_PASSWORD='你的口令' mvn spring-boot:run` | 临时跑一次、脚本化启动 |
| **C. IDE Run Configuration** | 在 IDEA 的 Run/Debug Configurations → Environment variables 里加 `MYSQL_PASSWORD=你的口令`（建议只放个人 run config，**不要提交** `.idea/`） | 图形界面里点运行 |

> **测试不受影响**：`src/test/resources/application-test.yml` 自带完整数据源配置（url/username/password 与测试库 `work_order_test`），`mvn test` 不依赖这个环境变量。实测见本轮报告。
>
> **如果你觉得本地开发确实不便**（例如每次都要设变量太麻烦），**先提出来再决定**是否改为"保留默认值 + 注释声明仅本地开发"——本轮不做折中。

## 六、测试与探针

### 5.1 测试环境准备（一次性）

测试**不连业务库**：`src/test/resources/application.properties` 默认激活 `test` profile，
其数据源指向独立测试库 `work_order_test`（见 `application-test.yml`）。首次使用需建库并导入种子数据：

```bash
mysql -h127.0.0.1 -P3306 -uroot -p -e "CREATE DATABASE IF NOT EXISTS work_order_test CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
mysql -h127.0.0.1 -P3306 -uroot -p --default-character-set=utf8mb4 work_order_test < sql/init.sql
```

```bash
mvn test        # 需要本机有可用的 MySQL 与 Redis
```

> **Redis 是硬依赖，不是可选件**（P1 步骤 4 实测）：不启 Redis 时全量测试会出现 **38 个 error**，全部是
> `RedisConnectionFailureException: Unable to connect to Redis`（`OrderNoGenerator`、Sa-Token 会话、驳回幂等键都在 Redis 上）。
> 测试用 **DB 1**（`TEST_REDIS_DB`，`application-test.yml`），应用用 **DB 0**——两者共用同一个 Redis 实例，因此：
> **重建/清空这个 Redis 会连带清掉应用的每日单号计数器** `order:seq:<yyyyMMdd>`，后果是提交工单出现
> `Duplicate entry 'WO-<日期>-xxxxx' for key 't_work_order.order_no'`（HTTP 200 + body `code=500`）。
> 恢复办法：`SET order:seq:<今日> <库里今日最大序号>`（见 `docs/DECISIONS.md` D45）。

> 为什么隔离：`WorkOrderFlowServiceTest` 需要"真实提交 + 跨事务可见"，无法用 `@Transactional` 回滚；
> 它此前直写业务库，单次运行留下 27 行 `TST-` 数据、4 次累积 108 行（见 `INVARIANTS.md` I9）。
> 现在所有测试统一指向 `work_order_test`，业务库在物理上不会被测试写入。
>
> 另两处配置坑（都在 `application-test.yml` 里注明了原因）：`connectionTimeZone` 必须用**偏移量**
> 而不是命名时区 `Asia/Shanghai`（命名时区要求 MySQL 已加载时区表，否则连接直接报 ERROR 1298），
> 且 URL 里的 `+` 必须写成 `%2B`（JDBC 按 form-urlencoded 解码，裸 `+` 会变成空格）。

### 5.2 探针（改造后跑一遍的固定动作）

```bash
# 不变量探针：一次输出 P1–P15 的 期望/实际/判定，result 列 PASS/FAIL 一眼可见
mysql -h127.0.0.1 -P3306 -uroot -p work_order < sql/probes.sql
```

无法用纯 SQL 表达的三条，按下列命令执行：

```bash
# P7：权限码与注解双向一致（代码引用的权限码 ⊆ 库中定义；库中定义 ⊆ 代码引用）
grep -rhoP '@SaCheckPermission\("\K[^"]+' src/main/java | sort -u > /tmp/code_perms.txt
grep -rhoP 'checkPermission\("\K[^"]+' src/main/java | sort -u >> /tmp/code_perms.txt
sort -u /tmp/code_perms.txt -o /tmp/code_perms.txt
mysql -N -B -e "SELECT perm_code FROM t_permission WHERE perm_code NOT LIKE '%:*'" work_order | sort -u > /tmp/db_perms.txt
comm -23 /tmp/code_perms.txt /tmp/db_perms.txt   # 期望为空：代码引用了库里没有的
comm -13 /tmp/code_perms.txt /tmp/db_perms.txt   # 期望为空：库里定义了却没人用

# P10：状态机守卫（回归用）
mvn -o test -Dtest=StateMachineValidatorTest

# P13：测试不污染业务库（跑两轮，业务库计数必须不变）
mysql -N -B -e "SELECT COUNT(*) FROM work_order.t_work_order" > /tmp/before.txt
mvn -o test > /dev/null 2>&1 && mvn -o test > /dev/null 2>&1
mysql -N -B -e "SELECT COUNT(*) FROM work_order.t_work_order" > /tmp/after.txt
diff /tmp/before.txt /tmp/after.txt && echo "PASS: 测试未污染业务库" || echo "FAIL"
```

**当前已知的探针状态（2026-09-23，P0a 完成时）**：P11 为 **FAIL** 且属预期——它检验
`t_sla_config` 覆盖 4 类 × 2 优先级 = 8 条，而类型枚举替换（R4）安排在 P0b，落地后自动转 PASS。

### 5.3 本地桩 `scripts/stub-llm.py`：**仅供确定性延迟演练，不作为功能验收工具**

- **定位**：它只用来取"确定性延迟"的数字（固定 N 秒后返回固定 JSON）。**不能拿它的输出判定应用行为对不对。**
- **~~已知限制：Java ↔ 该脚本存在连接层抖动~~ 已定位并修复（2026-09-25）**：那些"抖动"其实是**桩自己的两个缺陷**——
  ① 只按 `Content-Length` 读请求体，而 Java/Spring 用 `Transfer-Encoding: chunked` 发 POST → 桩读到 0 字节 →
  判成"模型名不对"→ **返回 400**（并在客户端还在发送时就写回响应，于是报"连接被中止"）；
  ② 监听队列用 `socketserver` 默认值 **5**，30 并发下被内核打满 → 客户端拿到 `Connection refused: getsockopt`。
  两个缺陷都让"改造前"的压测行被污染成"慢的 400 / 部分兜底"。修复后实测：120 请求 **0 次 triage 失败**、
  全部工单带上桩返回的 `priority=1`（见 §九 的三行对照表）。**定位不变**：它仍只用于延迟演练，
  不评测模型质量；但"Java 读不通本桩"这条限制**已消失**。
- **本地功能验证（triage 是否真的跑通、前端是否显示"分类中"）请指向真模型**：
  把 `LLM_API_URL` / `LLM_API_KEY` / `LLM_MODEL` 指到供应商接口（本机 host 侧 `curl` 已验证可访问该接口），
  再按上面的自检表核对启动日志——出现 `[启动自检] LLM 探测通过（triage 可用）` 才是"配置真的接上了"的判据。
- **压测脚本的口径约定**（规则本体在 `CLAUDE.md:78`）：**改 `scripts/loadtest.sh` 的判据时必须同时改 `scripts/loadtest.ps1`**——
  本地基线用 ps1 取（Windows 无 bash），服务器用 sh 跑，两版判据不同则两批数字不可并列。
  **口径包含计时方式**：2026-09-25 之前 ps1 在 `WaitAll` 之后才逐个 `Stop()`，等于把**每波最慢值**当成每个样本的耗时
  （实测把 P50 从 3.03s 抬到 6.15s），而 sh 版用 `curl -w '%{time_total}'` 一直是每请求真实耗时——已修（见 §九）。

### 5.4 AI 分诊评测集：`scripts/triage-eval.py`（把"判得准不准"变成测量事实）

**为什么有它**：一次真实事故——"人工选定的类型"被误读成"AI 判错"。根因不是模型，而是**没有测量**：
类型/优先级的来源（人填的还是 AI 判的）与准确率都没有数字，只能靠读日志推断。

```bash
# 前置：后端在跑 + OUTBOX_DISPATCH_ENABLED=true + broker 可达 + LLM_API_URL/KEY/MODEL 指向真实模型
#      ⚠ 不要对着业务库/演示库跑：每条用例都会真的提交一张工单
python scripts/triage-eval.py --base-url http://127.0.0.1:9000 --timeout 90
```

| 项 | 说明 |
| --- | --- |
| 用例 | `scripts/triage-eval-cases.json`，**20 条**：14 条有标准答案（其中 4 条是**真歧义**，用 `expect_types` 数组承认两种答案都对）+ **6 条 `insufficient`**（信息不足，单独看"是否保守"：判 `OTHER` 且 `priority=0` 记保守） |
| 优先级评判 | 只在文本有**明确**紧急信号（人身安全/大面积/爆管/冒烟）或明确局部轻微时才标注期望值；其余不评——不拿主观标准当准确率 |
| 测什么 | 走**真链路**：只带 title/content 提交 → 轮询到 `triageStatus` 离开 `PENDING` → 比对最终 `type/priority`。因此覆盖 prompt、消费端白名单、H4 重算与"分诊失败"分支 |
| 输出 | 逐条结果 + **类型准确率 / 优先级准确率 / 信息不足组保守率 / 失败清单**，并打印本次产生的工单 ID 与清理 SQL（D19：写数据的东西必须说清写了什么、怎么清） |

**⚠ 本机测不出准确率**：本机与 `deploy/.env` 都没有 LLM key，唯一可用的"模型"是 `scripts/stub-llm.py`，
而桩对任何输入都返回同一组固定值——用它跑出来的是**机械自检**（证明脚本能正确判对/判错），**不是准确率**。
实测（桩固定返回 `NETWORK/1`）：类型 5/14、优先级 5/8、信息不足组保守率 0/6、失败清单 10 条，20 条耗时约 24 秒。
**真实准确率待配了 key 的机器上重跑**（同一条命令）。

### 排障：PARKED 停车记录的重放（消费失败的人工入口，P4 步骤 3）

消费失败会落进重试账本 `t_message_retry`，按 **1m → 5m → 15m → 1h → 6h** 自动重投；**连续失败 6 次**后置为 `PARKED`
并打 ERROR 日志——此后不再自动重投，**需要人工介入**。本轮**不提供管理端点**（端点要配权限码、要审计、要测试，
成本高于收益），入口就是下面两条 SQL + 探针 **P16d**。

```sql
-- ① 看有哪些停车记录（探针 P16d 就是这条的计数，期望 0）
SELECT event_id, consumer, attempt, last_error, created_at
FROM t_message_retry WHERE status = 'PARKED' ORDER BY created_at;

-- ② 重放一条：状态改回 PENDING、下次重投时间置为立即，**并把 attempt 重置为 0**
UPDATE t_message_retry
SET status = 'PENDING',
    attempt = 0,                                  -- 必须重置！否则下次失败时 attempt(7)>5 → 立刻再停车，重放等于没做
    next_retry_at = NOW(),                        -- 立即到期，重投任务（每 10s 一轮）下一轮就会投
    last_error = CONCAT('[人工重放 @ ', NOW(), '] ', IFNULL(last_error, ''))   -- 保留历史原因，便于追溯
WHERE event_id = '<把①里的 event_id 填进来>' AND consumer = 'order-release-listener';
```

**重放前先读 `last_error` 定位根因**（例如"工单不存在"就要先确认为什么不存在）；根因没消除的话，
它会再走一遍阶梯并再次停车。**`attempt` 的处理是本步骤的关键**：重置为 0 = 重新给满 5 次自动重投机会；
不重置 = 下一次失败立即 `PARKED`（看起来"重放没生效"）。若你希望"只试一次"，把 `attempt` 设为 **5** 即可
（下一次失败 → `attempt=6` → 停车）。

**为什么消息本体不需要额外处理**：重投任务是拿 `t_message_retry.payload` 原样投出的（带原 `x-event-id`），
所以只要这一行还在，重放就一定能发出同一条事件；消费端的事件级去重（`t_consume_record`）会保证不重复执行业务。

### 排障：看到"triage 降级"（AI 把所有单都判成 `OTHER`/普通）该按哪三步查

**先看启动日志里那条自检**（`LlmStartupCheck`，每次启动都会打一次）：

| 启动日志 | 含义 | 处置 |
| --- | --- | --- |
| `[启动自检] LLM 探测通过（triage 可用）` | 配置齐全且能打通 | 无需处理 |
| `[启动自检] 未配置 LLM_API_URL / LLM_API_KEY` | 变量没进容器 | 走下面第 1、2 步 |
| `[启动自检] 未配置 LLM_MODEL（必须显式配置…）` | 模型名没配 | 填 `LLM_MODEL`（第 1、3 步） |
| `[启动自检] LLM 探测失败：HTTP 400（模型名可能不被支持：LLM_MODEL=… 必须与 LLM_API_URL 所属供应商匹配）` | **模型名与供应商不匹配**（例：给 DeepSeek 的 URL 配了 `gpt-3.5-turbo`） | 改成该供应商支持的模型名（第 3 步） |
| `[启动自检] LLM 探测失败：HTTP 401/403（LLM_API_KEY 无效或无权限）` | key 不对 | 换 key（第 3 步） |
| `[启动自检] LLM 探测失败：不可达或超时…` | URL/网络/代理问题 | 查 `LLM_API_URL` 与出网（第 3 步） |

1. **确认宿主机 `.env` 里填了三个键**：`grep -E '^LLM_' deploy/.env`（`LLM_API_URL` / `LLM_API_KEY` / `LLM_MODEL` 都要有值）。
2. **确认变量真的进了容器**（历史踩过的坑：`.env` 填了但 compose 没传，见 D57）：
   ```bash
   docker compose -f deploy/docker-compose.yml config | grep -E 'LLM_API_(URL|KEY)|LLM_MODEL'
   docker exec workorder-backend sh -c 'echo "${LLM_API_URL:-（空）}"; echo "${LLM_MODEL:-（空）}"'
   ```
   为空 → `docker compose up -d backend` 重建容器；仍为空 → 检查 `.env` 是否放在 `deploy/`（compose 只读 compose 文件同级目录，见 D47）。
3. **手工打一次模型接口，确认 key 与模型名**（把错误从"应用层"拉回到"凭据/模型"层）：
   ```bash
   curl -sS -o /tmp/llm.json -w '%{http_code}\n' "$LLM_API_URL" \
     -H "Authorization: Bearer $LLM_API_KEY" -H 'Content-Type: application/json' \
     -d '{"model":"'"$LLM_MODEL"'","messages":[{"role":"user","content":"ping"}],"temperature":0.1}'
   head -c 300 /tmp/llm.json    # 400 → 模型名；401/403 → key；超时 → 网络
   ```

**注意**：降级**不阻断业务**（提交照常成功，工单先用兜底 `OTHER`/普通落库），所以它的危害是"分类失真"而不是"服务不可用"
——这也是自检**不阻止启动**的原因（见 D58）。

---

## 七、文档地图

| 文件 | 作用 | 是否权威来源 |
| --- | --- | --- |
| `CLAUDE.md` | 工程纪律、架构不变量、交付标准 | 是（协作规则） |
| `BUSINESS-SCOPE.md` | 业务范围、场景、功能清单、权限矩阵、演示动线 | **是（业务基线）** |
| `INVARIANTS.md` | 必须永远成立的条件 + 探针 SQL + 恢复步骤 | **是（与业务基线配套）** |
| `ASYNC-SCHEDULING-PLAN.md` | 异步与调度改造方案（资源核算、业务点论证、选型、实施顺序 P0–P7） | 是（技术方案） |
| `CONTEXT.md` | 领域术语表 | 是（术语） |
| `TECHNICAL-PLAN.md` | 原始设计文档（含**未实现**的 MQ/XXL-Job 设计，勿当作既成事实） | 参考 |
| `RABBITMQ-MIGRATION.md` | Mock → RabbitMQ 的迁移指南（与当前实现已漂移，见技术方案 §3.4） | 参考 |
| `PROJECT_MAP.md` | 仓库测绘（模块划分、漂移点） | 参考 |
| `ISSUES.md` | 历史任务清单，**已废弃、不作为任务来源** | 否 |
| `docs/INTERVIEW-*.md`、`docs/PERFORMANCE-TUNING.md` | 对外素材与性能调优记录，描述的是改造前的系统 | 否 |

任务来源：`ASYNC-SCHEDULING-PLAN.md` 的阶段化方案（P0–P7），不再使用 `ISSUES.md`。

---

## 八、可靠性叙事："接单后到点释放"这条链路上有什么在保护它

> 一句话版：**接单时把事件写进同一个业务事务（outbox）→ 定时投递且只有 broker 确认（publisher-confirm ack）才算发出去
> → broker 的延迟交换机负责"到点前谁也别想拿到它" → 消费端两道幂等防线（去重表 + 状态守卫）→ 失败进重试账本按阶梯退避、
> 超限停车等人工介入 → 而兜底扫描独立于这条路径，始终是释放的权威通道。**

每一环都标出"它挡的是什么故障"，并尽量给出**已实测**的数字（不是设计预期）：

| # | 环节（代码位置） | 挡的是什么故障 | 实测口径 |
| --- | --- | --- | --- |
| 1 | **事务内写 outbox**（`WorkOrderServiceImpl.publishReleaseCheck` → `OutboxMessagePublisher`） | "业务提交了、消息却没发出去"（afterCommit 直发的双写窗口：commit 后崩溃 → 消息永久丢失且无记录） | 业务事务回滚 → outbox **0 行**（`OutboxWritePathTest`）；提交 → 恰 1 行 |
| 2 | **定时投递 + publisher-confirm**（`OutboxDispatchTask`，5s 一轮；只有 `Confirm.isAck()` 才标 `SENT`） | "消息交出去了但从没到 broker"（假发送；`convertAndSend` 不抛异常 ≠ broker 收到） | 停 broker → 记录保持 `PENDING`、`retry_count` 1→2→3、`next_retry_at` 每 30s 后移；**broker 恢复后 30 秒内补投为 `SENT`**（实测 19:28:41 投出） |
| 3 | **broker 延迟交换机**（`RabbitOutboxConfig` 的 `x-delayed-message` + `x-delay = deliver_at - now`；自建镜像含插件） | "还没到点就被处理"（提前释放）——包括应用与数据库时钟有偏差的情况 | `x-delay=60000`：t+30s 队列 **0** → t+62s 队列 **1**；**broker 被 `SIGKILL` 后消息仍在**（t+10s 杀、t+125s 队列 1） |
| 4 | **消费端两道幂等防线**：① `t_consume_record`（`UNIQUE(event_id, consumer)`）② 状态守卫（`WHERE status='ACCEPTED'`） | ① 同一条事件被处理两次；② 迟到的释放检查把**已开工/已释放**的单改错状态 | 同一 `eventId` 消费两次 → 第二次 `duplicate`，工单 `version` 只 **+1**；工单已 `IN_PROGRESS` → `SKIPPED`（且**不写**重试账本） |
| 5 | **失败重试账本 + 阶梯**（`t_message_retry` + `MessageRetryService`，**在业务事务之外**写） | "消费失败后消息就消失了"（旧行为是 ACK 掉 + 一行日志） | 业务失败 → 去重 **0 行** **且** 账本 **1 行**（灵魂断言）；阶梯 **1m/5m/15m/1h/6h**，**累积重投跨度 ≈7h21m**；第 6 次失败 → `PARKED` + ERROR 日志 |
| 6 | **兜底扫描（独立通道）**（`ReleaseTimeoutScheduler`，60s 一轮，时限取自 `t_sla_config.accept_minutes`） | **整条 MQ 链路全挂/消息全丢**——它是"工单最终一定会被释放"的最终保证 | 停 broker 接单（`accept_minutes=2`）→ **t+181s 由兜底释放**；同一配置下 MQ 路径 **t+121s**（两条路径时限一致） |
| 7 | **时钟一致性**（时区：容器 `TZ`、JVM `-Duser.timezone`、JDBC `connectionTimeZone=%2B08:00`；探针 P15a/P15b） | "所有工单瞬间超时"或"永不超时"（JVM 与 MySQL 差 8 小时时，SLA 判定全错但**不报错**） | 服务器批：`P15a-fresh = 0 秒`、`P15b = -28800`、`P15a-future = 0`（三方一致） |

**这一节刻意不写的（因为代码里目前没有）**——自查用，避免把"设计里有"当成"已经做了"：

| 不在表里的能力 | 现状 |
| --- | --- |
| DLX / 死信队列 / 停车队列 | **评估后不采用**（DB 账本已覆盖其职责，见 D54）——代价是"排查看数据库而不是管理台" |
| 消费端 NACK / requeue | **没有**：消费者一律 ACK，失败靠重试账本重投（改 NACK 的前提是 DLX，而 DLX 不做） |
| xxl-job 调度中心 | **未接入**（P2）：现在两个扫描任务都是进程内 `@Scheduled`（兜底释放 60s、SLA 扫描 300s） |
| SLA 告警的事件级去重 | **未做**：仍是 Redis `SETNX sla_notified:{orderId}` + 24h TTL 的粗粒度去重 |
| 通知表 `ref_type/ref_id` | **部分回填**：提交通知这条新链路已回填（P5 步骤 2），**SLA 超时 / 驳回达上限两条老链路仍未回填**（历史上实测 82/82 为 NULL）。因此 plan §5.2 那个四元组唯一索引仍然**建不起来**——这也是提交通知改用 `event_id` 唯一键的原因（D59）。`ref_*` 回填仍是待办 |
| 提交通知（提交 → 处理人站内信） | ~~未做~~ **已完成（P5 步骤 2，2026-09-25）**：提交事务只写一行 `ORDER_SUBMITTED` outbox；消费端按角色 `HANDLER` 群发，两道幂等（去重表 + `UNIQUE(event_id, user_id)`），工单不在 `PENDING` 时 `SKIPPED`；失败复用 P4 重试账本。规则见 `BUSINESS-SCOPE.md` F1-1 的「提交通知规则」表。**代价**：本机同机对照显示提交 P50 由 94.5–95.0ms 升至 107.5–110.3ms（每单多一行 outbox insert），P99 区间重叠（179.2–202.0ms vs 187.3–210.5ms） |
| 「人工指定」与「AI 判定」在界面上不可辨 | **已知缺口，本轮评估后暂不做**（D64）：类型/优先级只有最终值，**来源没有落库**，于是"用户选了 UTILITY、AI 改成 NETWORK"与"用户没选、AI 判成 NETWORK"长得一样（本轮一次误读就是这么发生的）。现状可用的替代：详情页操作日志里有 `TRIAGE` 记录（`operator_id=0` → 显示为「AI 分诊修正 / 操作人：系统」，D63 修）；**列表页看不出**。要做需加每字段来源列（`type_source`/`priority_source`），代价与触发条件见 D64 |
| Triage（AI 分类）异步化 | ~~未做（P5）：提交时同步调 LLM~~ **已完成（P5 步骤 1，2026-09-25）**：提交只落库 + 发 `ORDER_TRIAGE` 事件，由消费端异步分诊（见 D55 与下面的 P5 小节） |
| 失败的"可重试 / 不可重试"分类 | **未做**：当前 `ERROR` 与异常一律按可重试处理（会走满阶梯才停车） |

**另一条链路的同一套防护：「提交后通知处理人」（P5 步骤 2，2026-09-25）**

它的形态与上面的释放链路同构，但**通知对象是"角色下的全部用户"**（每单 30–50 行插入），
这正是 plan §2.1 最早论证"必须异步"的那条链路。逐环对照（同样只写代码里真有的东西）：

| # | 环节（代码位置） | 挡的是什么故障 | 实测口径 |
| --- | --- | --- | --- |
| 1 | **事务内写 outbox**（`submitOrder` → `OrderEvent.orderSubmitted`） | "工单提交了、通知没登记"（提交事务与通知之间没有一致点） | 提交后 outbox 恰 1 行 `ORDER_SUBMITTED`，而**此刻站内信 0 条**——证明接收人解析没跑在提交事务里（`OrderSubmittedNotifyTest`） |
| 2 | **定时投递 + publisher-confirm**（`OutboxDispatchTask`） | 假发送（"以为发出去了"） | 停 broker → 记录保持 `PENDING`、`retry_count` 1→2、`next_retry_at` 后移；**broker 恢复后 30 秒内补投为 `SENT`，站内信补发 1 条**（本机真 broker 实测） |
| 3 | **消费端两道幂等防线**：① `t_consume_record` ② `UNIQUE(event_id, user_id)`（`t_notification`） | ① 同一条事件被处理两次；② **同一条事件给同一个接收人发两条站内信**（去重表被归档清理、或有人绕过消费端直写通知时） | 重投（去重表在）→ `DUPLICATE` + ACK，通知数恒为 1；**删掉去重记录再重投** → 唯一键挡住，通知数仍为 1（两次都实测） |
| 4 | **状态守卫**（仅 `status='PENDING'` 时通知） | 对"已经不在池子里"的单广播"池子里有新单"（误导性噪音） | 工单被抢单（`ACCEPTED`）后重投 → `SKIPPED`，通知数不变、**不写**重试账本 |
| 5 | **失败重试账本 + 阶梯**（`MessageRetryService`，在业务事务之外） | "通知写失败就永远没人知道" | 站内信写入失败 → 去重记录 **0 行** 且账本 **1 行**（`OrderSubmittedNotifyFailureTest`）；阶梯与释放链路同一套 |
| 6 | **业务侧兜底**（`PENDING` 池列表轮询） | 通知这条链路整体失效时处理人看不到新单 | 不是新机制：plan §2.1 明确"丢失期间工单仍在池子里，可被列表看到；SLA 计时照常流逝" |

---

## 九、P5 步骤 1：Triage 异步化（改造前后实测对照）

**改了什么**：提交时缺 `type`/`priority` 的工单**不再同步等 LLM**——先用兜底值（`OTHER`/`0`，值来自 `t_sla_config`）落库、
`triage_status='PENDING'`，并在同一事务内发 `ORDER_TRIAGE` 事件；消费端（`OrderTriageListener`）调 LLM 后
**只写回提交时为空**的那些字段（元信息 `missingFields` 随消息走），按 H4 以 `created_at` 为基准重算 `sla_deadline`，
重算后若已过期则立即告警（计为首次告警）。失败复用 P4 的重试账本（阶梯 + PARKED）。

**实测对照（同口径：本机、stub LLM 固定 3s 延迟、脚本校验业务 code、预热分离）**

| 场景 | 请求数 / 业务成功 | P50 | P95 | P99 | max | MySQL 连接数 |
| --- | --- | --- | --- | --- | --- | --- |
| 改造前（同步等 LLM，并发 30） | 150 / 150 | 3097.8ms | 3110.6ms | **3110.9ms** | 3111ms | **21（池上限 20 被占满 + 采样器 1）** |
| 改造后（并发 30） | 4680 / 4680 | 110.7ms | 202.7ms | **230.7ms** | 240.2ms | 21（池按需扩容后保持；在忙时间从 3s 降到毫秒级） |
| 改造后（并发 5） | 4420 / 4420 | 21ms | 26.5ms | **33.5ms** | 169.5ms | — |

**怎么读这组数字**：① 提交延迟不再由 LLM 决定（改造前 P50≈P99≈LLM 延迟）；
② **连接池占用**是比 P99 更严重的隐患——改造前 30 并发下 `Threads_connected` 恒为 21（20 条全被 LLM 阻塞占用，
第二个 30-request 波次的 P50 直接涨到 6.1s，就是排队等连接），改造后同一池吞吐从 7.5 req/s 提到 234 req/s。

#### 9.1 三行同参数对照（2026-09-25 重测：每请求计时 + 桩已修复，**本节的数字取代上表**）

**参数声明（三行完全一致）**：`OMIT_TYPE=1`（不带 type → 走 triage 路径）、`TRIAGE_MODE=async`（三行同一个值：async 不剔除任何样本，
统一按"业务 code=200 即计入"统计）、`CONCURRENCY=30`（= 每波步长 30）、`WARMUP_WAVES=1`（1 波预热，丢弃）、`DURATION_SEC=30`、
本机同一台机器、`wo_perf` 专用库、**每行跑前清空 outbox/工单/通知/账本**（消除 FIFO 积压与表增长的影响）；
LLM 侧是 `scripts/stub-llm.py`（固定延迟 3000ms、返回 `type=OTHER, priority=1`）；三行各跑 **2 遍**且第二遍**反向顺序**。
计时口径 = **每请求自己完成时刻**（ps1 已修，与 bash 版 `curl %{time_total}` 一致）。

| 行 | 构建（提交） | 请求数 / 业务成功 | P50 | P95 | P99 | max |
| --- | --- | --- | --- | --- | --- | --- |
| ① 改造前（提交时同步等 LLM） | `36b7320`（P5 步骤 1 之前） | 150 / 150（第 2 遍 150 / 150） | **3130.5ms**（3136.7） | 6177.5ms（6195.2） | **6185.6ms**（6199.8） | 6185.6ms（6200.5） |
| ② 改造后 · 无提交通知 | `ba3c474`（P5 步骤 1 之后） | 6600 / 6600（6270 / 6270） | **96.2ms**（101.6） | 158.9ms（165.6） | **193.8ms**（206.4） | 295.9ms（314.4） |
| ③ 改造后 · +提交通知（当前 HEAD） | `94bea42` | 4260 / 4260（4920 / 4920） | **149.4ms**（130.9） | 225.5ms（201.6） | **264.4ms**（232.1） | 381.5ms（388.4） |

**每行的 type / 优先级分布**（证明测的是"预期的那条路径"，不是降级路径）：

| 行 | 响应 `type` 分布 | 落库 `type / priority / triage_status` | "triage 真的跑通"的判据 |
| --- | --- | --- | --- |
| ① | `OTHER×180` | `OTHER / 1 / DONE` ×150 | **`priority=1` 就是桩的返回值**（降级只会是 `fallback()` 的 `0`）；且应用日志里 triage 失败/回落 **0 条**（120 请求全走通 LLM 往返） |
| ② | `OTHER×6630` | `OTHER / 0 / PENDING` ×6630 | 异步形态**本该**是兜底落库 + 待分诊；证据是 `triage_status=PENDING` 且 outbox 每条工单 1 行 `ORDER_TRIAGE` |
| ③ | `OTHER×4290` | `OTHER / 0 / PENDING` ×4290 | 同上；另加每条工单 1 行 `ORDER_SUBMITTED`（本轮新增的提交通知事件） |

**怎么读**：① 同步路径 **P50 3.13s、P99 6.19s**，且**是双峰**——`<3.5s` 有 80 个样本、`≥6s` 有 40 个：
前 20 个并发拿到连接（3.0–3.1s，纯 LLM 往返），其余在**等连接池**（+3s）。这个"一半请求被池排队"的形状，
就是"事务里持有连接等外部调用"的直接后果。② 同样的提交在异步形态下 **P50 96ms / P99 194ms**（≈**32×** / 32×）。
③ 加上提交通知后 **P50 131–149ms / P99 232–264ms**：代价是每单多一行 outbox insert
（②每单 3 条 DB 写 → ③ 4 条，写量 +33%，本机吞吐 209–220 req/s → 142–164 req/s），
**与接收人数无关**——如果按同步做法在事务里群发（30–50 名处理人 = 30–50 次插入），量级会完全不同。

**⚠ 上一张表的两处口径缺陷（本轮定位）**：① `loadtest.ps1` 当时在 `WaitAll` 之后才统一 `Stop()`，
于是每个样本记的是**该波最慢请求**的耗时（实测把 ① 的 P50 从 3.13s 抬到 6.15s、把 ② 的 P50 从 96.2ms 抬到 110.7ms）；
② 桩当时读不到 chunked 请求体，Java 每次调用都拿到 **400**，所以旧表"改造前 3110.9ms"实际是**"慢的 400"**，
而不是一次成功的 LLM 往返。两处都已修复，本节数字为修复后的重测；结论方向（同步等外部调用 → 异步）不变，
但**旧的绝对值不要再引用**。

#### 9.2 演示动线第 1 步的端到端时序（异步 triage：提交即返回 → 数秒后写回 + SLA 收缩）

**环境**：真栈（MySQL `wo_demo` + Redis + **真 RabbitMQ**（自建延迟镜像）+ 后端当前提交）+ 桩 LLM（固定延迟 3s，返回 `NETWORK/1`）；
提交请求**只带 title 与 content**（不带 `type`/`priority`，即 P5 步骤 3 放开必填后的前端行为）。

```text
① POST /api/orders                                    (t+125ms)   ← 提交即返回，不等 LLM
{"code":200,"message":"操作成功","data":{"id":2,"orderNo":"WO-20260925-66433","title":"演示-只填标题与内容","content":"3 楼空调不制冷","type":"OTHER","priority":0,"status":"PENDING","submitterId":1,"rejectCount":0,"maxReject":3,"slaDeadline":"2026-09-26T05:39:52.3608602","triageStatus":"PENDING","createdAt":"2026-09-25T21:39:52.3608602","updatedAt":"2026-09-25T21:39:52.3608602"}}

② GET /api/orders （列表里该行）                        (t+1097ms) ← 列表「类型」列据此显示"分类中"
{"id":2,"orderNo":"WO-20260925-66433","title":"演示-只填标题与内容","content":"3 楼空调不制冷","type":"OTHER","priority":0,"status":"PENDING","submitterId":1,"rejectCount":0,"maxReject":3,"slaDeadline":"2026-09-26T05:39:52","triageStatus":"PENDING","createdAt":"2026-09-25T21:39:52","updatedAt":"2026-09-25T21:39:52"}

③ GET /api/orders/2 （详情）                           (t+3158ms) ← 仍是"分类中"
{"id":2,"orderNo":"WO-20260925-66433","title":"演示-只填标题与内容","content":"3 楼空调不制冷","type":"OTHER","priority":0,"status":"PENDING","submitterId":1,"rejectCount":0,"maxReject":3,"slaDeadline":"2026-09-26T05:39:52","triageStatus":"PENDING","createdAt":"2026-09-25T21:39:52","updatedAt":"2026-09-25T21:39:52"}

④ GET /api/orders/2 （同一个接口，数秒后）              (t+6901ms) ← 已分类，SLA 收缩
{"id":2,"orderNo":"WO-20260925-66433","title":"演示-只填标题与内容","content":"3 楼空调不制冷","type":"NETWORK","priority":1,"status":"PENDING","submitterId":1,"rejectCount":0,"maxReject":3,"slaDeadline":"2026-09-25T22:39:52","triageStatus":"DONE","createdAt":"2026-09-25T21:39:52","updatedAt":"2026-09-25T21:39:58"}
     ✅ SLA：2026-09-26T05:39:52（兜底 OTHER/普通 = created+480min）→ 2026-09-25T22:39:52（NETWORK/紧急 = created+60min）
```

**同时满足的五条**：① 提交 **40–100ms 返回**（不是等 3–5 秒）；② 立刻可查到该工单（`type=OTHER/priority=0` 兜底值 + 8 小时 SLA）；
③ 约 **6.5 秒后**写回 `NETWORK/1`；④ SLA 从 8 小时**收缩到 1 小时**；⑤ 全程应用日志 **0 条 LLM 失败**
（`grep -c 'LLM triage|LLM响应格式异常|不在合法集合'` = 0），且 `[triage] 分诊写回完成` 与 `[notify-listener] 提交通知处理完成` 都有记录。

**分类失败的路径（同一套证据，2026-09-25 补）**：把桩改成返回 401（模拟"LLM 不可用"）后提交同样缺字段的工单 →

```
① 提交响应：type=OTHER priority=0 triageStatus=PENDING
② 首次失败后：t_message_retry = 1 行（last_error: TriageUnavailableException: LLM triage 失败：401 Unauthorized）
              工单仍 triageStatus=PENDING（界面「分类中」）
③ 阶梯走完（生产上 1m/5m/15m/1h/6h ≈ 7h21m；本轮为便于演示把 attempt 推到 5 后等重投）：
   账本 = 6/PARKED   工单 = triageStatus=FAILED（界面「分类失败」）
   日志：[triage] LLM 调用失败…（WARN）→ [retry] 分诊重试已停车，工单分诊状态收口为 FAILED（ERROR）
④ 探针：P17（PENDING 且创建超 1 小时 = 0）= PASS    P16d（PARKED 记录数）= 1（需要人工介入的信号）
```

三态现在**都可达**，且响应体里都带 `triageStatus`（提交/列表/详情三处，见 §9.2 上方那段）——
前端按它渲染「分类中 / 已分类 / 分类失败」。**不做本地猜测**这条设计约束仍然成立（理由见 `docs/DECISIONS.md` D61），
字段的落地与第三个缺陷（"失败被洗成成功"）的修复见 **D62**。

**⚠ 这组数字的口径**：本机**没有 LLM key**（`LLM_API_URL/KEY` 未设置），所以"改造前"用的是
`scripts/stub-llm.py`（固定 3s 延迟）——它复现的是**同一条代码路径**（RestTemplate + 5s 超时 + 事务内同步调用），
得到的是**同口径相对对照**，不是真实模型的绝对值。**在配好 key 的机器上重取真实基线的步骤**：

```bash
# 1) 起后端（切到改造前的提交：git stash / 切到 P5 之前的提交），把 LLM 指向真实模型
LLM_API_URL=https://<真实模型>/v1/chat/completions LLM_API_KEY=<key> java -jar target/work-order-system-*.jar
# 2) 跑不带 type 的提交（脚本已支持 OMIT_TYPE）
BASE_URL=http://127.0.0.1:9000 OMIT_TYPE=1 CONCURRENCY=30 WARMUP_SEC=60 DURATION_SEC=120 ./scripts/loadtest.sh
#    Windows 上等价命令：见 scripts/loadtest.ps1（用 API_USER 而不是 USERNAME）
#    ⚠ 形态开关：**异步形态（P5 之后）请用 TRIAGE_MODE=async**——否则响应里的兜底 type=OTHER 会被
#      误判成"triage 未生效"、延迟统计为空（P50/P95/P99 全是 NaN）；同时按脚本末尾输出核对 triage_status：
#        SELECT triage_status, COUNT(*) FROM work_order.t_work_order WHERE title LIKE '压测-triage-%' GROUP BY triage_status;
#        docker compose logs backend | grep '分诊写回成功'
# 3) 压测期间另开一个窗口采样连接数，看是否被占满
mysql -uroot -p -e "SHOW STATUS LIKE 'Threads_connected';"    # 期望：改造前≈21（池上限20+1），改造后≈5-8
```
