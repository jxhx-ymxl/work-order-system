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

| 编号 | 缺口 | 现状 | 代价（谁受影响、影响成什么样） |
| --- | --- | --- | --- |
| **N1** | 工单附件 / 照片上传 | **不做（R6 已裁决）** | 报修人**仍需在微信群里补图**；P4 的"分类靠人读"只能依赖文字描述，AI 分类对"只有照片才说得清"的故障（如墙面渗水、设备烧毁）判断力下降。做它的代价是对象存储、上传鉴权、内容类型校验、孤儿文件清理与备份体积翻倍，而目标机器只有一块 2C4G 的盘 |
| **N2** | 短信 / 邮件通道 | **不做（R7 已裁决收窄）** | 只在"外部渠道群机器人 webhook（F5-6）"这一条通道上满足非登录触达；短信与邮件不做，代价是**夜间与现场作业时，没有手机短信兜底**，若群机器人凭据失效则退化为只能登录看站内信 |
| **N2-补充** | 外部渠道 webhook（F5-6） | **尚未实现（C 类，排在 P5 之后）** | 当前非登录触达能力为**零**：师傅在现场作业时不登录系统就看不到任何超时告警（站内信只在登录后可见）。实现后若无可用凭据，必须在本文件把该通道标注为**「已实现未启用」**，不得留下半成品 |
| **G1–G5** | 五处已核实的代码缺口（`RELEASED` 死路、日志越权、三个权限码不生效、接管权限层与服务层不一致、SLA 接单时限界面在骗人） | 详见 `BUSINESS-SCOPE.md` §1.6 与 `INVARIANTS.md` | 每条缺口都有承接功能编号（F1-6 / F2-4 / F3-5 / F4-1 / F5-2）与验收标准，未修复前不要对外宣称这些能力已具备 |
| **I4 / I5** | `sla_deadline` 为 NULL 的静默失效（NULL 与任何值比较均为 unknown，永不进入 SLA 扫描） | 已定稿修复方案（a-2 兜底配置 + type 枚举校验 + 启动自检 `ensureSlaConfigComplete()`），实施在 P0 | 未修复前，漏配的 `type+priority` 组合会让该类工单**永远不告警且无任何报错**。2026-09-23 实测：未完结 NULL 工单 104 行，全部为测试残留（详见 `INVARIANTS.md` §2(c)） |
| **N3** | **outbox → MQ 的消费端** | **尚未实现（P1 步骤 4）**：投递侧已闭环（outbox → 延迟交换机 → `workorder.order.release.queue`），但**没有消费者**，因此队列会持续堆积，到点释放仍由进程内 `@Scheduled` 兜底扫描完成 | 演示时必须说清"消息堆积是预期状态"：队列深度**只增不减**（无消费者），所以**不能**用"队列被消费掉"证明链路可用；正确的证据是 `t_event_outbox.status='SENT'` + 队列深度从 0 变正数。另外 `ReleaseTimeoutScheduler` 的 30 分钟仍是硬编码（F4-1 未做），所以 `accept_minutes≠30` 的类型在 MQ 与兜底两条路径上的时限**暂不一致** |

---

## 五、本地与服务器部署的环境变量清单

样例文件见 [`.env.example`](.env.example)（只含键名与占位说明，**不含真实值**）。`.env` 已被 `.gitignore` 覆盖（`.gitignore:23-24`，实测 `git check-ignore -v .env` 命中），**真实值只写在本地或服务器的 `.env` 里，永不入库**。

### 5.1 应用消费的变量（改这些才有效果）

| 变量 | 用途 | 代码里的默认值 | 是否必须外部提供 |
| --- | --- | --- | --- |
| `MYSQL_HOST` / `MYSQL_PORT` / `DB_NAME` | 后端连接 MySQL | `localhost` / `3306` / `work_order` | 否（compose 内会是服务名 `mysql`） |
| `MYSQL_USER` | MySQL 账号 | `root` | 否（compose 下由 `MYSQL_ROOT_PASSWORD` 决定后端口令） |
| ~~`MYSQL_PASSWORD`~~ | **已移除：写了不生效的死配置** | —— | **不要设**。compose 里后端的环境变量 `MYSQL_PASSWORD` 取自 `${MYSQL_ROOT_PASSWORD}`，你另设的会被覆盖；要改后端连库口令请改 `MYSQL_ROOT_PASSWORD`。**这也意味着后端是用 MySQL root 账号连库的**——演示环境的取舍，**生产应改为最小权限的专用用户**（只授予 `work_order` 库所需权限） |
| `REDIS_HOST` / `REDIS_PORT` | Redis 连接与 Sa-Token 会话 | `localhost` / `6379` | 否 |
| `LLM_API_URL` / `LLM_API_KEY` | LLM 智能分诊 | **空** | 否。两个都为空时走静默降级（`type=OTHER`、`priority=0`），不影响提交 |
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

1. 本地：`cp .env.example .env` → 填 `MYSQL_ROOT_PASSWORD`（后端连库口令也取自它；**不要**再设 `MYSQL_PASSWORD`，那是死配置）→ 按 §二 启动。
2. 服务器：在服务器上新建 `.env`（**不要从本地拷**，避免把本地口令带上去）→ 填必备值 → `docker compose up -d --build`。
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
| **A. `.env` 文件（推荐，compose 与本地都吃）** | `cp .env.example .env`，填 `MYSQL_PASSWORD=你的口令`。`.env` 已被 `.gitignore` 覆盖 | 用 Docker Compose 起全栈、或本地起后端 |
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
