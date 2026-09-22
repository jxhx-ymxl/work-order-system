# 业务基线（BUSINESS-SCOPE.md）

> 版本 v1 · 2026-09-22 · 业务范围的唯一权威来源
> 上游：本文件是 `CLAUDE.md` 所定义文档权威顺序的第一位；与 `ASYNC-SCHEDULING-PLAN.md`（技术方案）、`TECHNICAL-PLAN.md`（原始设计）冲突时以本文件为准，冲突必须上报
> **范围边界（S5）**：本文件只定义用户可见功能。技术性后台任务——归档、outbox 投递、重试重放、消息清理——由 `ASYNC-SCHEDULING-PLAN.md` 管理，不在本文件范围内。**这些任务不属于"范围外可砍"的对象**，它们的存在由技术方案负责论证，本文件不重复论证，也不得据此判定其可删。
> 证据来源（全部为逐行核对）：`CONTEXT.md`、`sql/init.sql`、`TECHNICAL-PLAN.md`、`src/main/java/com/workorder/controller/` 全部 6 个控制器、`common/enums/Status.java`、`common/enums/OrderAction.java`、`service/StateMachineValidator.java`、`service/impl/WorkOrderServiceImpl.java`、`scheduler/` 两个调度器、`config/SaTokenConfig.java`、`config/StpInterfaceImpl.java`、`frontend/src/router/index.ts` 与 `components/order/OrderActions.vue`（前端已并入单仓，原路径为 `work-order-frontend/`）
> 本轮边界：只做文档修订（本文件、`INVARIANTS.md`、`ASYNC-SCHEDULING-PLAN.md` 的最小同步），不修改任何代码、不新增表或字段

---

## 0. 标注约定

全文用三种标记，不允许模糊表述：

| 标记 | 含义 |
| --- | --- |
| 【实现】 | 代码里真实存在且可运行，已核对到文件与行 |
| 【文档】 | 只在 `TECHNICAL-PLAN.md` / `CONTEXT.md` 里有描述，代码未实现。**不得当作已实现** |
| 【缺口】 | 代码存在但前后不一致：端点无前端入口、权限码未生效、状态无出口等 |

---

## 一、反推现状

### 1.1 七个状态

定义位置：`src/main/java/com/workorder/common/enums/Status.java:5-11`（7 个枚举值）。

| 状态 | 中文（前端 `types/order.ts` STATUS_MAP） | 业务含义 | 是否终态 |
| --- | --- | --- | --- |
| `PENDING` | 待分配 | 已提交，等待被抢单或被指派 | 否 |
| `ACCEPTED` | 已接单 | 已有处理人，但还没开始处理 | 否 |
| `IN_PROGRESS` | 处理中 | 处理人已开始处理 | 否 |
| `AWAIT_APPROVAL` | 待验收 | 处理人提交验收，等提交人确认 | 否 |
| `CLOSED` | 已关闭 | 验收通过或被管理员强制关闭 | **是** |
| `RELEASED` | 已释放 | 接单后 30 分钟未开始处理，被系统回收 | **是** |
| `ESCALATED_ADMIN` | 已升级 | 驳回次数达上限，升级给管理员 | 否（可被接管或强制关闭） |

终态判定依据：`StateMachineValidator.ALLOWED` 中没有 `CLOSED` 与 `RELEASED` 的条目（`StateMachineValidator.java:15-20`），前端 `TERMINAL_STATUSES` 也把 `ESCALATED_ADMIN` 一并列为不可操作（`frontend/src/types/order.ts`）。

### 1.2 允许的状态转移（全部【实现】）

动作枚举共 9 个：`ACCEPT / START / COMPLETE / APPROVE / REJECT / ASSIGN / RELEASE / MANAGE / CLOSE`（`common/enums/OrderAction.java:5-15`）。

| 当前状态 | 动作 | 目标状态 | 触发方式 | 接口 | 权限码 | 服务层身份约束 | 标记 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `PENDING` | ACCEPT | `ACCEPTED` | HTTP | `POST /api/orders/{id}/accept`（WorkOrderController.java:75-78） | `order:accept` | 无（仅校验状态与并发） | 【实现】 |
| `PENDING` | ASSIGN | `ACCEPTED` | HTTP | `POST /api/orders/{id}/assign`（:121-124） | `order:assign` | 无（仅校验被指派人存在且启用） | 【实现】 |
| `ACCEPTED` | START | `IN_PROGRESS` | HTTP | `POST /api/orders/{id}/start`（:84-86） | **无权限码**（仅类级 `@SaCheckLogin`，:32） | 必须是当前处理人（`WorkOrderServiceImpl.java:156-165`） | 【实现】【缺口】 |
| `ACCEPTED` | RELEASE | `RELEASED` | 定时任务 | **无 HTTP 端点**，`ReleaseTimeoutScheduler.java:24-26` | 无 | 系统触发，操作人记为 0 | 【实现】 |
| `IN_PROGRESS` | COMPLETE | `AWAIT_APPROVAL` | HTTP | `POST /api/orders/{id}/complete`（:92-94） | **无权限码** | 必须是当前处理人（:180-190） | 【实现】【缺口】 |
| `AWAIT_APPROVAL` | APPROVE | `CLOSED` | HTTP | `POST /api/orders/{id}/approve`（:100-102） | **无权限码** | 必须是提交人（:202-212） | 【实现】【缺口】 |
| `AWAIT_APPROVAL` | REJECT | `IN_PROGRESS` 或 `ESCALATED_ADMIN` | HTTP | `POST /api/orders/{id}/reject`（:108-111） | `order:reject` | 必须是提交人；`reject_count+1 >= max_reject(3)` 时升级（:224-247） | 【实现】 |
| `ESCALATED_ADMIN` | MANAGE | `IN_PROGRESS` | HTTP | `POST /api/orders/{id}/manage`（:131-134） | `order:manage` | 角色 ∈ {HANDLER, DEPT_ADMIN, SYS_ADMIN}（:326-345） | 【实现】【缺口】 |
| `ESCALATED_ADMIN` | CLOSE | `CLOSED` | HTTP | `POST /api/orders/{id}/close-escalated`（:140-143） | `order:manage` | 必须 SYS_ADMIN（:352-360） | 【实现】 |
| `CLOSED` | — | 无可用动作 | — | — | — | — | 终态 |
| `RELEASED` | — | 无可用动作 | — | — | — | — | 终态 |

补充两条实现事实：

- `SUBMIT` 不在状态机里：`submitOrder` 直接插入 `PENDING` 行，不经过 `StateMachineValidator`；`SUBMIT` 只作为操作日志的 action 出现（`WorkOrderServiceImpl.java:73-118`）。
- 驳回两步走：先 `GET /api/orders/{id}/action-token` 取 Token（需 `order:reject`），再 `POST .../reject` 带 Token 消费（Redis Lua 原子删除，:117、:404-427）。

### 1.3 四类角色：能看到什么、能操作什么

角色的权限码来自种子数据 `sql/init.sql`（SYS_ADMIN 全量；SUBMITTER 只有 `order:reject`；HANDLER 只有 `order:accept`；DEPT_ADMIN 有 `order:accept`、`order:assign`、`order:stats`、`order:manage`）。权限码在每次请求时通过 `StpInterfaceImpl` 实时查库计算（无缓存）。

**列表可见范围**（`WorkOrderServiceImpl.listOrders`，按角色拼 OR 条件）：

| 角色 | 列表能看到 |
| --- | --- |
| SYS_ADMIN | 全部工单（跳过所有过滤） |
| SUBMITTER | 自己提交的 |
| HANDLER | 自己接单的 + 待分配池（`PENDING` 且 `assignee_id IS NULL`） |
| DEPT_ADMIN | 本部门用户提交的（按 `submitter_id IN (同部门用户)`） |
| 无上述角色 | 退化为"自己提交的" |

**详情可见范围**（`canViewDetail`）：SYS_ADMIN 全部；`ESCALATED_ADMIN` 状态仅 DEPT_ADMIN 与 SYS_ADMIN 可见；SUBMITTER 看自己提交的；HANDLER 看自己接的 + 待分配池；DEPT_ADMIN 看本部门提交的。

**统计可见范围**（`AdminController.orderStats`）：接口要求 `order:stats`，`scope=ALL` 额外要求 `order:stats:all`。因此 SUBMITTER 与 HANDLER **完全无法调用统计接口**，DEPT_ADMIN 只能看部门口径，SYS_ADMIN 两者皆可。

### 1.4 文档有、代码没有（漂移清单，逐条核对）

| 文档描述 | 出处 | 代码实际 | 标记 |
| --- | --- | --- | --- |
| RabbitMQ 延迟消息作为超时释放主路径 | `TECHNICAL-PLAN.md` §3.3（第 305-370 行） | 只有 `MockMessagePublishServiceImpl` 打日志；无 `RabbitTemplate`、无 `@RabbitListener`、无 `RabbitMQConfig` | 【文档】 |
| `@XxlJob("releaseTimeoutFallback")`、`@XxlJob("slaEscalationCheck")` | `TECHNICAL-PLAN.md` 第 352、375 行 | 实际是 `@Scheduled(fixedRate=60_000)` 与 `@Scheduled(fixedRate=300_000)`；全仓库无 `XxlJob` 注解、无 xxl-job 配置类（仅 `pom.xml` 依赖与 `application.yml` 配置项） | 【文档】 |
| publisher confirm + 消费方手动 ACK | `TECHNICAL-PLAN.md` §3.3、`RABBITMQ-MIGRATION.md` | 无生产者确认回调、无消费者、`acknowledge-mode: manual` 是空配置 | 【文档】 |
| 定时任务用 Redis SETNX 分布式锁防多节点重复 | `TECHNICAL-PLAN.md` Q5（第 728 行） | 只有一个"通知去重" `SETNX sla_notified:{orderId}`，没有任何分布式锁 | 【文档】 |
| 前端方案为 Swagger/Knife4j + Postman，"不为界面拖累后端精力" | `TECHNICAL-PLAN.md` §八（第 684-712 行） | 实际存在完整 Vue3 前端（12 个页面、5 个 API 模块、4 个 store） | 【文档】 |
| `CONTEXT.md` 举例权限码 `order:admin:stats` | `CONTEXT.md` 术语"权限码" | 真实权限码是 `order:stats` 与 `order:stats:all`，不存在 `order:admin:stats` | 【文档】 |
| `accept_minutes` 作为"接单超时" SLA 参数 | `TECHNICAL-PLAN.md` §1.6、`sql/init.sql`（8 条配置均有该字段） | 该字段**只被写入、从未被消费**：唯一的写入口是 `AdminController.java:91`（`config.setAcceptMinutes(...)`），全仓库没有一处业务逻辑读它。`submitOrder` 只用 `finish_minutes` 算 `sla_deadline`（`WorkOrderServiceImpl.java:94-96`），超时释放用的 30 分钟是硬编码常量（`ReleaseTimeoutScheduler.java:26`、`WorkOrderServiceImpl.java:145`、:311） | 【文档】 |
| 附件上传 | `TECHNICAL-PLAN.md` Q17（第 763-765 行）明确写"没实现" | 与文档一致，确认未实现 | 【文档】 |

### 1.5 代码有、文档没有或与文档不同（反向漂移）

| 代码事实 | 位置 | 文档状态 | 标记 |
| --- | --- | --- | --- |
| AI Triage 只在 `type` 或 `priority` 为空时触发，失败回退 `OTHER`/普通 | `WorkOrderServiceImpl.java:615-622`、`TriageResult.fallback()` | `CONTEXT.md` 描述了 Triage，未说明触发条件 | 【实现】 |
| 创建工单时**不产生任何通知**，通知只出现在"驳回达上限"与"SLA 超时"两处 | `WorkOrderServiceImpl.java:249`、`SlaEscalationScheduler.java:67` | 文档未描述通知触发点矩阵 | 【实现】 |
| 驳回上限升级与 SLA 超时共用同一个 `sendSlaEscalation(orderId)` 方法名 | `MessagePublishService.java` | `RABBITMQ-MIGRATION.md` 将其当作一类事件 | 【实现】 |
| 升级工单有 `manage` / `close-escalated` 两个端点 | `WorkOrderController.java:131-143` | `TECHNICAL-PLAN.md` 未记录这两个端点 | 【实现】 |

### 1.6 现状中的五个硬缺口（不是漂移，是缺陷）

| 编号 | 缺口 | 证据 | 后果 | 承接功能（§3） |
| --- | --- | --- | --- | --- |
| G1 | `RELEASED` 是死路：既不接受任何动作，也不回到 `PENDING` | `StateMachineValidator.java:15-20` 无 `RELEASED` 条目；`grabOrder` 的 `WHERE` 要求 `status='PENDING'` | 被超时释放的工单**永久无法再被处理**，只能躺在列表里 | F5-2 |
| G2 | 操作日志接口无越权校验 | `WorkOrderController.java:60-63` 直接 `queryLogs(id)`，无任何归属校验（对比 `detail` 有 `canViewDetail`） | 任意登录用户拿工单 ID 即可读到他人工单的完整操作日志 | **F1-6（H3 新增）** |
| G3 | `order:start` / `order:complete` / `order:approve` 三个权限码"有定义、有授权、不生效" | 三个权限码在 `sql/init.sql` 中存在，但对应端点只有类级 `@SaCheckLogin`（:84-86、:92-94、:100-102） | 权限体系出现"看起来有权限控制、实际没有"的假象；按权限码审计会得出错误结论 | **F2-4（H3 新增）** |
| G4 | `order:manage` 与角色白名单不一致 | 权限码只授予 DEPT_ADMIN 与 SYS_ADMIN（`sql/init.sql`），但服务层允许列表含 HANDLER（`WorkOrderServiceImpl.java:319-321`） | HANDLER 在拦截器就被拦下，服务层的"处理人可接管"永远走不到 | **F3-5（H3 新增）** |
| G5 | SLA 配置页的"接单时限"可编辑但完全不生效 | 前端 `SlaConfigView.vue:24,36,73,139,147` 会读取、校验并提交 `acceptMinutes`，`AdminController.java:91` 会保存成功，但没有任何业务逻辑消费该字段（§1.4 已核对全仓库引用） | 管理员以为把"接单时限"从 30 分钟改成 10 分钟就生效了，实际释放逻辑仍按硬编码 30 分钟执行——**配置界面在骗人**，这是比"功能没做"更糟的状态 | F4-1 |

**五条缺口现在都有可验收的承接条目**（G1→F5-2、G2→F1-6、G3→F2-4、G4→F3-5、G5→F4-1）。此前 G2/G3/G4 只记录在缺陷表而没有功能条目，改造做完三个缺陷仍在，H3 已补齐。

---

## 二、场景定稿

### 2.1 定位

**系统名称**：东湖校区后勤与 IT 报修平台（原定位"模拟企业内部工单"作废）。

**服务对象**：临江理工大学东湖校区的师生报修与后勤/信息化派工。

**边界**：本系统管"报修受理 → 派工 → 处理 → 验收"的完整闭环与时效统计。**不管**资产台账、备件库存、师傅排班、财务结算——这四件事分别属于资产系统、仓储系统、人事排班与财务系统。

### 2.2 谁在用

规模与 `ASYNC-SCHEDULING-PLAN.md` §1.1 的容量假设一致（2 万师生、日均 300–800 单、峰值 5 单/分钟），两份文档不允许出现两套量级。

| 角色（系统 role_code） | 真实岗位 | 人数 | 每天在系统里做什么 |
| --- | --- | --- | --- |
| 提交人（SUBMITTER） | 东湖校区师生（含教职工） | 约 20,000 | 提交报修、查进度、验收或驳回 |
| 处理人（HANDLER） | 后勤维修班组 12 人 + 信息化中心值班工程师 6 人 | 18（白班在岗约 8） | 抢单、开始处理、提交验收 |
| 部门主管（DEPT_ADMIN） | 后勤保障处维修科 1 人 + 信息化中心运行组 1 人 | 2 | 看本部门工单、手工派单、接管升级单 |
| 系统管理员（SYS_ADMIN） | 信息化中心系统管理员 | 1 | 维护 SLA 规则、用户与角色、看全局统计 |

### 2.3 现在怎么做（改造前的真实流程：电话 + 微信群 + Excel）

1. **报修**：打后勤值班室电话，或在"东湖后勤报修群"（约 380 人）发消息 + 照片；网络类问题另在"网络报修群"。
2. **登记**：值班员一人盯两个群，把消息手工抄进 Excel《东湖报修台账》，单号手写（形如 `5.12-17`，无跨月唯一性）。
3. **派工**：值班员看谁"在线"，打电话口头派给某位师傅，口述地点和故障。
4. **处理**：师傅到现场处理，完成后在群里回一句"修好了"或 @ 报修人。
5. **关单**：值班员看到群里回复，手工把 Excel 对应行标黄。
6. **统计**：每月由值班员翻 Excel 汇总，主管通常等 2–3 天才拿到上月数据。

### 2.4 六条痛点

每条痛点都给出"现象 → 现状处理 → 代价"，第 2.5 节给出与功能的一一对应。

| 编号 | 痛点 | 现象与现状处理 | 代价（可量化） |
| --- | --- | --- | --- |
| **P1** | 无台账、进度不可查 | 状态只存在于值班员大脑和微信群；报修人只能再打电话问 | 值班室日均接到 60+ 通"修到哪了"的进度咨询电话，占值班员约 1/3 工时 |
| **P2** | 派工看人不看量 | 值班员凭印象指派，手里没有可即时查看的工作量依据 | 差异只在月底人工汇总 Excel 时才暴露（12 名师傅月工单量最大差 3 倍），**等看到时已过去一个月，无法在周期内干预**（S1：原表述"无法量化"与"已看出 3 倍差异"自相矛盾，已改为时效视角） |
| **P3** | 超时无人管，紧急与普通无差别 | 群里消息会被新消息顶掉，沉底的单只能等报修人再催 | 沉底工单平均 2 天后才被发现；宿舍停水停电这类紧急单与普通单同等待遇 |
| **P4** | 分类靠人逐条读 | 值班员要人工判断工单类型和紧急度 | 开学季峰值 5 单/分钟，值班员同时接电话+看群，漏判紧急单是常态 |
| **P5** | 处理结果无人验收 | "修好了"是口头承诺，没有确认环节 | 同一问题一周内被重复报修 3 次时，无法证明前一次是否真的处理过 |
| **P6** | 责任争议无据可查 | 报修人说过什么、师傅改了什么、为什么退回，全靠翻微信群 | 争议平均处理时间按天计；群消息无法作为管理依据 |

### 2.5 痛点 ↔ 功能 一一对应

| 痛点 | 支撑功能（§3 编号） | 覆盖是否完整 |
| --- | --- | --- |
| P1 无台账、进度不可查 | F1-1 提交工单、F1-2 我的工单列表与详情、F1-5 操作日志时间线 | 完整 |
| P2 派工看人不看量 | F2-1 抢单、F3-2 手工派单、F3-4 部门统计看板、**F5-5 按处理人工作量统计（需新增）** | **部分**：现有统计只按"状态"分组，不按处理人分组；P2 的诉求是"在周期内就能看到差异"，F5-5 把它从"月底人工汇总"提前到"任意时间范围即时可查"，落地后才完整（S1 改的是表述，本条对应关系不变） |
| P3 超时无人管 | F4-1 SLA 规则维护、F5-2 超时自动释放、F5-3 SLA 超时升级与通知、F3-3 接管/关闭升级单 | 完整 |
| P4 分类靠人读 | F1-4 AI 自动分类（**需改造**，见下） | **部分**：当前前端强制必选类型，AI 只在 API 直连且不带 type 时触发，因此线上等于未生效 |
| P5 处理结果无人验收 | F2-3 提交验收、F1-3 验收通过/验收驳回（含驳回上限升级） | 完整 |
| P6 责任争议无据可查 | F1-5 操作日志时间线、F1-3 驳回必须填理由、F5-1 站内信留痕 | 完整 |

### 2.6 孤儿清单与裁决结果

**A. 有功能、无痛点支撑**——五条均已于 2026-09-23 裁决，结果如下：

| 编号 | 功能 | 代码位置 | 裁决 | 裁决内容、理由与代价 | 落地编号 |
| --- | --- | --- | --- | --- | --- |
| O1 | 角色 CRUD + 权限树分配 | `RoleController.java:38-90`、`RoleManageView.vue` | **保留（R1）** | 保留的理由不是"以后可能用"，而是权限调整不必发版；代价是配错权限的风险，用"内置角色不可删除"收口。**代码中已有该保护**：`RoleServiceImpl.java:29` 的 `PROTECTED_ROLE_CODES = {SUBMITTER, HANDLER, DEPT_ADMIN, SYS_ADMIN}`，在 `:72` 拦截删除 | F4-4 |
| O2 | 开放注册 `/api/users/register` | `UserController.java:28-32`、`RegisterView.vue`、`LoginView.vue:93` | **改为配置开关，默认关闭（R2）** | 高校应走统一身份认证，开放注册使任何校外人员都能注册并报修，与 P6（可追溯）冲突。保留代码但默认不可用；**连带影响**：前端 `/register` 路由与登录页"没有账号？去注册"入口在开关关闭时不得再引导用户走进去 | F4-3 配套项 |
| O3 | 按用户名查询任意用户的角色与权限码 | `GET /api/users/{username}`（`UserController.java:22-26`） | **收窄为仅本人可查（R3）** | **原判断有误，此处更正**：该接口不是"无业务入口调用的调试接口"——前端 `src/api/user.ts:16` 的 `getByUsername` 确实在调用它，被 `stores/auth.ts:130,174` 用于登录后同步本人的 `deptId/roles/permCodes`。因此不能删除，收窄为仅本人 | F1-2 配套项 |
| O4 | 工单类型 `LEAVE`（请假）/ `REIMBURSE`（报销） | `sql/init.sql` 类型枚举与现存 8 行 SLA 配置 | **替换为报修四类（R4）** | 新枚举：`NETWORK` 网络故障 / `UTILITY` 水电故障 / `DORM` 宿舍与公区维修 / `OTHER` 其他（兜底）；优先级沿用普通/紧急。**R4 改动范围 = 8 行配置数据（替换类型集合，行数不变）+ 2 处列注释**（`sql/init.sql:15` 与 `:131-132`）——上一版写的"4 条配置替换为 8 条"有误：表里本来就是 8 行（4 类 × 2 优先级），已按实测更正。**连带必须处理（本轮新发现）**：① `sql/data-generator.sql:73` 仍在用旧类型集合，不同步会让造数脚本产出配置表覆盖不到的类型；② 存量工单已有旧类型值（实测 `REPAIR` 109 条、`REIMBURSE` 1 条等），替换枚举后 I5 探针 P5 会立即变红，必须同时处置存量类型值。**`OTHER + 普通` 必须存在**——缺失会让 `sla_deadline` 落为 NULL，而 NULL 永远不会被 SLA 扫描命中（真实缺口，见 `INVARIANTS.md` 的 I4/I5） | F1-1、F4-1 |
| O5 | 权限码 `order:start` / `order:complete` / `order:approve` | `sql/init.sql` 定义并授权 | **启用，且必须按顺序执行（R5）** | 端点加 `@SaCheckPermission` 与补齐角色授权必须同时做：**只加注解不补授权，HANDLER 会在 `/start` 被拦下，主流程当场锁死**。授权表见 F2-4 | F2-4 |

**B. 有痛点、无功能支撑**：**无**。六条痛点都有对应功能，其中 P2 与 P4 为部分支撑，已在 2.5 标注。

**C. 原 C 冲突表的裁决结果**：

| 冲突 | 裁决 | 依据与代价 |
| --- | --- | --- |
| 工单附件（照片） | **不做（R6）** | 见 §4 的 N1 行：代价是报修人仍需在微信群补图，P4 只能靠文字描述分类 |
| 报表导出 | **不做文件导出；保留"定时聚合落表"（S6）** | 导出 PDF/Excel 的代价见 §4 的 N10 行；但跨月区间的聚合是实时接口做不到的，因此保留定时聚合结果表，两处口径已在 §4 N10 与 `ASYNC-SCHEDULING-PLAN.md` P6 对齐 |

**D. R2 / R3 的验收标准**（这两项是对既有功能的收口，不新增功能条目，故在此列出）：

| 编号 | 验收标准（WHEN…THEN…SHALL…） |
| --- | --- |
| R2 | WHEN 注册开关为关闭 THEN 调用 `/api/users/register` SHALL 返回拒绝，且 SHALL NOT 创建用户<br>WHEN 开关为开启 THEN 注册 SHALL 恢复现有行为 |
| R3 | WHEN 已登录用户查询他人的 `GET /api/users/{username}` THEN 系统 SHALL 拒绝<br>WHEN 用户查询自己的用户名 THEN 系统 SHALL 返回含 `roles`/`permCodes`/`deptId` 的本人信息（前端 `stores/auth.ts` 的同步逻辑依赖此行为） |

---

## 三、功能清单

编号规则：`F角色-序号`。类别：**A** 已实现 / **B** 需改造 / **C** 需新增。中间件栏只列本功能实际依赖的能力（无 / Redis / MQ / 调度）。

### 3.1 提交人（SUBMITTER）

| 编号 | 功能 | 类别 | 用户故事 | 验收标准（WHEN…THEN…SHALL…） | 前端 | 中间件 | 演示动线 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| F1-1 | 提交工单 | A + **B（R4 类型枚举替换）** | As a 师生报修人 / I want 只填标题和内容就能提交一条报修 / so that 我不必打电话或被转接 | WHEN 提交人提交标题与内容 THEN 系统 SHALL 生成 `WO-YYYYMMDD-XXXXX` 编号并以 `PENDING` 落库<br>WHEN 落库成功 THEN 系统 SHALL 同事务按 `type+priority` 写 `sla_deadline` 并写入一条 `SUBMIT` 日志<br>**情形一（非法类型，入口拒绝）** WHEN 提交的 `type` 不在 {`NETWORK`,`UTILITY`,`DORM`,`OTHER`} 内 THEN 系统 SHALL 拒绝提交且 SHALL NOT 落库（R4）<br>**情形二（合法类型但漏配，走兜底）** WHEN `type` 合法但 `type+priority` 在 `t_sla_config` 中无对应行 THEN 系统 SHALL 按兜底配置（`OTHER` + 普通）计算 `sla_deadline` 并落库，同时 SHALL 增加一次「兜底触发计数」（I4 选定 a-2） | 有（`OrderCreateView.vue`，类型选项需随 R4 替换为报修四类） | 无 | 是 |
| F1-2 | 我的工单列表与详情 | A | As a 提交人 / I want 看到我提过的工单及其当前状态 / so that 我不用再打电话问进度 | WHEN 提交人打开列表 THEN 系统 SHALL 只返回 `submitter_id=本人` 的工单<br>WHEN 提交人请求不属于自己的工单详情 THEN 系统 SHALL 返回 403 | 有（`OrderListView.vue`、`OrderDetailView.vue`） | 无 | 是 |
| F1-3 | 验收通过 / 验收驳回 | A | As a 提交人 / I want 在师傅提交验收后确认修好、或带理由退回 / so that "修好了"从口头承诺变成有记录的确认 | WHEN 提交人对 `AWAIT_APPROVAL` 工单验收通过 THEN 系统 SHALL 置 `CLOSED` 并写入 `APPROVE` 日志<br>WHEN 驳回且 `reject_count+1 < max_reject(3)` THEN 系统 SHALL 回退到 `IN_PROGRESS` 且 `reject_count` 加 1；WHEN 达到上限 THEN 系统 SHALL 置 `ESCALATED_ADMIN` 并通知 SYS_ADMIN<br>WHEN 驳回 THEN 系统 SHALL 校验一次性 Token，同一 Token 重复使用 SHALL 被拒绝 | 有（`OrderActions.vue`、`RejectDialog.vue`） | Redis（驳回 Token） | 是 |
| F1-4 | AI 自动分类与优先级 | **B** | As a 提交人 / I want 只写标题和内容，类型与优先级由系统判断 / so that 高峰期我不必逐项选、也不会因选错类型拿到错误的处理时限 | WHEN 未提供 `type` 或 `priority` THEN 系统 SHALL 调用 triage 取建议值并落库，且提交响应 SHALL NOT 因该调用阻塞<br>WHEN triage 不可用或返回非法值 THEN 系统 SHALL 使用兜底值（`OTHER`/普通）落库且提交 SHALL 成功<br>WHEN 异步结果写回且类型或优先级发生变化 THEN 系统 SHALL 按下方 H4 规则重算 `sla_deadline`、写一条修正日志，且 SHALL NOT 覆盖用户手工填写的值 | 有但**当前禁用该能力**：`OrderCreateView.vue` 的 `type` 为必填，前端永远会传入类型，triage 永不触发 | MQ（复用 F5-4） | 是 |
| F1-5 | 操作日志时间线 | A + **B（G2 越权读取）** | As a 提交人 / I want 看到每一步是谁在什么时候做的 / so that 出问题时能对质 | WHEN 打开工单详情 THEN 系统 SHALL 按时间正序展示全部日志（操作人、动作、前后状态、备注）<br>WHEN 无可查看权限的用户请求日志 THEN 系统 SHALL 拒绝（**当前违反，见 G2，由 F1-6 承接**） | 有（`OrderLogTimeline.vue`） | 无 | 是 |
| F1-6 | 操作日志访问控制（承接 G2，H3 新增） | **B** | As a 任何登录用户 / I want 日志接口与详情接口同级鉴权 / so that 我提报的工单细节不会被无关人员翻看 | WHEN 无权用户读取他人工单日志 THEN 系统 SHALL 返回 403<br>WHEN 有权用户（提交人本人 / 当前处理人 / 本部门主管 / SYS_ADMIN）读取 THEN 系统 SHALL 返回日志列表<br>WHEN 校验失败 THEN 系统 SHALL NOT 泄露该工单是否存在 | 有（复用详情页，无需新页面） | 无 | 否 |

#### F1-4 的 SLA 重算规则（H4，已定稿：a-1 + b-1）

原文"按新 `type+priority` 重算 `sla_deadline`"没有定义公式，不可验证。**2026-09-23 定稿：采用 a-1（以 `created_at` 为基准）+ b-1（立即触发告警）。** 下表保留另一选项及其代价，便于日后回看当时为什么这么选。

**a) 重算基准**

| 选项 | 规则 | 理由 | 代价 |
| --- | --- | --- | --- |
| a-1 以创建时刻为基准 | `sla_deadline = created_at + 新 finish_minutes` | 与现有实现（`submitOrder` 用 `LocalDateTime.now()` 算）语义一致，只把基准从"提交瞬间"固定为 `created_at`；SLA 的语义是"从报修那一刻起算"，与人何时分类无关 | 分类耗时会直接吃掉 SLA 余量，用户提交后等待分类的时间被计入时限 |
| a-2 以分类完成时刻为基准（**未采用**） | `sla_deadline = 分类完成时刻 + 新 finish_minutes` | 对用户更宽松，师傅的处理时间更充裕 | 分类越慢时限越长，会激励"晚分类"；异步链路抖动会静默放宽 SLA |

**b) 重算结果已过期时的行为**（例：兜底值给 24 小时、AI 判定紧急要求 2 小时、工单已提交 3 小时）

| 选项 | 规则 | 理由 | 代价 |
| --- | --- | --- | --- |
| b-1 立即触发告警（**已采用**） | 重算后若 `sla_deadline < now`，SHALL 立刻产生一次 SLA 超时告警（不等下一个扫描周期） | 超时是事实，隐瞒比告警更糟；且能暴露"分类太慢"这个系统问题 | 分类抖动会造成告警噪音；需要额外的"立即触发"通路（复用 F5-3 的事件，不新增机制） |
| b-2 顺延到下限（**未采用**） | 重算后若已过期，SHALL 顺延为 `分类完成时刻 + 最小宽限期`（如 30 分钟） | 避免"刚分类完就超时"的观感 | 掩盖了真实超时；宽限期取值无业务依据，属于人为制造的数字 |

**定稿补充（b-1 与 H1 的衔接）**：b-1 立即触发的这次告警 **SHALL 计为 H1 的「首次告警」**，后续每满 24 小时的催办节奏**自该时刻起算**（即 `eventVersion` 从此记为 `v1`，+24h 记 `v2`，依此类推），不得因为是"重算后补发"而另起一套计时。

**与 F1-1 情形一的关系（不冲突，已确认）**：F1-1 情形一校验的是**用户提交的 `type`**（入口拒绝非法枚举值）；F1-4 的兜底规则处理的是**AI 返回值非法**（系统内部产生的值），两者作用于不同来源、顺序上也不同（先校验入口、再处理 AI 结果）。AI 返回非法值时落到 `OTHER`，`OTHER` 本身是合法枚举值，因此不会触发 F1-1 情形一的拒绝；若此时 `OTHER + 该优先级` 恰好漏配，则走 F1-1 情形二的兜底（`OTHER` + 普通），二者可叠加且不矛盾。

### 3.2 处理人（HANDLER）

| 编号 | 功能 | 类别 | 用户故事 | 验收标准（WHEN…THEN…SHALL…） | 前端 | 中间件 | 演示动线 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| F2-1 | 抢单 | A | As a 处理人 / I want 在待分配池里先到先得地接单 / so that 我不必等值班员打电话派工 | WHEN 多个处理人同时抢同一张 `PENDING` 工单 THEN 系统 SHALL 只允许一人成功，其余 SHALL 收到"工单已被抢走"<br>WHEN 抢单成功 THEN 系统 SHALL 写入 `assignee_id`、置 `ACCEPTED` 并写入 `ACCEPT` 日志 | 有（`OrderActions.vue`） | Redis（接单超时标记） | 是 |
| F2-2 | 开始处理 | A | As a 处理人 / I want 到场后标记开始处理 / so that 我的处理时长有依据、工单也不会被超时回收 | WHEN 非当前处理人调用开始处理 THEN 系统 SHALL 返回 403<br>WHEN 开始处理成功 THEN 系统 SHALL 置 `IN_PROGRESS` 并清除接单超时标记，该单 SHALL 不再被超时释放 | 有 | Redis | 是 |
| F2-3 | 提交验收 | A | As a 处理人 / I want 处理完成后提交验收 / so that 由报修人确认结果，而不是我自己说修好了 | WHEN 处理人对 `IN_PROGRESS` 工单提交验收 THEN 系统 SHALL 置 `AWAIT_APPROVAL`<br>WHEN 提交成功 THEN 该工单 SHALL 出现在提交人的待验收视图中 | 有 | 无 | 是 |
| F2-4 | 状态流转权限码启用（承接 G3，H3 新增） | **B** | As a 系统管理员 / I want 这三个权限码真正生效 / so that 权限清单与实际拦截一致、审计结论可信 | WHEN 具备 `order:start` 的处理人调用开始处理 THEN 系统 SHALL 允许<br>WHEN 提交人调用开始处理 THEN 系统 SHALL 返回 403<br>WHEN 拥有角色却缺少对应权限码 THEN 系统 SHALL 返回 403 且 SHALL NOT 放行（R5：注解与授权必须同时到位） | 无（后端拦截，前端无需改动） | 无 | 否 |

**F2-4 的授权表（R5）**——加注解的同时必须补齐这些角色授权，否则主流程会被锁死：

| 权限码 | 端点 | 授予角色 |
| --- | --- | --- |
| `order:start` | `POST /api/orders/{id}/start` | HANDLER、DEPT_ADMIN、SYS_ADMIN |
| `order:complete` | `POST /api/orders/{id}/complete` | HANDLER、DEPT_ADMIN、SYS_ADMIN |
| `order:approve` | `POST /api/orders/{id}/approve` | SUBMITTER、DEPT_ADMIN、SYS_ADMIN |

依据 §5 权限矩阵：抢单对主管与超管开放（`order:accept` 已授予两者），故他们也能开始处理与提交验收；验收通过是提交人的动作，故 SUBMITTER 必须有 `order:approve`。

### 3.3 部门主管（DEPT_ADMIN）

| 编号 | 功能 | 类别 | 用户故事 | 验收标准（WHEN…THEN…SHALL…） | 前端 | 中间件 | 演示动线 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| F3-1 | 本部门工单查看 | A | As a 主管 / I want 看到本部门全部工单 / so that 我能发现积压与异常 | WHEN 主管打开工单列表 THEN 系统 SHALL 返回本部门用户提交的工单<br>WHEN 主管查看 `ESCALATED_ADMIN` 工单详情 THEN 系统 SHALL 允许访问 | 有 | 无 | 是（只读） |
| F3-2 | 手工派单 | A | As a 主管 / I want 把工单直接指派给指定师傅 / so that 复杂或紧急的单不必等抢单 | WHEN 对 `PENDING` 工单指派有效且启用的用户 THEN 系统 SHALL 置 `ACCEPTED` 并写入 `assignee_id`<br>WHEN 指派对象不存在或已禁用 THEN 系统 SHALL 返回 400 且 SHALL NOT 改变工单状态 | 有 | Redis（接单超时标记） | 否 |
| F3-3 | 接管 / 强制关闭升级单 | **B** | As a 主管或超管 / I want 接管升级单或强制关闭 / so that 驳回循环不会让工单永久卡住 | WHEN 具备 `order:manage` 的主管接管 `ESCALATED_ADMIN` 工单 THEN 系统 SHALL 置 `IN_PROGRESS` 且接管人=操作人<br>WHEN 两人同时接管 THEN 系统 SHALL 只允许一人成功<br>WHEN SYS_ADMIN 强制关闭 THEN 系统 SHALL 置 `CLOSED` 且保留原处理人字段 | **无按钮**：`OrderActions.vue` 未提供入口，且 `TERMINAL_STATUSES` 把 `ESCALATED_ADMIN` 视为不可操作 → 需补界面 | 无 | 是 |
| F3-4 | 部门统计看板 | A | As a 主管 / I want 看到本部门各状态的工单量 / so that 我知道今天卡在哪一环 | WHEN 主管请求 `scope=DEPT` THEN 系统 SHALL 返回本部门按状态分组的计数<br>WHEN 主管请求 `scope=ALL` 且无 `order:stats:all` THEN 系统 SHALL 返回 403 | 有（`StatsDashboardView.vue`） | 无 | 是 |
| F3-5 | 接管权限与服务层一致性（承接 G4，H3 新增） | **B** | As a 系统管理员 / I want 权限层与服务层的判断一致 / so that 代码里不存在"看似允许、永远被拦"的死分支 | WHEN 处理人尝试接管升级单 THEN 系统 SHALL 返回 403（以权限层为准）<br>WHEN 主管或 SYS_ADMIN 接管 THEN 系统 SHALL 允许<br>WHEN 清理服务层 THEN 代码 SHALL NOT 保留无法到达的 HANDLER 分支，或必须显式标注为死分支并说明原因 | 无 | 无 | 否 |

### 3.4 系统管理员（SYS_ADMIN）

| 编号 | 功能 | 类别 | 用户故事 | 验收标准（WHEN…THEN…SHALL…） | 前端 | 中间件 | 演示动线 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| F4-1 | SLA 规则维护 | A + **B（`accept_minutes` 归一 + 8 行覆盖）** | As a 系统管理员 / I want 按类型与优先级维护时限 / so that 停水停电这类紧急单有更短的时限 | WHEN 管理员修改已存在的 `type+priority` 配置 THEN 系统 SHALL 更新并立即对新提交工单生效<br>WHEN 修改不存在的组合 THEN 系统 SHALL 返回 404 且 SHALL NOT 新增记录<br>WHEN 管理界面提供"接单时限"字段 THEN 该字段 SHALL 被业务逻辑消费（三件事见下方「`accept_minutes` 归一」），或 SHALL 从界面移除（G5/I8）<br>WHEN 类型枚举按 R4 替换后 THEN 配置表 SHALL 覆盖 4 类 × 2 优先级 = 8 条，且 `OTHER + 普通` SHALL 存在（I5）<br>**WHEN 应用启动 THEN 系统 SHALL 校验 `t_sla_config` 覆盖 4 类 × 2 优先级共 8 条，缺任一条 SHALL 记 `error` 日志**（`ensureSlaConfigComplete()`，`@PostConstruct`，复用 `UserServiceImpl.ensureAdminSurvival()` 的既有启动自检模式） | 有（`SlaConfigView.vue`） | 无 | 是 |
| F4-2 | 全局统计看板 | A | As a 系统管理员 / I want 看全局各状态工单量 / so that 我能判断整体压力 | WHEN SYS_ADMIN 请求 `scope=ALL` THEN 系统 SHALL 返回全局按状态分组计数<br>WHEN 无 `order:stats` 的用户请求统计 THEN 系统 SHALL 返回 403 | 有 | 无 | 是 |
| F4-3 | 用户管理与角色分配 | A | As a 系统管理员 / I want 管理用户并分配角色 / so that 新入职师傅能立刻接单、离职人员立刻失去入口 | WHEN 管理员为某用户分配角色 THEN 系统 SHALL 立即生效于该用户下一次请求的权限计算<br>WHEN 管理员查询用户列表 THEN 系统 SHALL 支持按用户名模糊与部门过滤 | 有（`UserManageView.vue`、`UserRoleDialog.vue`） | 无 | 否 |
| F4-4 | 角色与权限管理 | A（O1 已裁决：保留） | As a 系统管理员 / I want 维护角色与其权限码 / so that 权限调整不必改代码 | WHEN 管理员为角色分配权限 THEN 系统 SHALL 更新角色权限集合<br>WHEN 管理员删除仍有用户绑定的角色 THEN 系统 SHALL 返回错误且 SHALL NOT 产生无角色用户<br>WHEN 管理员删除系统内置角色（`SUBMITTER`/`HANDLER`/`DEPT_ADMIN`/`SYS_ADMIN`）THEN 系统 SHALL 拒绝删除（R1；现有实现见 `RoleServiceImpl.java:29,72`，本条把既有的隐式保护写进验收标准） | 有（`RoleManageView.vue`、`PermissionTreeDialog.vue`） | 无 | 否 |

说明：F4-1 的"接单时限"一栏当前**不生效**——`accept_minutes` 全仓库无人读取（§1.4），实际生效的只有 `finish_minutes`。该项列入 B 类待改造。**注意归口变化**：先前版本把该 B 项挂在 F5-3 名下，实际上它与 SLA 配置表直接相关，现已归入 F4-1。

#### F4-1 的 `accept_minutes` 归一（G5 / I8，必须是三件事一起做）

`t_sla_config.accept_minutes`（"N 分钟内必须接单"）与 `ReleaseTimeoutScheduler` 里硬编码的 30 分钟，是同一个业务参数的两处定义。**只把字段接线到一处不算完成**，三件事必须同时落地，否则主路径与兜底路径会再次分叉：

| 序 | 要求 | 现状 | 不改的后果 |
| --- | --- | --- | --- |
| a | 释放时限读取 `t_sla_config.accept_minutes`（按该工单的 `type+priority`） | 硬编码 30 分钟（`ReleaseTimeoutScheduler.java:26`、`WorkOrderServiceImpl.java:145,311`） | 配置界面改的接单时限对释放行为毫无影响，G5 继续存在 |
| b | 兜底扫描 SQL 由 `updated_at <= now - 30min` 改为按配置表判断 | `ReleaseTimeoutScheduler.java:26` 写死阈值 | 即使 a 做了，兜底扫描仍按 30 分钟释放——**主路径与兜底路径给出不同结果**，且以先到者为准 |
| c | Redis 接单超时标记的 TTL 与延迟消息的 `delay` 取同一配置值 | 三处各自写死 30 分钟（见 `ASYNC-SCHEDULING-PLAN.md` §3.4 第 2 条） | 标记先过期 → 抢单防重失效；延迟消息先到 → 提前释放 |

这一条同时收敛三处重复常量并修复 G5（配置界面在骗人）与 I8（声明的可配置项与实际生效项不一致）。

### 3.5 横切功能（全部角色共同依赖）

| 编号 | 功能 | 类别 | 用户故事 | 验收标准（WHEN…THEN…SHALL…） | 前端 | 中间件 | 演示动线 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| F5-1 | 站内信中心 | A | As a 任何登录用户 / I want 收到与我相关的系统通知并标记已读 / so that 我不必盯着微信群 | WHEN 用户打开站内信中心 THEN 系统 SHALL 返回本人通知分页与未读计数<br>WHEN 用户标记他人通知为已读 THEN 系统 SHALL 返回"通知不存在"且 SHALL NOT 泄露该通知是否存在 | 有（`NotificationListView.vue`） | 无 | 是 |
| F5-2 | 超时自动释放 | A + **B（回池）** | As a 系统 / I want 接单后 30 分钟未开始处理的工单自动回收 / so that 占单不处理的工单不阻塞报修人 | WHEN 工单为 `ACCEPTED` 且 `updated_at` 早于 30 分钟前 THEN 系统 SHALL 清除 `assignee_id`、置 `RELEASED` 并写入 `RELEASE` 日志（操作人=0）<br>WHEN 释放后 THEN 该工单 SHALL 能再次被处理（**当前违反：G1，`RELEASED` 是死路**）→ 改造后 SHALL 回到 `PENDING` 可再次抢单 | 有（状态标签展示"已释放"） | 调度 | 否（30 分钟，见 §6 说明） |
| F5-3 | SLA 超时升级与通知 | A + **B（触发方式、通知对象、分级催办）** | As a 系统 / I want SLA 到期未完结时按节奏反复催办、完结即停 / so that 超时工单不会沉底，也不会因为"只提醒一次"被漏掉 | WHEN 工单 ∈ {`PENDING`,`ACCEPTED`,`IN_PROGRESS`} 且 `sla_deadline < now` THEN 系统 SHALL 向 SYS_ADMIN 发出**首次**告警<br>WHEN 自首次告警起每满 24 小时且工单仍未完结 THEN 系统 SHALL **再次告警一次**<br>WHEN 工单完结（`CLOSED` / `RELEASED`）THEN 系统 SHALL 停止告警<br>WHEN 同一条消息被重复投递 THEN 系统 SHALL 只发送一次（幂等去重）<br>WHEN 通知发送失败 THEN 系统 SHALL 清除幂等键，使下一周期 SHALL 重试<br>WHEN 扫描任务被手动触发 THEN 系统 SHALL 在秒级完成一次扫描（依赖 F5-4） | 有（站内信呈现） | 调度 + Redis | 是 |
| F5-4 | 异步与调度基础设施接入 | **B / C** | As a 运维与演示者 / I want 消息投递与定时扫描由真实的 MQ 与调度中心承载 / so that 进程重启后告警仍可靠、演示可手动触发 | WHEN 业务事务提交成功但进程在投递前崩溃 THEN 该事件 SHALL 在重启后被投出（outbox）<br>WHEN 运维在调度中心手动触发 SLA 扫描 THEN 系统 SHALL 秒级完成并留下执行记录<br>WHEN 消费失败 THEN 消息 SHALL 进入死信并按退避重投，SHALL NOT 无限重回队列 | 无（纯后台） | MQ + 调度 + Redis | 是（P2 后） |
| F5-5 | 按处理人工作量统计 | **C（全表最低优先级）** | As a 主管 / I want 看到每位师傅的接单量、完成量、驳回数 / so that P2 的派工差异能在周期内被发现 | WHEN 主管选择时间范围 THEN 系统 SHALL 返回每位处理人的接单数、完成数、驳回数<br>WHEN 某处理人在周期内无工单 THEN 系统 SHALL 显示 0 且 SHALL NOT 遗漏该行 | 无（需新增视图） | 无 | 否 |
| F5-6 | 外部渠道告警推送（R7 新增） | **C** | As a 主管或处理人 / I want 在钉钉/企业微信群里收到超时告警 / so that 不登录系统也能知道有工单超时（支撑 P3、补上 N2 的触达缺口） | WHEN 外部渠道开关为关闭 THEN 系统 SHALL 只发站内信，行为与现在一致<br>WHEN 开关开启且工单超时 THEN 系统 SHALL 通过 MQ 异步投递一条群机器人消息，业务事务 SHALL NOT 同步等待该调用<br>WHEN webhook 超时或返回失败 THEN 系统 SHALL 按退避重试，且工单主流程 SHALL NOT 受影响<br>WHEN 无可用凭据 THEN 代码 SHALL 保留实现并在 README 标注"已实现未启用"，SHALL NOT 留下半成品 | 无（外部渠道，无页面） | MQ + webhook | 是（P5 后） |

#### F5-3 的告警政策（H1）与去重键要求

本节的告警政策**取代**原文的"同一工单 24 小时内 SHALL 只发一次"。原表述照抄了当前实现的缺陷（`sla_notified:{orderId}` 键粒度错误会吞掉同一工单的第二次合法告警，见 `ASYNC-SCHEDULING-PLAN.md` §3.4 第 3 条），已删除。

验收标准必须能同时区分两件事，缺一不可：

| 要区分的事 | 判定 | 期望行为 |
| --- | --- | --- |
| **同一工单的第二次合法告警**（如首告警后 24 小时仍未完结） | `eventId` 不同（`version` 已递增） | **必须发出** |
| **重复投递的同一条消息**（MQ 至少一次语义、消费者重投） | `eventId` 完全相同 | **必须被去重** |

因此幂等键必须是事件维度 `{aggregate}:{aggregateId}:{version}:{eventType}`，其中 `version` 在同一工单的每一次合法告警时递增（首次 v1，此后每满 24 小时加 1），而不是对同一工单长期复用同一个键。技术方案的对应条款已同步修订（见 `ASYNC-SCHEDULING-PLAN.md` §5.2 与 §3.4 第 3 条）。

**F5-5 的最低优先级理由（S2）**：它是唯一的 C 类新功能、不在任何演示动线中、且不引出新的技术问题（分组查询模式与 F3-4 完全相同）。因此实施顺序中必须排在其他所有工作之后。

### 3.6 汇总与孤儿标记

功能总数 **25** 条（提交人 6、处理人 4、部门主管 5、系统管理员 4、横切 6）。其中 F1-1、F1-5、F4-1、F5-2、F5-3 同时属于 A 与 B（已实现但需改造），因此下表 A 与 B 的条数之和大于 25。

**分类规则（可复现）**：功能只要"已实现但必须改造才能满足本基线"即计入 A+B 跨类，同时计入 B 合计；纯 A 指无需任何改造即可满足基线。

| 类别 | 条数 | 编号 |
| --- | --- | --- |
| A 已实现（纯） | **12** | F1-2、F1-3、F2-1、F2-2、F2-3、F3-1、F3-2、F3-4、F4-2、F4-3、F4-4、F5-1 |
| A 已实现 + B 需改造（跨类） | **5** | F1-1（R4 类型枚举替换）、F1-5（G2 越权读取）、F4-1（`accept_minutes` 生效 + 8 条覆盖）、F5-2（`RELEASED` 回池）、F5-3（触发方式、通知对象、分级催办） |
| B 需改造（合计，含跨类 5 条） | **11** | 跨类：F1-1、F1-5、F4-1、F5-2、F5-3<br>纯 B：F1-4（AI 分类异步化 + 前端放开必选）、F1-6（G2 日志越权）、F2-4（G3 权限码启用）、F3-3（前端补齐升级单入口）、F3-5（G4 服务层与权限层一致）、F5-4（MQ 与调度真实接入） |
| C 需新增 | **2** | F5-5（按处理人工作量统计，全表最低优先级）、F5-6（外部渠道 webhook 告警） |

**与上一版（v1）的差异溯源**：

| 变化 | 来源 | 影响 |
| --- | --- | --- |
| F1-5 由纯 A 移入 A+B | H2 | 纯 A 14→13、跨类 3→4、B 合计 6→7（仅 H2 生效时的中间值） |
| 新增 F1-6 / F2-4 / F3-5 三条纯 B 条目 | H3 | B 合计 +3 |
| F1-1 因类型枚举替换移入 A+B | R4 | 纯 A −1、跨类 +1、B 合计 +1 |
| 新增 F5-6 一条 C 条目 | R7 | C 1→2 |
| 最终值 | — | 纯 A **12**、A+B **5**、B 合计 **11**、C **2**，合计 25 |

**F5-4 的计数归属说明**：F5-4 标为"B / C"（主体是改造现有占位实现，同时会新增 outbox 与消费去重表）。计数时**整体归入 B**，其新增部分不再另计入 C，以避免同一功能被重复计数；上表 C 栏的 2 条不含 F5-4。

**说不出"引出什么技术问题、对应哪个痛点"的条目**：**无**。上一版的唯一一条（F4-4 角色与权限管理，孤儿 O1）已由 R1 裁决保留并给出保留理由（权限调整不必发版），不再是孤儿。

---

## 四、明确不做

判断标准：**删掉它，有没有哪个痛点或技术点会失去支撑**。理由均为代价视角，不使用"时间不够"。

| 编号 | 不做的事 | 代价（做它的真实成本） | 删掉它谁失去支撑 | 结论 |
| --- | --- | --- | --- | --- |
| N1 | 工单附件 / 照片上传 | 需要对象存储（或本地磁盘配额）+ 上传鉴权 + 内容类型校验 + 孤儿文件清理 + 备份体积翻倍；本机只有一块 2C4G 的盘 | P4 的现状流程里照片是重要信息通道，报修人仍需在微信群补图。**这是本轮已知损失（R6）**：不做附件的代价必须在 README 与本节同时写明，不得只写"不做" | **不做（R6 已裁决）** |
| N2 | 短信 / 邮件通道（**不含 webhook**） | 两套第三方凭据与配额管理、模板审核、运营商通道与额度成本 | 无功能失去支撑：P3 的非登录触达已由 F5-6 的群机器人 webhook 覆盖（R7 裁决：只做单渠道 webhook，短信与邮件不做） | **不做（R7 已裁决收窄）** |
| N3 | 移动端 App / 小程序 | 第二个前端工程 + 应用商店/小程序审核 + 与 Web 端双份功能维护 | 无。维修师傅用手机浏览器即可完成抢单与提交验收 | 不做 |
| N4 | 与学校统一身份认证（CAS/钉钉）对接 | 跨部门协调窗口不可控、需要联调环境与账号映射规则 | 无。用"关闭开放注册 + 管理员建号"即可消除 O2 的越权注册问题 | 不做 |
| N5 | 实时推送（WebSocket / SSE） | 长连接占用、心跳与重连、多实例广播需要额外通道 | 无。站内信按页面打开拉取，秒级时效不是任何痛点的要求 | 不做 |
| N6 | 自动派单 / 负载均衡算法 | 需要技能矩阵、地理分区、排班数据，这三类数据本系统都不持有；错误的自动派单比人工指派更糟 | 无。P2 由 F5-5（量化工作量）+ F3-2（人工指派）覆盖 | 不做 |
| N7 | 多校区 / 多租户 | 所有表加租户维度，缓存键、索引、权限过滤与统计口径全部重构 | 无。本基线只定义东湖校区；其他校区上线时再评估 | 不做 |
| N8 | 工单转派（处理人之间转单） | 新增一条状态转移与权限校验点，且与"抢单先到先得"的公平性冲突（可以把难单转走） | 无功能失去支撑，但暴露一个能力缺失：`assignOrder` 只允许 `PENDING` 且未分配的单（`WorkOrderMapper.assignOrder`），**主管无法把已接单的工单改派出去**（师傅请假、派错人时无解）。**R8 裁决：不做，并补记操作预案**——师傅请假或派错人时，主管只能等该单接单满 30 分钟被超时释放（F5-2）后重新指派；**该预案在 F5-2 回池改造完成前不成立**（当前 `RELEASED` 是死路，释放后既不能抢单也不能重新指派，见 G1） | **不做（R8 已裁决，附操作预案；预案依赖 F5-2）** |
| N9 | 满意度评价 / 回访 | 新增评价表、评价口径、以及对师傅的考核压力；且评价与"是否修好"不是同一件事 | 无。P5 的验收闭环已能证明结果，评价不解决 P5 | 不做 |
| N10 | 报表**文件导出**（Excel/PDF） | 文件生成、下载鉴权、临时文件存储与清理、大文件内存占用 | 无。P1–P6 的决策需求由 F3-4 与 F4-2 看板覆盖。**S6 裁决：去掉文件导出，但保留"定时聚合落表"**——跨月区间的聚合是实时接口做不到的，该聚合结果由 F5-4 的调度任务定时写入结果表，供看板读取；两处口径已在 `ASYNC-SCHEDULING-PLAN.md` P6 同步 | **不做文件导出（S6 已裁决）** |
| N11 | 分布式事务框架（Seata 等） | 引入协调者组件与全局锁，2C4G 内没有余量；且本项目没有跨库写 | 无。所有一致性在单库事务内解决，跨进程用 outbox + 幂等消费 | 不做 |

### 本节裁决结果（2026-09-23）

| 裁决项 | 裁决 | 代价与操作预案（必须随裁决一起保留） |
| --- | --- | --- |
| 附件（N1） | **不做（R6）** | 做：每单平均 2 张图 × 日均 500 单 ≈ 1GB/月增量，需要存储与清理策略。不做：报修人继续在微信群补图，P4 只能靠文字描述分类 |
| 非登录触达（N2） | **做，但限定单渠道 webhook（R7）** | 实现为 `NotifyChannel` 的新实现（`InAppNotifyChannel` 不动），调用必须走 MQ，需配超时与退避重试，且必须可由开关关闭；无凭据时保留实现并在 README 标注"已实现未启用"。代价：多一条外部依赖与其失败链路 |
| 已接单改派（N8） | **不做（R8）** | 操作预案：主管只能等 30 分钟超时释放后重新指派。**该预案在 F5-2 回池改造完成前不成立**——当前 `RELEASED` 是死路（G1），释放后工单既不能被抢单也不能被重新指派，预案的空窗会变成"永久卡住"。代价：预案生效后空窗仍最长 30 分钟 |

**未决项（不影响本轮交付，但必须记录）**：R6 与 R7 都要求在 README 中标注，而**仓库根目录当前不存在 `README.md`**（只有 `deploy/README.md`）。这两条标注要求落在哪一份文件尚待确认，见本轮交付的「危险区」。

---

## 五、权限矩阵

图例：**允许** 可执行操作 / **只读** 可查看不可操作 / **禁止** 不可访问。括号内是生效范围。"权限码"栏为接口层 `@SaCheckPermission` 的值，`无` 表示仅要求登录（对应 §1.6 的 G3）。

| 编号 | 功能 | 权限码 | 提交人 | 处理人 | 部门主管 | 系统管理员 |
| --- | --- | --- | --- | --- | --- | --- |
| F1-1 | 提交工单 | 无 | 允许 | 允许 | 允许 | 允许 |
| F1-2 | 我的工单列表与详情 | 无 | 只读（自己提交的） | 只读（自己接的 + 待分配池） | 只读（本部门提交的） | 只读（全部） |
| F1-3 | 验收通过 | 无 | 允许（仅本人提交的） | 禁止 | 禁止 | 禁止 |
| F1-3 | 验收驳回 | `order:reject` | 允许（仅本人提交的） | 禁止 | 禁止 | 禁止 |
| F1-4 | AI 自动分类 | 无（系统内部） | — | — | — | — |
| F1-5 | 操作日志时间线 | 无 | 只读 | 只读 | 只读 | 只读 |
| F1-6 | 操作日志访问控制（G2） | 无（按归属校验，非权限码） | 只读（仅本人提交的） | 只读（仅自己接的） | 只读（本部门提交的） | 只读（全部） |
| F2-1 | 抢单 | `order:accept` | 禁止 | 允许 | 允许 | 允许 |
| F2-2 | 开始处理 | 无（现状 G3）→ `order:start`（F2-4 后） | 禁止 | 允许（仅自己接的） | 允许（仅自己接的） | 允许（仅自己接的） |
| F2-3 | 提交验收 | 无（现状 G3）→ `order:complete`（F2-4 后） | 禁止 | 允许（仅自己接的） | 允许（仅自己接的） | 允许（仅自己接的） |
| F2-4 | 状态流转权限码启用（G3） | `order:start` / `order:complete` / `order:approve` | 见各功能行 | 见各功能行 | 见各功能行 | 见各功能行 |
| F3-1 | 本部门工单查看 | 无 | 只读（自己提交的） | 只读（池 + 自己接的） | 只读（本部门） | 只读（全部） |
| F3-2 | 手工派单 | `order:assign` | 禁止 | 禁止 | 允许 | 允许 |
| F3-3 | 接管升级单 | `order:manage` | 禁止 | 禁止（G4：权限码未授予） | 允许 | 允许 |
| F3-3 | 强制关闭升级单 | `order:manage` | 禁止 | 禁止 | 禁止（服务层要求 SYS_ADMIN） | 允许 |
| F3-5 | 接管权限一致性（G4） | `order:manage` | 禁止 | 禁止（以权限层为准，服务层 HANDLER 分支须清理） | 允许 | 允许 |
| F3-4 | 部门统计看板 | `order:stats` | 禁止 | 禁止 | 允许 | 允许 |
| F4-1 | SLA 规则维护 | `sla:config:manage` | 禁止 | 禁止 | 禁止 | 允许 |
| F4-2 | 全局统计看板 | `order:stats` + `order:stats:all` | 禁止 | 禁止 | 禁止 | 允许 |
| F4-3 | 用户管理与角色分配 | `system:user:manage` | 禁止 | 禁止 | 禁止 | 允许 |
| F4-4 | 角色与权限管理（O1 已裁决保留） | `system:role:manage` | 禁止 | 禁止 | 禁止 | 允许（删除内置角色 SHALL 被拒） |
| F5-1 | 站内信中心 | 无 | 只读（仅本人） | 只读（仅本人） | 只读（仅本人） | 只读（仅本人） |
| F5-2 | 超时自动释放 | 无（系统触发） | — | — | — | — |
| F5-3 | SLA 超时升级与通知 | 无（系统触发，分级催办） | — | — | — | 只读（唯一接收方为 SYS_ADMIN） |
| F5-4 | 异步与调度基础设施 | 无（后台） | — | — | — | — |
| F5-5 | 按处理人工作量统计（C，待新增） | `order:stats` | 禁止 | 禁止 | 允许 | 允许 |
| F5-6 | 外部渠道告警推送（C，待新增） | 无（开关控制，非权限码） | — | — | 只读（群消息接收方） | 只读（群消息接收方） |

矩阵暴露的两处不一致（均已在 §1.6 记录，此处只做交叉印证，并已各自指定承接条目）：

- **F2-2 / F2-3 的"无权限码"**：提交人虽然有 `order:start`、`order:complete`、`order:approve` 三个权限码的定义与授权，但接口不校验，矩阵中这几行实际由服务层的"是否本人"兜底，而不是由权限体系兜底（G3 → 承接条目 **F2-4**，R5 要求注解与授权同时到位）。
- **F3-3 / F3-5 接管行的处理人列**：服务层允许 HANDLER 接管，权限层不允许，最终以权限层为准 → 处理人**禁止**（G4 → 承接条目 **F3-5**，需清理服务层的不可达分支）。

---

## 六、演示动线

### 6.1 业务动线（5 分钟，面试与验收用）

**前置条件**：数据库已导入 `sql/init.sql`；四个账号可用（提交人 / 处理人 / 主管 / 超管）；调度周期保持代码默认值（释放扫描 60s、SLA 扫描 300s）；浏览器打开前端首页。

| 时间 | 步骤 | 操作人 | 动作 | 看什么（界面 + 后台数据） |
| --- | --- | --- | --- | --- |
| 0:00–0:40 | 1. 提交（含 AI 异步分类） | 提交人 | 登录 → 创建工单，只填标题与内容（不选类型） | **S3：本步必须演出完整时序，不能只说"类型由 AI 判定"**<br>① 点击提交后 **100 毫秒内**返回，列表出现新工单，类型显示**「分类中」**、SLA 栏显示兜底值算出的截止时间；<br>② 数秒内刷新或重开详情，类型变为**「网络故障」**、优先级**「紧急」**；<br>③ **SLA 截止时间随之收缩**（例如从兜底的 8 小时缩到 2 小时）。<br>后台：`t_work_order` 先以 `type=OTHER`/兜底优先级落库，写回后更新为 `NETWORK/1` 并按 H4 规则重算 `sla_deadline`；`t_work_order_log` 新增 `SUBMIT` 与一条"分类修正"日志。<br>**当前限制**：前端 `type` 为必填，AI 不触发；本步依赖 B 类改造 F1-4 完成。改造前演示改为手工选类型，并把"AI 判定"作为口头说明——不把未实现的能力当已实现演示 |
| 0:40–1:20 | 2. 抢单 | 处理人 | 登录 → 待分配池看到该单 → 点"抢单" | 界面：列表里该单从"待分配"变"已接单"，处理人显示为自己；按钮区变为"开始处理"。<br>后台：`assignee_id` 写入、`status=ACCEPTED`、`version+1`；Redis 出现 `order:accept_timeout:{id}`；日志新增 `ACCEPT`。<br>并发点：两个浏览器同时点抢单，只有一个成功，另一个收到"工单已被抢走" |
| 1:20–1:50 | 3. 处理 | 处理人 | 点"开始处理" | 界面：状态变"处理中"。<br>后台：`status=IN_PROGRESS`、`version+1`；Redis 的 `order:accept_timeout:{id}` 被删除；日志新增 `START` |
| 1:50–2:20 | 4. 提交验收 | 处理人 | 点"提交验收" | 界面：状态变"待验收"，按钮区出现"验收通过/驳回"（对提交人可见）。<br>后台：`status=AWAIT_APPROVAL`；日志新增 `COMPLETE` |
| 2:20–3:00 | 5. 验收驳回 | 提交人 | 打开详情 → 点"驳回" → 填理由并确认 | 界面：状态回到"处理中"；详情时间线出现"驳回"与理由。<br>后台：`reject_count` 由 0 变 1；日志新增 `REJECT` 并带 `remark`。<br>防重点：再点一次驳回提交同一 Token，界面提示"请勿重复提交或Token已过期"（Redis Lua 消费失败） |
| 3:00–3:40 | 6. 驳回至上限升级 | 提交人 + 处理人 | 再走一轮"提交验收 → 驳回"（第 3 次驳回） | 界面：状态变"已升级"，普通操作按钮消失。<br>后台：`status=ESCALATED_ADMIN`、`reject_count=3`；`t_notification` 新增 SYS_ADMIN 的站内信"驳回次数已达上限" |
| 3:40–4:10 | 7. SLA 超时升级 | 超管 + 提交人 | 超管在 SLA 配置页把 `NETWORK/普通` 的完成时限改为 1 分钟（R4 替换后的类型；改造完成前用 `REPAIR/普通`）→ 提交人新提一条单，谁也不处理 | 界面：超管站内信出现"工单 WO-… SLA 超时"；该单状态仍为"待分配"（系统只告警、不改状态）。<br>后台：`t_notification` 新增一条；幂等键按事件维度写入（`{orderId}:{triggerType}:{version}`，首次 v1）。<br>**当前限制**：扫描周期 5 分钟，现场无法等；依赖 B 类改造 F5-4（引入调度中心后可手动触发，秒级出结果）。改造前此步用"预置数据"跳过，并在界面上说明定时扫描的存在 |
| 4:10–4:40 | 8. 通知 | 超管 | 打开站内信中心 | 界面：未读红点数字、通知列表按时间倒序、点击"标记已读"后未读数减少。<br>后台：`t_notification.is_read` 由 0 变 1 |
| 4:40–5:00 | 9. 统计看板 | 主管（切超管对比） | 打开统计报表 | 界面：主管只能切"本部门统计"，各状态数量与刚才动作一致（待分配 +1、已升级 +1）；切成超管账号后可切"全局统计"。<br>后台：`SELECT status, COUNT(*) ... GROUP BY status` 的实时结果 |

**动线未覆盖但已实现的超时释放（F5-2）**：30 分钟无法在现场等待。若需要演示，用 SQL 把该单的 `updated_at` 回拨 31 分钟，然后等下一个扫描周期（≤60 秒）即可看到状态变"已释放"、`assignee_id` 置空、日志新增 `RELEASE`（操作人=0）。**同时会暴露 G1**：该单此后无法再被抢单，这正是 F5-2 需要改造的原因。

### 6.2 技术动线（约 3 分钟，S4）

**用途**：面试官追问"可靠性怎么证明"时使用。**可演示时机：P1–P5 全部完成之后**，在此之前不要演示本动线。

| 序 | 故障注入动作 | 看什么 | 证明的不变量 |
| --- | --- | --- | --- |
| ① | 在投递前 `kill -9` 后端进程 → 重启 | 重启后消息仍被投出，outbox 中该条从 `PENDING` 变 `SENT` | 事务与投递不靠"进程活着"保证（outbox，`ASYNC-SCHEDULING-PLAN.md` §5.1） |
| ② | 手工把同一条消息投递两次 | 只产生一条通知、一条消费记录 | 消费端幂等键按事件维度去重（§5.2），重复投递不放大 |
| ③ | `docker stop rabbitmq` 后抢单并等待 | 工单仍被超时释放（由兜底扫描完成） | 兜底路径独立于 MQ 可用：MQ 挂了业务不致命 |
| ④ | 在调度中心手动触发 SLA 扫描 | 秒级返回，控制台留下执行记录与耗时 | 扫描可被观测、可被人工触发（F5-4） |
