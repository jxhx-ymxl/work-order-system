# PROJECT_MAP — 企业工单流转平台（work-order-system）

> 测绘时间：2026-09-22
> 测绘范围：仓库第一层目录 + `src/main/java` 包结构一层 + 根级文档清单；未逐行阅读业务实现。
> 本文只做"地图"，不修改任何既有文件；文末【六】列出测绘中发现的文档/实现不一致点，读文档时需重点核对。

---

## 一、第一层目录测绘

| 条目 | 类型 | 作用 | 状态 |
| --- | --- | --- | --- |
| `src/` | 目录 | 主体源码。`main` 下 8 个包共 91 个 Java 文件，`test` 下 13 个测试文件 | 已入库 |
| `sql/` | 目录 | 数据库脚本 4 份：`init.sql`(14KB, 9 张表建表+种子)、`data-generator.sql`、`explain-queries.sql`、`hotfix-role-permissions.sql` | 已入库 |
| `deploy/` | 目录 | 部署产物：`README.md`(7.4KB 部署指南)、`docker-compose.yml`、`docker-compose.local.yml`、`nginx.conf`、`frontend.Dockerfile` | 已入库 |
| `http/` | 目录 | 手测脚本：`text.http`、`work-order.http` | 已入库 |
| `TECHNICAL-PLAN.md` | 文件 | 39KB / 686 行，完整技术方案（DDL、状态机、并发抢单、SLA 设计） | 设计源头 |
| `RABBITMQ-MIGRATION.md` | 文件 | 22KB / 442 行，Mock 消息 → 真实 RabbitMQ 的迁移指南 | 情景文档 |
| `CONTEXT.md` | 文件 | 3KB / 51 行，领域术语表（工单/抢单/驳回/SLA/Triage/RBAC/权限码） | 必读 |
| `pom.xml` | 文件 | Spring Boot 3.3.5 / Java 17 / MyBatis-Plus 3.5.7 / Sa-Token 1.39 / Knife4j 4.5 / XXL-Job 2.4 / AMQP + Redis | 已入库 |
| `Dockerfile` | 文件 | 后端多阶段构建（maven → alpine JRE），`-Xmx256m`，阿里云镜像源 | 已入库(有未提交改动) |
| `.spec-workflow/` | 目录 | 规格工作流骨架。仅 `templates/`(6 份) 与 `user-templates/README.md` 有内容；`specs/`、`steering/`、`approvals/`、`archive/` 为空 | **未跟踪(untracked)** |
| `.claude/` | 目录 | 仅 `settings.local.json`，白名单 `mvn test *` / `mvn clean *` | 已入库 |
| `.idea/` | 目录 | IDE 配置 | 被 .gitignore 排除 |
| `target/` | 目录 | 构建产物，含 62MB fat jar | 被 .gitignore 排除 |
| `.git/` | 目录 | 版本库，当前分支 `master`，HEAD=`6713bbe` | — |

---

## 二、`src` 一层包结构

| 包 | 文件数 | 职责 |
| --- | --- | --- |
| `com.workorder` | 1 | `WorkOrderApplication` 启动类 |
| `common` | 4 | `Result` / `PageResult` / `BizException` / `ErrorCode` 统一响应与异常 |
| `common.dto` | 12 | 入参对象（提交/分配/驳回/登录/角色/SLA 配置等） |
| `common.vo` | 9 | 出参对象（工单详情/日志/通知/权限树/统计等） |
| `common.enums` | 2 | `Status`(7 态)、`OrderAction` 状态机词汇表 |
| `common.aop` | 2 | `OrderLogAspect` + `@OrderAction` 操作日志切面 |
| `config` | 6 | MyBatis-Plus / Redis / Sa-Token(`SaTokenConfig`+`StpInterfaceImpl`) / 密码 / 全局异常 |
| `controller` | 6 | 6 个 REST 控制器，共 32 个端点：WorkOrder(13)、Role(7)、Admin(6)、Notification(3)、User(2)、Login(1) |
| `service` | 10 | 服务接口层：WorkOrder / StateMachineValidator / OrderTriage / Permission / Role / User / Notification / MessagePublish / NotifyChannel 等 |
| `service.impl` | 9 | 服务实现，含 `MockMessagePublishServiceImpl`(消息为 Mock) |
| `mapper` | 9 | MyBatis-Plus Mapper 接口（XML 3 份在 `resources/mapper/`） |
| `scheduler` | 2 | `ReleaseTimeoutScheduler`(60s)、`SlaEscalationScheduler`(300s)，均为 `@Scheduled` |
| `entity` | 9 | 9 张表对应实体 |
| `utils` | 1 | `OrderNoGenerator` 工单编号生成 |

数据库表（`sql/init.sql`，9 张）：`t_work_order`、`t_work_order_log`、`t_user`、`t_role`、`t_permission`、`t_user_role`、`t_role_permission`、`t_sla_config`、`t_notification`。

运行期外部依赖（`src/main/resources/application.yml`）：MySQL 8（`work_order` 库）、Redis 7；端口 9000；RabbitMQ 与 XXL-Job 有配置项但代码未真实接入。LLM Triage 走 `${LLM_API_URL}` / `${LLM_API_KEY}`，未配置时走降级。

---

## 三、推荐阅读顺序

### 第 0 档 · 先建立词汇与边界（约 15 分钟）

1. **`CONTEXT.md`**（51 行）— 先读它。术语表定义了工单/抢单/验收/驳回/SLA/Triage 与四类角色、权限码，后面所有文档都建立在这套词汇上。
2. **`deploy/README.md`**（7.4KB）— 第二份就读它，因为它写明了**系统实际长什么样**：瘦身版 MySQL8 + Redis7 + Spring Boot + Nginx，明确"不含 RabbitMQ / XXL-Job，代码真实未接入"。先知道真实边界，读技术方案时才不会被"设计里应有的组件"带偏。

### 第 1 档 · 设计源头（按顺序读，不要跳）

3. **`TECHNICAL-PLAN.md`**（686 行）— 设计唯一源头。建议按小节分批：§1 数据库设计(DDL+索引+乐观锁) → 状态机与流转规则 → 并发抢单方案 → SLA/超时释放。不必一次读完，但 §1 必须读完。
4. **`sql/init.sql`**（14KB）— 紧接技术方案读，用来**对照** plan 里的 DDL 与真实建表/种子数据是否一致（含 RBAC 五表、SLA 配置、通知表）。
5. **`ISSUES.md`**（任务清单）— 用来判断"哪些已完工、哪些待做"。⚠️ 该文件在工作区已被删除，仓库 HEAD 版本约 81KB 较完整；仓库外归档目录 `<仓库外>/adr/ISSUES.md` 是另一份约 47KB 的**不同版本**，两者内容不等价，读之前先确认要看哪一份。

### 第 2 档 · 实现真相（读代码前先读这几份，成本低、收益高）

6. `common/enums/Status.java` + `common/enums/OrderAction.java` — 7 个状态与操作枚举，是理解全流程的最小钥匙。
7. `service/StateMachineValidator.java` → `service/impl/WorkOrderServiceImpl.java` — 状态转移校验规则 + 抢单/开始/完成/验收/驳回主流程（含乐观锁与事务边界）。
8. `common/aop/OrderLogAspect.java` — 操作日志如何被切面自动写入。
9. `scheduler/ReleaseTimeoutScheduler.java` + `scheduler/SlaEscalationScheduler.java` — 超时释放与 SLA 升级的实际兜底机制。
10. `config/SaTokenConfig.java` + `config/StpInterfaceImpl.java` — 认证与权限码加载入口，理解 RBAC 如何落到接口。
11. `service/impl/OrderTriageServiceImpl.java` — LLM 智能 triage 亮点逻辑与降级行为。

### 第 3 档 · 运行与交付（要动手改代码前再读）

12. `pom.xml` + `src/main/resources/application.yml` — 依赖清单与所有可配置外部依赖。
13. `deploy/docker-compose.yml` + `Dockerfile` + `http/*.http` — 怎么起、怎么调。
14. `RABBITMQ-MIGRATION.md` — **仅在真的要做 MQ 迁移时读**，平时读它是纯噪声。

### 明确可以先不读

`target/`、`.idea/`、`.spec-workflow/templates/`（通用模板，非本项目内容）、`docs/INTERVIEW-*`（对外素材，与工程判断无关）、`data-generator.sql` / `explain-queries.sql`（压测与调优时才需要）。

---

## 四、仓库当前状态（测绘快照，未做任何改动）

> **以下为 2026-09-22 快照，当前状态以 `git status` 为准。** 本节内容刻意不重写——保留它才能看出当时的工作区长什么样（例如 `D CLAUDE.md` 与 `?? deploy/docker-compose.server.yml` 现均已不复存在：前者已入库、后者已在 P0a 批删除）。

`git status` 显示工作区不干净，其中"删除"并非文件丢失：

```
 D CLAUDE.md
 D ISSUES.md
 D docs/INTERVIEW-RESUME.md
 D docs/PERFORMANCE-TUNING.md
 M Dockerfile
 M deploy/frontend.Dockerfile
?? .spec-workflow/
?? deploy/docker-compose.server.yml
```

- 被删除的 4 份文件在仓库外的 `<仓库外>/docs/` 与 `<仓库外>/adr/`（相对仓库根的上一级目录）有同名副本。经内容归一化比对：`CLAUDE.md`、`docs/PERFORMANCE-TUNING.md`、`docs/INTERVIEW-RESUME.md` 与仓库 HEAD 内容一致；`adr/ISSUES.md` 与 `HEAD:ISSUES.md` **内容不一致**（47KB vs 81KB），属于不同版本。
- `CLAUDE.md` 是原工程的纪律文件：定位为"资深 Java 后端架构师"、技术栈红线（Java 17 + Spring Boot 3 + MyBatis-Plus + MySQL 8 + Redis 7 + RabbitMQ 3 + XXL-Job 2.4）、禁止篡改 DDL、禁止跨 Issue 开发、TDD 铁律、交付前列"危险区"。若后续要继续本仓库的工程协作，建议先决定是否把它恢复到仓库根目录。
- 上述改动与删除**属于既有工作区状态，本测绘与本文档未做任何改动**。

---

## 五、30 秒版阅读路线

`CONTEXT.md` → `deploy/README.md` → `TECHNICAL-PLAN.md`(§1 必读) → `sql/init.sql` → `ISSUES.md`(先确认版本) → 然后进代码：`Status/OrderAction` → `StateMachineValidator` → `WorkOrderServiceImpl` → `scheduler/*` → `SaTokenConfig/StpInterfaceImpl` → `OrderTriageServiceImpl`。

---

## 六、测绘发现的三处漂移，阅读时需重点核对

1. **技术栈红线 vs 实际实现**：`CLAUDE.md` 把 RabbitMQ 3.x、XXL-Job 2.4.0 列为不可违反的技术栈红线，但代码中消息发布只有 `MockMessagePublishServiceImpl`（无 `RabbitTemplate`、无 `@RabbitListener`），定时任务全部是 `@Scheduled`（60s / 300s），无任何 `XxlJob` 注解与配置类；`deploy/README.md` 与 `RABBITMQ-MIGRATION.md` 也都承认"代码真实未接入"。`pom.xml` 里这两个依赖仍然存在，容易让人误判已接入。
2. **`ISSUES.md` 双版本**：仓库 HEAD 版本(81KB) 与仓库外 `adr\ISSUES.md`(47KB) 内容不同，用它判断进度前必须先确认权威版本。
3. **`.spec-workflow/` 未入库且主体为空**：目录骨架存在（含 6 份模板），但 `specs/`、`steering/`、`approvals/`、`archive/` 均无文件，且整体处于 untracked 状态——说明这条规格工作流尚未真正启用，不要把空目录当作已有规范。

---

## 附：本次测绘的可复现命令

```powershell
Get-ChildItem -Force -LiteralPath .                      # 在仓库根目录执行
git status --porcelain
Get-ChildItem -Recurse -LiteralPath .\src -File
Select-String -LiteralPath .\sql\init.sql -Pattern 'CREATE TABLE'
Select-String -LiteralPath .\src\main\java\com\workorder\scheduler\*.java -Pattern '@Scheduled|XxlJob'
```
