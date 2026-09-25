# 不变量清单（INVARIANTS.md）

> 版本 v1 · 2026-09-23 · 与 `BUSINESS-SCOPE.md` 配套，两者共同构成业务约束的权威来源
> **定位**：记录"任何时刻都必须成立、一旦不成立即为事故"的条件。不变量与功能是不同的东西——功能会被重新实现，不变量不会，因为不变量是踩坑总结出来的。
> **范围**：本文件只管"必须永远成立的条件"；用户可见功能在 `BUSINESS-SCOPE.md`，技术性后台任务（归档、outbox 投递、重试重放、消息清理）在 `ASYNC-SCHEDULING-PLAN.md`
> **本轮边界**：只产出文档，不修改任何代码；I4 的修复方案写入本文件但**本轮不实施**

**探针说明**：下表"校验方式"列引用文末的探针编号；所有探针可直接在业务库执行，`期望` 列为空或 0 时才算通过。

---

## 一、不变量总表

| 编号 | 不变量 | 违反后果 | 当前守卫（含代码位置） | 是否存在缺口 | 校验方式 | 恢复步骤 |
| --- | --- | --- | --- | --- | --- | --- |
| **I1** | 系统至少存在一名**启用状态**的 SYS_ADMIN | 无人能维护用户/角色/SLA 配置；驳回升级单无人可接管或强制关闭（`order:manage` 只授予主管与超管）；系统进入"不可管理"状态，只能改库恢复 | 启动自愈 `UserServiceImpl.ensureAdminSurvival()`（`@PostConstruct`，`UserServiceImpl.java:51-77`）+ 四道防线（`:178-202`） | 无 | 探针 **P1** | `INSERT IGNORE INTO t_user_role (user_id, role_id) VALUES (1, 1);`（见 `sql/hotfix-role-permissions.sql`）；若 ID=1 用户本身被停用，先 `UPDATE t_user SET status=1 WHERE id=1;` |
| **I2** | ID=1 造物主账号不可失去 SYS_ADMIN 角色 | 与 I1 同源：一旦造物主失去超管，系统失去"最后的稳定管理员"，只能改库 | 防线 1：`UserServiceImpl.java:178-182`（允许为其加角色，不允许移除 SYS_ADMIN） | 无 | 探针 **P2** | 同 I1 的恢复语句 |
| **I3** | 4 个内置角色（SUBMITTER / HANDLER / DEPT_ADMIN / SYS_ADMIN）不可删除 | 角色被删后其权限绑定与用户绑定失去语义：用户变成"无角色"，登录后无任何可见范围（`listOrders` 退化为仅自己提交的），处理人无法抢单 | `RoleServiceImpl.java:29` 的 `PROTECTED_ROLE_CODES`，在 `:72` 拦截删除；另有测试 `RoleServiceTest.testDeleteRole_protectedSeedData` | 无 | 探针 **P3** | 重新插入角色行（`sql/init.sql` 的角色段）与对应权限绑定（`sql/hotfix-role-permissions.sql`） |
| **I4** | `t_work_order.sla_deadline` 必须非空（未完结工单） | **静默失效**：SLA 扫描 SQL 是 `AND sla_deadline < NOW()`，SQL 中 NULL 与任何值比较结果为 unknown，永不匹配 → 该工单**永远不会**出现在扫描结果中、永远不会告警。全程无异常、无日志 | **无（运行期兜底已定稿，尚未实施）**：`WorkOrderServiceImpl.submitOrder` 里 `selectOne` 在 `:89`、`LocalDateTime slaDeadline = null;` 在 `:93`、条件块 `:94-96` 只在查到配置时赋值、`:108` 把可能仍为 null 的值写库，全程不抛异常、不记日志。定稿方案：a-2 兜底配置 + type 枚举校验（见 §2 (a)/(b)），实施阶段 **P0a 第 5 项** | **是** | 探针 **P4**（2026-09-23 已实测：未完结 NULL = **104** 行、全状态 NULL = 108 行，108 行全部为 `TST-%` 测试残留，见 §2 (c) 步骤 0） | 见文末「I4 完整修复方案」；历史数据处置见该节 (c) |
| **I5** | `t_sla_config` 必须覆盖全部 `type × priority` 组合 | 与 I4 是同一问题的两个面：缺失组合 → `selectOne` 返回 null → `sla_deadline` 为 NULL（运行期由 I4 的兜底接住，但配置漏配会被静默降级而不是被发现） | **当前无守卫**；修复方案已定稿：新增启动自检 `ensureSlaConfigComplete()`（`@PostConstruct`，复用 `UserServiceImpl.ensureAdminSurvival()` 的既有模式，`UserServiceImpl.java:55-77`），启动时校验 8 条覆盖、缺任一条记 `error` 日志。另有 `submitOrder` 未对 `type` 做枚举校验（`WorkOrderServiceImpl.java:73-91` 直接使用请求值）——该缺口由 R4 的枚举校验一并修掉 | **是** | 探针 **P11**（事前型，配置表自身）+ 探针 **P5**（事后型，实际工单数据） | 按 R4 把 8 行配置的类型集合替换为 `NETWORK/UTILITY/DORM/OTHER`（**行数保持 8 行不变**），**`OTHER + 普通` 必须存在**（它是 AI 兜底值对应的组合）；同步补 `ensureSlaConfigComplete()` 启动自检 |
| **I6** | `status='PENDING'` 的工单，`assignee_id` 必须为 NULL | 状态与归属不一致的脏数据：工单在待分配池里却已挂着处理人，`grabOrder` 的 `WHERE assignee_id IS NULL` 会拒绝所有人抢单 → 该单无人可接 | 现状**天然成立**（超时释放后进入 `RELEASED` 且清空 assignee，不回池）；**风险在 F5-2 回池改造引入**：若先改状态后清 assignee 或中途异常，就会破坏该不变量 | 改造后新增 | 探针 **P6** | `UPDATE t_work_order SET assignee_id = NULL WHERE status='PENDING' AND assignee_id IS NOT NULL;` 并检查应用日志确认回池逻辑的更新顺序（必须在同一条 UPDATE 内同时改状态与清 assignee） |
| **I7** | 权限码与注解**双向**一致：代码里被 `@SaCheckPermission` 引用的权限码必须在 `t_permission` 中存在，反之亦然 | 正向（定义了但没生效）已由 G3 覆盖；**反向**：注解引用了表中不存在的权限码 → Sa-Token 判定失败 → 该接口对**所有人**返回 403，形成连锁不可用 | 无自动校验；正向缺口由 F2-4 承接（G3） | **是（反向尚无覆盖）** | 探针 **P7**（代码侧）+ **P8**（库侧） | 逐条对齐：缺权限码就补 `t_permission` 行，缺注解就补注解或删除权限码 |
| **I8** | 声明的可配置项与实际生效项一致：任何出现在**配置界面**的字段必须有一处业务逻辑消费它；任何硬编码的业务参数必须在文档中标注为常量 | 配置界面成为"假开关"：管理员改了值、保存成功、界面显示新值，但系统行为不变。比"功能没做"更糟——它消耗了管理员的信任与排障时间 | **已闭合（P1 步骤 5，提交 e7a159b）**：`accept_minutes` 已真正被消费——MQ 路径在接单时算 `deliver_at = now + accept_minutes`（P1 步骤 2），兜底扫描改为 `TIMESTAMPADD(MINUTE, c.accept_minutes, w.updated_at) <= NOW()`（P1 步骤 5）。仍存在的硬编码业务参数已逐个标注为常量：`max_reject = 3`（建单时写入，工单表默认值 3）、SLA 告警去重窗口 `24h`（`SlaEscalationScheduler.SLA_NOTIFIED_TTL`）、驳回一次性 Token 有效期 `30s`（安全令牌，不是业务时限）、兜底扫描周期 `60s` 与 SLA 扫描周期 `300s`（**扫描频率，不是时限**）。另：原计划"把 Redis 接单超时标记的 TTL 收敛到 accept_minutes"已被推翻——该键判定为死设计并彻底移除（`docs/DECISIONS.md` D48） | **无** | 探针 **P9**（已改为可自动判定：不存在"超过自身配置时限 +3 分钟仍为 ACCEPTED"的工单；跑本项时应用须在运行） | **`accept_minutes` 归一的落地情况**（P1 步骤 2/3/5 已完成，详见 `BUSINESS-SCOPE.md` F4-1 的「`accept_minutes` 归一」表）：a) 释放时限读取 `t_sla_config.accept_minutes`；b) 兜底扫描 SQL 由 `updated_at <= now-30min` 改为按配置表判断（否则主路径与兜底路径再次分叉）；c) Redis 接单超时标记的 TTL 与延迟消息 `delay` 取同一配置值。三者同时收敛三处重复常量（`ASYNC-SCHEDULING-PLAN.md` §3.4 第 2 条）。`max_reject` 与其余硬编码项：在本文档标注为常量，或移入配置表 |
| **I9** | 测试执行不得向业务库遗留持久数据：任何测试写入的行必须在同一测试生命周期内被清理，或写入独立的测试库 | 业务库被测试数据污染：本库实测曾积累 **108 条 `TST-` 残留**（详见 §三），它们参与统计、占据工单 ID 空间，且 108 条 `sla_deadline` 全为 NULL，直接构成 I4 的假阳性样本——**探针结果被污染后，真实的 I4 缺口反而被淹没** | **已修复（P0a）**：`src/test/resources/application.properties` 设 `spring.profiles.active=test`，全部 `@SpringBootTest` 统一指向独立库 `work_order_test`（含原污染源 `WorkOrderFlowServiceTest.setUp` 的提交式写入）；类级 `@Transactional` 保留用于测试间隔离。**2026-09-23 实测：连续运行测试后业务库计数不变（111/530996/108 → 111/530996/108）** | 否（已闭环） | 探针 **P13a/P13b** | 已执行：108 条工单残留 + 1343 条关联日志已删除（留档见下） |
| **I10** | **时间来源必须一致**：写 `sla_deadline` 的时钟（JVM 的 `LocalDateTime.now()`）与判定超时的时钟（MySQL 的 `NOW()`）必须落在同一时区 | **所有工单瞬间变成"已超时"**，或反过来**永不超时**：`submitOrder` 用 JVM 时间算截止点，`findSlaExpired` 用 `AND sla_deadline < NOW()` 比较；两侧差 8 小时时，一张刚提交的工单会被判定为已超时 8 小时，SLA 告警对所有工单同时触发。与 I4 同属"静默失效"一类——**它不会报错，只会让结论全错** | **配置层已加固**：`src/main/resources/application.yml` 与 `src/test/resources/application-test.yml` 的 `connectionTimeZone` 统一为 `%2B08:00`（偏移量），配合容器 `TZ=Asia/Shanghai` 与 `-Duser.timezone=Asia/Shanghai`。**无运行时守卫**——没有任何代码在启动时校验两者一致 | **是（无运行时守卫）** | 探针 **P15a**（最近 10 分钟内创建的工单，`created_at` 与 `NOW()` 偏差应 0–5 秒；若接近 28800 即为差 8 小时）+ **P15b**（DB 会话偏移应为 -28800 秒） | ① 确认两处配置为 `%2B08:00`；② 若 P15a 报 28800 量级，检查 JVM `user.timezone` 与容器 `TZ`；③ 长期方案：在启动自检里增加一条"JVM 时区 == DB 会话时区"的校验（与 `ensureSlaConfigComplete` 同模式） |
| **I11** | **outbox 投递与消费链路四条约束**（P1 步骤 3–4）：① 投递任务**不得在持有数据库行锁时做网络 IO**；② `status='SENT'` **只能由 publisher-confirm 的 ack 产生**；③ 延迟时机以 `t_event_outbox.deliver_at` 为**唯一真相来源**（`x-delay = deliver_at - now`，由 broker 的延迟交换机执行）；④ 消费者**必须手动 ACK**，且**任何被 ACK 掉的分支都必须留下日志**（RELEASED→INFO / SKIPPED→DEBUG / ERROR→ERROR） | 违反 ①：一次 broker 卡顿就会拖住整个投递循环并占住数据库连接（2 vCPU 上尤其明显），outbox 长时间不可用；违反 ②：**假 SENT**——消息根本没进 broker，记录却显示已投递，释放检查链路静默失效（最危险的一类，因为它看起来一切正常）；违反 ③：延迟语义与配置分叉，管理员改了 `accept_minutes` 而实际延迟不变且不报错（G5/I8 的同一类错误） | ① `EventOutboxMapper.claimPending`（条件 UPDATE + owner 租约，语句结束即释放锁）/ `reclaimStale`（回收阈值 5 分钟）；② `OutboxDispatchTask` 只认 `CorrelationData.Confirm.isAck()`：nack / 超时 / 发送异常一律 `markAttemptFailed`；③ `OutboxDispatchTask.delayMillis(deliverAt)` + `RabbitOutboxConfig` 声明的 `x-delayed-message` 交换机（启动期校验其类型）；④ `OrderReleaseListener`（`@RabbitListener` + `Channel` 参数 + `basicAck`；`acknowledge-mode: manual`；用 `GET /api/consumers` 的 `ack_required=true` 复核） | **否（已落地）**；未覆盖：**多实例并发抢占的真机验证**（当前只有单实机 + 单测覆盖 SQL 语义） | 探针 **P16a/P16b/P16c**（PENDING 长期积压 / SENDING 卡死 / FAILED 计数）。真机实测（2026-09-24）：停 broker 后记录保持 PENDING、`retry_count` 1→2→3、`next_retry_at` 每 30s 后移，broker 恢复后转 SENT；broker 被 SIGKILL 后延迟消息仍到点投递；后端被强杀后记录被补投 | ① `SENDING` 长期 >0：确认 `OUTBOX_DISPATCH_ENABLED=true` 且投递任务在跑；② `PENDING` 积压：查 broker 可达性与交换机类型；③ `FAILED`：需人工介入，P4 的 retry-replay 接管；④ 队列堆积：先确认 `OUTBOX_DISPATCH_ENABLED=true` 与 `ack_required`，再看 `[release-listener]` 日志 |
| **I12** | **异步分诊必须能到达终态**（P5 步骤 3 补）：`triage_status` 只有三种去向——① 分诊成功 → `DONE`；② 用户填全/人工处理 → `DONE`；③ **重试阶梯走完仍未成功 → `FAILED`**（与账本转 `PARKED` **同事务**）。另外：`triage()` **拿不到结论必须抛异常，不得返回兜底值冒充结果** | ① 没有 ③ 这条路时，`PENDING` 会变成**事实上的终态**——`t_message_retry` 已 `PARKED`、没人会再改这张单，界面却永远显示"分类中"（假状态，且没人知道要介入）；② `triage()` 返回兜底值时，**LLM 一次没调通也会写成 `DONE`**，界面显示"已分类：其他/普通"——把"外部服务不可用"伪装成"AI 的判断"，且"失败→账本→阶梯→停车→FAILED"整条链一次都走不到（本轮实测：桩返回 401 → 工单 DONE、账本 0 行） | `MessageRetryService.recordFailure`（`attempt > MAX_ATTEMPTS` → `markParked` + `markTriageFailed`，同一 `REQUIRES_NEW` 事务，带 `AND triage_status='PENDING'` 守卫）、`WorkOrderMapper.markTriageFailed`、`OrderTriageServiceImpl.triage` 抛 `TriageUnavailableException`、`OrderTriageConsumeService` 失败即 `scheduleRetry` | **否（已落地，2026-09-25）**；未覆盖：`NOT_RETRYABLE` 分支（消息缺 `x-event-id` 时无法落账，工单会停在 `PENDING`——只记 ERROR 日志，等人工处理） | 探针 **P17**（`triage_status='PENDING'` 且创建超 1 小时 = 0；期望 0）+ **P16d**（`PARKED` 记录数，非 0 即需人工介入）。实测（本机真栈，2026-09-25）：桩 401 → 账本 1 行 + 工单 PENDING → 阶梯走完 → 账本 6/PARKED 且工单 FAILED；P17=PASS） | ① `PENDING` 长期存在：查 `OUTBOX_DISPATCH_ENABLED`、`[triage]` 日志、broker；② `FAILED`：查 `t_message_retry.last_error` 定位根因后按 README 排障小节重放（重放会把 `attempt` 归零） |

**三个时区探针的分工（P0a 补测补充：别把 P15b 通过当成"时区没问题"）**

**I11 的两条实测易错点（P1 步骤 3 真机实测，写在这里避免后人重踩）**

1. **延迟交换机 + `mandatory=true` 会给每条延迟消息一条假告警**：延迟插件的 route/2 对延迟消息返回"无立即路由"，
   于是 `mandatory=true` 触发 NO_ROUTE 退回（管理台 API 同样是 `routed=false`），但消息**并没有丢**——
   实测 t+30s 队列 0、t+62s 队列 1（`accept_minutes=1`）。因此 `spring.rabbitmq.template.mandatory` 必须是 `false`；
   判定"消息到底进没进队列"的唯一依据是**目标队列深度**与 **confirm ack**，不是 returns 回调。
2. **投递触发条件里不能有 `deliver_at <= NOW()`**：延迟由 broker 承担（`x-delay`）。
   若在取数时再卡一次 `deliver_at`，延迟永远等于 0，交换机里的延迟语义整体失效（D32/D36）。

| 探针 | 能抓什么 | 抓不到什么 | 用法约束 |
| --- | --- | --- | --- |
| **P15a-fresh** | **唯一能抓"JVM 比 MySQL 慢"的探针**——`created_at` 由 JVM 写入，JVM 慢 8 小时时最新工单会显示约 +28800 秒 | 需要"刚提交过工单"才有判定力；它读到的数值本质是"工单年龄"，工单一旧就失去意义（P0a 实测：压测结束 41 秒后运行得到 41） | **提交后 5 秒内**执行；期望 0–5 秒，接近 28800 立即停止并上报 |
| **P15a-future** | 只能抓"**JVM 比 MySQL 快**"——`created_at` 超前 DB 时间 | "JVM 慢"这一类（此时 `created_at` 在过去，future 值为负，看起来正常） | 随时可跑，与工单年龄无关 |
| **P15b** | **会话偏移的静态检查**：确认 DB 会话时区为 +08:00 | **任何运行期偏差**：它只反映 MySQL 自身配置，不知道 JVM 时钟；JVM 容器时区错配时它依旧 PASS | 随时可跑，但**不得单凭它判定"时区一致"** |

---

### 服务器批实测的验证结论（2026-09-24，真机，取代本机预演的推断）

本节记录"不变量在真实服务器上是否真的成立"，与 §一 的守卫设计、§四 的探针脚本对应。

| 不变量 | 真机验证结论 | 证据形态 |
| --- | --- | --- |
| **I4（`sla_deadline` 非空 / 启动期依赖可用性）** | **fail-fast 生效**：故意用错口令启动时，后端容器**以 `ExitCode=1` 退出**，随后被 `restart: unless-stopped` 拉起，形成**重启循环**。**关键观察：`Status=running` 只是重启循环的中间态**——只看 `docker ps` 会以为"服务正常" | 容器 `State.ExitCode=1` + `Restarts` 持续增长 + 日志里的失败横幅（`数据库不可用，拒绝启动`） |
| **I10（时间来源一致）** | **三方一致**：`P15a-fresh = 0 秒`、`P15b = -28800 秒`、`P15a-future = 0` | 三个探针在真机同一时点全部符合期望（本机只验证过前两者） |
| **正面验证（SLA 扫描 + 通知链路）** | **端到端跑通**：51 单提交 → **51 条站内信**，**全程无人工干预** | 数量守恒（工单数 = 通知数） |

**为什么这条正面验证值得单独记**：I4/I10 都是"防失效"型的守卫，而 SLA 扫描 → 通知是**功能链路是否真的跑起来**的证据。
在真机上"51 单对应 51 条站内信"说明：扫描任务确实被触发、SLA 判定确实命中、通知确实落库——**三者缺一都不可能数量相等**。

## 二、I4 完整修复方案（已定稿；实施在 P0a，本轮不修代码）

I4 是八条不变量里**唯一已经确认可达、且完全静默**的缺口，因此单独给出完整方案。

### (a) `submitOrder` 查不到配置时的行为：三个选项

| 选项 | 规则 | 理由 | 代价 |
| --- | --- | --- | --- |
| **a-1 拒绝提交** | 查不到 `type+priority` 配置时抛 `BizException`，不落库 | 绝不产生"无 SLA 工单"，把问题暴露在提交入口而不是静默沉底；与 R4 引入的 type 枚举校验天然一致 | 提交接口的可用性绑定到配置表的完整性：漏配一条就会让整类工单**提交不了**，报修人直接被挡住；对"必须在任何情况下都能报修"的后勤场景偏硬 |
| **a-2 用兜底配置** | 查不到时按内置兜底（`OTHER + 普通`，即 480 分钟）计算 `sla_deadline` | 提交永不失败，且工单**必然有** SLA；I4 在数据层面恒成立 | 隐藏配置缺失：漏配的类型会被当成"其他/普通"处理，紧急单可能被给出 8 小时时限；需要额外监控"兜底触发次数"否则无人知道配置漏了 |
| **a-3 落库但标记无 SLA** | `sla_deadline` 保持 NULL，同时写一个显式标记（如 `sla_status='NO_CONFIG'`）并在详情页与列表中可见，后台产生告警 | 不阻断报修，也不掩盖问题；把静默失效变成显式可见 | 需要新增字段（改 DDL，按 `CLAUDE.md` 第 4 节要成套交付）；且"NULL + 标记"仍不满足 I4 的字面要求，必须同时把 I4 改写成"`sla_deadline` 非空 **或** 显式标记无 SLA"，等于放宽不变量 |

**已定稿（2026-09-23）：采用 a-2（兜底配置）**，并配合 type 枚举校验把非法类型挡在入口。不采用 a-1（拒绝提交）：它把接口可用性绑在配置表完整性上，漏配一条就让整类工单**报修不了**，对"任何时候都必须能报修"的后勤场景过硬。不采用 a-3（标记无 SLA）：需要改 DDL 加字段，且等于把 I4 放宽成"`sla_deadline` 非空**或**显式标记无 SLA"。a-2 的代价是可能把紧急单按普通处理（8 小时时限），因此**必须**同时落地「兜底触发计数」（F1-1 情形二）与启动自检（I5 的 `ensureSlaConfigComplete()`），否则配置漏配会从"静默无 SLA"变成"静默降级"。**本轮仍不实施，实施在 P0。**

### (b) 是否需要为 `type` 增加枚举值校验

**需要，已定稿。** 依据：`submitOrder` 当前直接使用请求里的 `type`，没有任何白名单校验（`WorkOrderServiceImpl.java:73-91`），因此**通过 API 传入任意字符串即可造出无 SLA 工单**——这是 I4 当前唯一确定可达的路径。R4 已经批准把类型替换为 `NETWORK/UTILITY/DORM/OTHER`，校验与替换是同一件事：新枚举落地时若不校验，等于换了个拼写继续漏。

代价：新增一个校验点，且**必须与 `t_sla_config` 的 8 条配置同时上线**（先上校验后上配置，会让新类型的工单全部被拒）。

### (c) 已存在的 `sla_deadline IS NULL` 历史数据如何处置

| 步骤 | 动作 | 说明 |
| --- | --- | --- |
| 0 | **P4 探针已执行（2026-09-23），且残留已按下方策略清理完毕** | 清理前：`t_work_order` 共 111 行；全状态 `sla_deadline IS NULL` = **108** 行；未完结口径（P4）= **104** 行；**108 行全部是 `TST-%` 测试残留**（来源：`WorkOrderFlowServiceTest.java:46` 用 `"TST-" + UUID` 直接插入），分布在 2026-06-11 的 4 个批次（每批 27 行，对应 27 个 `@Test`）；经应用路径产生的 `WO-%` 工单 3 行中 **NULL 数为 0**。**清理后：工单 3 行、P4 = 0、`TST-` 在工单表与日志表均为 0。**<br>**反转留痕**：清理时**原以为**关联日志是 311 条（用 `order_id JOIN 现有工单` 统计）→ **后来发现**日志表冗余列 `order_no` 上有 **1343** 条 `TST-`，差额 1032 条是 `order_id` 已不存在的历史孤儿日志 → **因此实际删除 1343 条，但留档只覆盖了 311 条**。影响评估：全部为测试夹具数据（`data-generator.sql:55` 只生成 `WO-` 前缀，`TST-` 仅由测试产生），3 条真实工单及其 16 条日志完好，无业务损失；1032 条未留档即删除，不可恢复。 |

#### I4 的证据基础（收口 1：原引用的测试是空测试，已更换）

**原依据作废**：`INVARIANTS.md` 早期版本引用 `WorkOrderServiceTest.java:87` 的 `testSubmitOrder_slaDeadlineNull` 作为"该行为由测试断言证明"。**该依据无效**——实测该方法（`:85-94`）虽然 `@DisplayName` 写着"SLA配置不存在时deadline为null"，但：

- 方法体用的是 `buildReq("特殊工单", "无匹配SLA", "OTHER", 0)`，而 `OTHER/0` **在 `t_sla_config` 中存在**（配置行 id=7，`finish_minutes=480`），根本走不到"配置缺失"分支；方法内注释自己写着"OTHER/0 在 SLA 表中存在"；
- 全文没有任何关于 `slaDeadline` 的断言，只有 `assertNotNull(order)` 与 `assertNotNull(order.getOrderNo())`；
- 因此它**既没覆盖缺失配置路径，也不会因为修复 I4 而失败**——它是空测试。

**更换后的证据：三条代码事实。**

| # | 代码事实 | 位置 | 说明 |
| --- | --- | --- | --- |
| 1 | `sla_deadline` 无 null 守卫 | `WorkOrderServiceImpl.java:93-96`（`LocalDateTime slaDeadline = null;` + 仅在查到配置时赋值）、`:108`（无条件写库） | 查不到配置时把 `null` 落库，不抛异常、不记日志 |
| 2 | `type` 无枚举校验 | `WorkOrderServiceImpl.java:73-91` | 直接使用请求里的 `type`，任意字符串都能落库，是"配置必然查不到"的可达路径 |
| 3 | 扫描 SQL 对 NULL 永不匹配 | `WorkOrderMapper.xml:25`（`AND sla_deadline < NOW()`） | NULL 与任何值比较结果为 unknown，该工单永不进入 SLA 扫描结果 |

**与 I10 的关系**：本条的失效方式是"`sla_deadline` 为空 → 永不参与比较"，I10（时间来源不一致）的失效方式是"比较了，但两侧时钟差 8 小时 → 结论全错"。两者都不会报错，都会让 SLA 机制整体失灵；修复本条时**必须同时确认 I10**，否则修好 NULL 之后仍会因为时区不一致而得到错误的超时判定。

**新增待办（P0a 第 8 项）**：重写 `testSubmitOrder_slaDeadlineNull`，使其真正断言"配置缺失时的行为"——修复后应断言**得到兜底值**（`OTHER` + 普通，即 `created_at + 480 分钟`）而非 `null`，并在修复前断言当前会落 `null`，以证明缺口存在。
#### 处置策略（收口 4：撤回"预置去重标记"方案）

**撤回说明**：v1.1 曾写入"实测 > 0 → 按 H1 的 `eventVersion` 机制预置去重标记以抑制首次告警"。**该方案已撤回并删除**，理由：它是为测试残留量身定做的——对真实业务数据，超时告警本就是正确行为，抑制它是错的。随之删除的还有"P0 预置键 vs P4 幂等表的阶段依赖"说明：不再需要预置，该依赖自然消失。

| 数据类型 | 处置 | 理由 |
| --- | --- | --- |
| **测试残留**（`order_no LIKE 'TST-%'`，本库 108 条） | **删除**（不是回填、不是标记） | 无业务含义：单号格式与真实工单的 `WO-%` 不同、`submitter_id` 固定为 10。删掉即可；任何"让它们继续参与扫描"的动作都是在维护垃圾数据 |
| **真实业务数据**若将来发现 `sla_deadline IS NULL` | **回填** `created_at + 对应 finish_minutes`；由此产生的超时**按正常流程告警，不做任何抑制** | 超时是事实，告警是正确行为；抑制告警会把真实问题盖住 |

**保留的提醒**：回填真实 NULL 数据时会产生一批历史超时告警。这与"抑制告警"是两件事——正确做法是**提前告知业务方**这批告警的来源，而不是把告警关掉。

**例行检查**：修复后把探针 **P4**（事后型）与 **P11**（事前型）纳入例行检查，否则缺口会重新长出来。


---

## 三、测试污染根因与修法（收口 3 / I9）

**这是类级问题，不是数据问题。** 只删数据不修测试，残留会在下一次跑测试时长回来——本库的 108 条 `TST-%` 就是这么来的，且是 4 个批次累积的。

### 根因（两处，均为类级）

| 位置 | 写法 | 为什么留下数据 |
| --- | --- | --- |
| `WorkOrderFlowServiceTest.setUp`（`:44-64`） | `transactionTemplate.execute(status -> { ... workOrderMapper.insert(order); ... })` | `TransactionTemplate` 的默认行为是**提交**：它只保证"回调内的异常回滚"，而正常返回就提交。加上没有 `@Transactional`（Spring Test 的测试级回滚）也没有 `@AfterEach` 清理，每跑一次测试就永久写入 1 行 `TST-` 单 |
| `WorkOrderServiceTest` | 直接调用 `workOrderService.submitOrder(...)`（走真实事务并提交） | 同上：这是"对真实库做写操作"的集成测试，没有隔离库、没有清理 |

### 修法（三选一，必须落在类级）

| 方案 | 做法 | 优点 | 代价 |
| --- | --- | --- | --- |
| 独立测试库 | 给测试配置单独的库（如 `work_order_test`），测试启动时用 `sql/init.sql` 建库 | 彻底隔离，业务库永不被污染 | 需要维护第二套库与连接配置；CI 需要预先建库 |
| 类级 `@Sql` 清理 | 类上加 `@Sql(scripts=".../cleanup-test-data.sql", executionPhase=AFTER_CLASS)` | 改动小，与现有结构兼容 | 清理脚本一旦漏写表就再次污染；跨类并行时仍有窗口 |
| `@AfterEach` 按前缀删除 | 每个测试类记录自己写入的单号前缀，在 `@AfterEach` 删除 | 精确、无跨类影响 | 每个类都要写；要用 `@Transactional` 或显式清理，容易漏 |

**判断标准**：修完之后，**连续跑两轮 `mvn test`，业务库的行数不变**。这是 I9 的验收方式，也是 P13 探针的内容。

### 对照记录：本项目已有正确做法的先例

2026-09-23 验证 `sql/init.sql` 语法时采用的正是正确姿势——**建临时库 `wo_syntax_check` → 导入 → 校验结果 → `DROP DATABASE`**，验完即删、业务库零影响。同一份代码库里已经有人知道该怎么做，测试类只是没照做。

### C1 诊断结果（P0a 实测，13 个测试类逐类判断）

| 测试类 | 是否写库 | 是否需要真实提交 | 选用的隔离方案 |
| --- | --- | --- | --- |
| `BCryptHashGeneratorTest` | 否（纯编码器） | 否 | 无（不涉及 Spring/DB） |
| `StateMachineValidatorTest` | 否（纯逻辑） | 否 | 无 |
| `OrderTriageServiceTest` | 否（Mockito） | 否 | 无（`@ExtendWith(MockitoExtension)`） |
| `WorkOrderSubmitValidationTest`（P0a 新增） | 否（Mockito） | 否 | 无（mock mapper，Redis 也是 mock） |
| `SlaConfigStartupCheckTest`（P0a 新增） | 否（Mockito） | 否 | 无 |
| `WorkOrderMapperTest` | 是（mapper 直插） | 否 | 类级 `@Transactional` 回滚 |
| `NotificationServiceTest` | 是 | 否 | 类级 `@Transactional` 回滚 |
| `PermissionServiceTest` | 是 | 否 | 类级 `@Transactional` 回滚 |
| `RoleServiceTest` | 是 | 否 | 类级 `@Transactional` 回滚 |
| `UserServiceTest` | 是 | 否 | 类级 `@Transactional` 回滚 |
| `SlaEscalationSchedulerTest` | 是（直插工单） | 否 | 类级 `@Transactional` 回滚 |
| `WorkOrderServiceTest` | 是（走 `submitOrder`） | 否（**它与被测代码共享事务，回滚即可**） | 类级 `@Transactional` 回滚 |
| `WorkOrderFlowServiceTest` | 是（`transactionTemplate.execute` 提交） | **是**——各测试方法要在另一个事务里看到 `setUp` 插入的工单 | **独立测试库** + test profile |
| `OrderNoGeneratorTest` | 否（只写 Redis） | 否 | 无 DB 需求；Redis 键按日期递增，天然可重复 |

**修正一条上一轮的结论**：早期判断写的是"至少两个测试类存在该问题（`WorkOrderFlowServiceTest` 与 `WorkOrderServiceTest`）"。**实测表明只有 `WorkOrderFlowServiceTest` 会污染业务库**——`WorkOrderServiceTest` 有类级 `@Transactional`（`:30`），它调用 `submitOrder` 时与被测代码**共享同一事务**，测试结束整体回滚，不产生持久数据。108 行残留的构成也印证了这一点：**4 批次 × 27 行 = 108**，而 `WorkOrderFlowServiceTest` 恰好有 27 个 `@Test`。

**落地方式（比逐类加注解更彻底的方案）**：`src/test/resources/application.properties` 一行 `spring.profiles.active=test`，让**全部** `@SpringBootTest` 都连 `work_order_test`。这样"业务库被测试写入"从"靠 `@Transactional` 记得回滚"变成"物理上不连它"；类级 `@Transactional` 仍然保留，用于测试之间的隔离。

---

## 四、探针 SQL 与校验清单

### P1 · 至少一名启用的 SYS_ADMIN

```sql
SELECT COUNT(*) AS enabled_sys_admin
FROM t_user u
JOIN t_user_role ur ON ur.user_id = u.id
JOIN t_role r ON r.id = ur.role_id
WHERE u.status = 1 AND r.role_code = 'SYS_ADMIN';
-- 期望：>= 1
```

### P2 · 造物主账号（ID=1）持有 SYS_ADMIN

```sql
SELECT COUNT(*) AS creator_is_sys_admin
FROM t_user_role ur
JOIN t_role r ON r.id = ur.role_id
WHERE ur.user_id = 1 AND r.role_code = 'SYS_ADMIN';
-- 期望：= 1
```

### P3 · 4 个内置角色存在

```sql
SELECT COUNT(*) AS protected_roles
FROM t_role
WHERE role_code IN ('SUBMITTER','HANDLER','DEPT_ADMIN','SYS_ADMIN');
-- 期望：= 4
```

### P4 · 未完结工单的 sla_deadline 非空（I4）

```sql
SELECT COUNT(*) AS orders_without_sla
FROM t_work_order
WHERE sla_deadline IS NULL
  AND status NOT IN ('CLOSED','RELEASED');
-- 期望：0
```

### P5 · SLA 配置覆盖工单中出现的全部 (type, priority)（I5，**事后型**）

```sql
SELECT DISTINCT w.type, w.priority
FROM t_work_order w
LEFT JOIN t_sla_config c
       ON c.type = w.type AND c.priority = w.priority
WHERE c.id IS NULL
  AND w.status NOT IN ('CLOSED','RELEASED');
-- 期望：空结果集
```

### P11 · `t_sla_config` 自身是否覆盖 4 类 × 2 优先级（I5，**事前型**）

```sql
SELECT COUNT(*) AS sla_config_rows
FROM t_sla_config
WHERE type IN ('NETWORK','UTILITY','DORM','OTHER')
  AND priority IN (0, 1);
-- 期望：= 8
```

**P11 与 P5 的分工**：P11 只看配置表自身，**不依赖任何工单数据**，因此可以在应用启动前、数据为空时执行，是"事前型"把关；P5 反查"工单里出现过但配置表没有"的组合，只能在实际产生工单后执行，是"事后型"兜底。两者都必须为空/达标，缺一不可——只有 P5 时，一个尚未被提交的新类型可以长期漏配而不被发现；只有 P11 时，配置表可能被改回旧类型集合而工单数据里已经存在新类型。

对应的运行时守卫是 `ensureSlaConfigComplete()`（`@PostConstruct`，见 I5），P11 是它的离线等价物。

### P6 · PENDING 工单的 assignee 必须为空（I6）

```sql
SELECT COUNT(*) AS pending_with_assignee
FROM t_work_order
WHERE status = 'PENDING' AND assignee_id IS NOT NULL;
-- 期望：0
```

### P7 · 注解引用的权限码是否都存在于 t_permission（I7 正向）

**适用范围（必须先读）**：截至 2026-09-23，全仓库 `@SaCheckPermission("...")` 注解共 **19 处，全部为字面量单值形式**（如 `@SaCheckPermission("order:accept")`），分布为：`AdminController` 6 处、`RoleController` 7 处、`WorkOrderController` 6 处。另有 **1 处程序式调用** `StpUtil.checkPermission("order:stats:all")`（`AdminController.java:103`），不在注解形式内，需单独纳入比对集合。

**计数更正（收口 2）**：早期版本写"20 处"，是把 `GlobalExceptionHandler.java:26` 的**注释行**也算进去了——那行是 `/** Sa-Token 权限不足（@SaCheckPermission 拦截）→ 统一返回 403 ... */`，只是 javadoc 里提到了注解名，不是真实使用点。若照旧数 20，会让"代码引用 vs 库中定义"的差集比对出现一个永不匹配的幻影项。

**若将来出现数组形式（`@SaCheckPermission({"a","b"})`）或 `value =` 命名参数形式，本探针的 `grep` 正则必须同步调整**，否则会静默漏检——这类"探针自己失效"的情况比权限码漏配更隐蔽。同时注意：包含注解名的**注释行**会被宽泛的正则误命中（本轮就是这么错的），提取语句需排除注释行。

```bash
# 从代码中提取全部被引用的权限码，与库中做差集
grep -rhoP '@SaCheckPermission\("\K[^"]+' src/main/java | sort -u > /tmp/code_perms.txt
# 程序式调用需单独追加
grep -rhoP 'checkPermission\("\K[^"]+' src/main/java | sort -u >> /tmp/code_perms.txt
sort -u /tmp/code_perms.txt -o /tmp/code_perms.txt
mysql -N -e "SELECT perm_code FROM t_permission WHERE perm_code NOT LIKE '%:*'" work_order | sort -u > /tmp/db_perms.txt
comm -23 /tmp/code_perms.txt /tmp/db_perms.txt   # 代码引用了但库里没有 → 期望为空
comm -13 /tmp/code_perms.txt /tmp/db_perms.txt   # 库里定义了但代码没用 → 期望为空（G3 修复后）
```

### P8 · 库中定义的权限码是否都被真实消费（I7 反向）

```sql
-- 列出定义了但没有任何接口消费的权限码（人工与 P7 的第二个 comm 结果对照）
SELECT p.perm_code, p.perm_name, p.parent_id
FROM t_permission p
WHERE p.perm_code NOT LIKE '%:*'
ORDER BY p.id;
-- 期望：每一行都能在代码中找到对应的 @SaCheckPermission；菜单类权限（以 :* 结尾）除外
```

### P9 · 配置项与实际生效项一致（I8）

```sql
-- SLA 配置表里被声明为可配置的字段
SELECT type, priority, accept_minutes, finish_minutes FROM t_sla_config ORDER BY type, priority;
-- 人工核对：accept_minutes 在当前代码中无任何消费者（G5）；
--           max_reject=3 等仍是硬编码常量（已在 I8 行逐个标注），不在本表内
```

### P10 · 状态机守卫（回归用，非 SQL）

`StateMachineValidatorTest` 已覆盖非法状态转移；F5-2 回池改造后**必须新增** `RELEASED → PENDING` 的用例，否则 I6 与 G1 都可能在改造中被破坏。

### P12 · 部署冒烟：全新库导入后种子管理员必须能登录（收口 5）

```bash
# 1) 建临时库并导入（正确姿势：验完即删，见 §三 的对照记录）
mysql -e "CREATE DATABASE IF NOT EXISTS wo_p12_check"
mysql wo_p12_check < sql/init.sql

# 2) 取出种子管理员的哈希，确认它与 README 标注的明文一一对应
mysql -N -B -e "SELECT password FROM wo_p12_check.t_user WHERE username='admin'"
#    期望：bcrypt.checkpw(b'admin123', 该哈希) == True

# 3) 起后端，用 admin + README 标注的密码调一次登录接口
curl -s -X POST http://localhost:9000/api/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}'
#    期望：返回 token（HTTP 200 且 body.code 表示成功），不是 401/500

# 4) 清理
mysql -e "DROP DATABASE wo_p12_check"
```

**判断标准**：第 3 步必须成功。这一条同时兜住三件事——密码 seed 是否正确、种子数据是否完整（角色/权限绑定齐全才能登录）、以及"演示前系统能不能跑起来"。

**2026-09-23 执行记录**：第 1、2 步已执行并通过——临时库导入 `exit=0`（建 9 张表），`t_user` 中 `admin` 的哈希经 BCrypt 校验为 `admin123`（对 `123456` 校验为 False），临时库已 `DROP`。第 3 步（HTTP 登录）需后端运行，**尚未执行**，属于 P0a 的验收动作。

### P13 · 测试不污染业务库（I9）

**2026-09-23 执行结果**：`P13a`（工单表 `TST-` 残留）= **0**、`P13b`（日志表 `TST-` 残留）= **0**，
均已并入 `sql/probes.sql` 一键执行。业务库行数在测试前后不变（111 → 111）；隔离已由
`src/test/resources/application.properties` 的 `spring.profiles.active=test` 落地。

```bash
# 连续跑两轮测试，业务库行数必须不变
mysql -N -B -e "SELECT COUNT(*) FROM work_order.t_work_order" > /tmp/before.txt
mvn test > /dev/null 2>&1
mvn test > /dev/null 2>&1
mysql -N -B -e "SELECT COUNT(*) FROM work_order.t_work_order" > /tmp/after.txt
diff /tmp/before.txt /tmp/after.txt && echo "PASS: 测试未污染业务库" || echo "FAIL: 测试写入了业务库"

# 残留检查（期望 0）
mysql -N -B -e "SELECT COUNT(*) FROM work_order.t_work_order WHERE order_no LIKE 'TST-%'"
```

**说明**：本探针在测试类修复（P0a 第 7 项）完成前**必然 FAIL**——实测残留 108 条即为证据。修复完成的定义就是这条探针转为 PASS。

---

## 五、维护规则

1. **新增任何"必须永远成立"的条件，必须同时给出探针**；给不出探针的条件不允许写进本文件。
2. **任何改造如果可能破坏某条不变量，必须先在本文件登记缺口**，改造完成后再把"是否存在缺口"改回"无"。
3. **本文件与 `BUSINESS-SCOPE.md` 冲突时，以更严格的一方为准**并立即上报（`CLAUDE.md` §1 第 2 条）。
