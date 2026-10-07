# 架构决策记录（DECISIONS.md）

> 依据：`CLAUDE.md` §5「架构级决策要留痕」。本文件是**唯一的"当时为什么这么想"的记录来源**。
> 格式约定：每条一个二级标题，字段固定为 编号 / 日期 / 问题 / 备选项 / 选择 / 理由 / 代价 / 关联文档。
> **反转必留痕**：凡发生过认知反转的条目，必须显式写出"原以为 X → 后来发现 Y → 因此改为 Z"。反转让后来的读者知道边界在哪，比"一步到位"的叙述更可信。
> 编号按实际发生顺序；日期为该决策**生效**的日期（同日多次修订取最后一次）。

---

## D01 · 在现有工单系统上改造，而不是重写

- **日期**：2026-09-22
- **问题**：场景要从"模拟企业内部工单"改成高校后勤报修，是重写一套还是改造现有仓库？
- **备选项**：① 按新场景重写；② 在现有代码上改造；③ 保留现状只换文案
- **选择**：② 改造
- **理由**：现有代码已具备可直接复用的硬核部分——并发抢单的原子 SQL（`WorkOrderMapper.grabOrder`）、状态机与乐观锁（`StateMachineValidator` + `version` 字段）、RBAC 与权限码、AOP 操作日志。这些与场景无关，重写等于把已验证的并发正确性再赌一次。
- **代价**：必须背下历史包袱——`RELEASED` 死路、日志接口越权、三个权限码不生效等缺口都是既有代码带来的，得逐个还债（见 `INVARIANTS.md` I1–I9）。
- **关联文档**：`PROJECT_MAP.md`、`BUSINESS-SCOPE.md` §1

---

## D02 · 场景重定位为高校后勤 / IT 报修，类型枚举替换为报修四类

- **日期**：2026-09-22（场景定稿）/ 2026-09-23（类型枚举裁决 R4）
- **问题**：原场景是"模拟企业内部工单"，工单类型是 `REPAIR/LEAVE/REIMBURSE/OTHER`（含请假与报销）。
- **备选项**：① 保留原类型；② 换成报修四类；③ 类型做成完全可配置
- **选择**：② 替换为 `NETWORK` 网络故障 / `UTILITY` 水电故障 / `DORM` 宿舍与公区维修 / `OTHER` 其他（兜底）；**保留 4 类 × 2 优先级 = 8 条 SLA 配置**
- **理由**：请假与报销属于人事/财务系统，后勤报修场景不存在；而"网络/水电/宿舍"才是值班员每天真正要分派的类别。保持 4×2=8 条配置是刻意的——**行数不变**，避免引入"配置表要不要扩行"的新变量。
- **代价**：存量工单的类型值需要迁移（清理测试残留后仅 3 行，成本极低）；`sql/data-generator.sql:73` 的类型集合必须同步，否则造数脚本产出配置表覆盖不到的类型。
- **关联文档**：`BUSINESS-SCOPE.md` §2.6 O4、`INVARIANTS.md` I5

---

## D03 · 引入真实 RabbitMQ（不再维持 Mock）

- **日期**：2026-09-22
- **问题**：`MessagePublishService` 只有 `MockMessagePublishServiceImpl`（打日志），是否要接真实 MQ？
- **备选项**：① 维持 Mock；② RabbitMQ；③ Kafka；④ RocketMQ；⑤ 只用 outbox 表 + 调度轮询（不引入 MQ）
- **选择**：② RabbitMQ
- **理由**：本项目的需求是"低吞吐（≤10 msg/s）+ 高可靠 + 多档延迟 + 灵活路由 + 内存受限（2C4G）"。RabbitMQ 在前四项够用，第五项是唯一可行项；Kafka 的核心优势（回放、海量吞吐、流处理）本项目一项都用不上，却要 1G+ 起步；RocketMQ 的 NameServer + Broker 双进程在 4G 上与其他组件争抢后没有余量。
- **代价（三条，必须一起接受）**：① 吞吐上限远低于 Kafka（单队列单节点万级 msg/s，本项目够用）；② 延迟消息依赖社区插件 `rabbitmq_delayed_message_exchange`，且延迟消息存放在交换机内部、**不被队列积压指标覆盖**；③ management 插件 + Erlang 运行时使空载常驻 180–260M（该值本身是本表最可疑的低估项，见 `ASYNC-SCHEDULING-PLAN.md` §1.2 待实测标注）。
- **关联文档**：`ASYNC-SCHEDULING-PLAN.md` §4.1、§4.2

---

## D04 · 引入 XXL-Job 2.4，并**保留 `@Scheduled` 兜底默认开启、与调度中心并行**

- **日期**：2026-09-22（引入 XXL-Job）/ 2026-09-23（兜底开关反向）
- **问题**：定时任务用 XXL-Job、PowerJob 还是自建？迁移后进程内的 `@Scheduled` 兜底要不要保留、默认开还是关？
- **备选项**：① XXL-Job；② PowerJob；③ 自建 `@Scheduled` + 分布式锁；兜底则分"默认开/默认关"
- **选择**：XXL-Job 2.4.x；**本地 `@Scheduled` 兜底默认开启，与 xxl-job 长期并行**
- **理由**：选 XXL-Job 是因为 `pom.xml` 已声明该依赖、admin 是单 jar 可塞进 4G、有手动触发与执行日志（演示与排障刚需）、分片广播正好匹配归档。兜底默认开启的理由更硬：`releaseOrder` 靠 `WHERE status='ACCEPTED'` 天然幂等，**重复触发的唯一代价是日志噪音**，而"需要人工判断的开关"在凌晨故障时没人会去开。
- **反转留痕**：**原以为**"兜底应默认关闭，只在 admin 挂了时人工开启，以免两套调度重复触发" → **后来发现**这个设计与本方案的可靠性承诺直接矛盾：P1 阶段承诺的"停 MQ 仍能释放"其实现就是那个 `@Scheduled`，默认关掉等于把承诺的实现关掉；而且故障时没人会去改配置 → **因此改为**默认开启、与 xxl-job 并行。
- **代价**：两套调度并行会产生重复触发（靠状态守卫吸收，代价是日志噪音）；admin 迁移的那一阶段存在**可靠性净倒退**（从"停 MQ 不丢"变成"停 MQ 且 admin 健在才不丢"），必须靠并行兜底 + P7 的组合故障演练堵住。
- **关联文档**：`ASYNC-SCHEDULING-PLAN.md` §4.3、§5.6、P2、P7

---

## D05 · 明确不引入的中间件与能力清单

- **日期**：2026-09-22
- **问题**：哪些看起来"更企业级"的东西明确不做？
- **备选项**：全量引入 / 按需引入 / 明确清单化
- **选择**：不引入 **Kafka、RocketMQ、PowerJob、MongoDB、Elasticsearch、Seata、任何配置中心**；业务上不做 **多租户/多校区、短信与邮件通道、移动端 App/小程序、统一身份认证对接、实时推送（WebSocket/SSE）、自动派单算法、工单转派、满意度评价、报表文件导出（Excel/PDF）**
- **理由**：每条都有代价视角的理由而非"时间不够"——例如 ES 在 4G 上要额外吃 1G+ 且需索引同步，而搜索需求走 `order_no LIKE` + 状态过滤在 10⁵ 行量级完全够用；自动派单需要技能矩阵、地理分区、排班数据，这三类数据本系统都不持有，**错误的自动派单比人工指派更糟**；分布式事务框架在单库事务内无适用场景。
- **代价**：已知缺口被保留并且必须写明代价——**N1 附件不做 → 报修人仍需在微信群补图**；**N2 短信/邮件不做 → 夜间与现场作业没有短信兜底**（只靠单渠道 webhook）。
- **关联文档**：`BUSINESS-SCOPE.md` §2.8 / §4、`README.md` §四

---

## D06 · outbox 表作为跨事务投递的唯一方式

- **日期**：2026-09-22
- **问题**：业务提交成功后要对外发消息，`afterCommit → convertAndSend` 存在"commit 成功但消息未发出"的窗口，怎么补？
- **备选项**：① `afterCommit` 直发 + 失败落补偿表；② 事务内写 outbox + 独立投递任务；③ 分布式事务
- **选择**：② outbox
- **理由**：`afterCommit` 回调只在提交后执行一次，进程崩溃/被 OOM Killer 杀掉，消息**永久丢失且没有任何记录**；Mock 实现下这个漏洞不可见（只打日志），接真实 MQ 第一天就会成为线上问题。outbox 把"业务写"与"事件写"放进同一事务，投递由独立任务从表里拉，**只有 publisher-confirm 返回 ack 才标记 SENT**。
- **代价**：每个事件多一次 DB 写（约 +1–2ms）与一张持续增长的表（需归档清理）；投递延迟增加一个扫描周期（5–10s）；投递任务本身要防多实例并发（`SKIP LOCKED` 或状态抢占）。
- **副作用收益**：broker 内存水位触顶时是**阻塞生产者**而非拒绝消息——因为投递不再由用户请求线程发起，被阻塞的是投递任务线程，用户提交不受影响（`ASYNC-SCHEDULING-PLAN.md` §5.7）。
- **关联文档**：`ASYNC-SCHEDULING-PLAN.md` §5.1、§5.7、`CLAUDE.md` §3 架构不变量第 2 条

---

## D07 · 幂等键采用事件维度 `{aggregate}:{id}:{version}:{eventType}`

- **日期**：2026-09-22
- **问题**：消费端去重键用什么粒度？
- **备选项**：① 业务实体维度（如 `sla_notified:{orderId}`，即现状实现）；② 事件维度（含 `version`）
- **选择**：② 事件维度
- **理由**：一张工单会被多次接单、多次超时释放，用实体维度去重会把**同一工单的第二次合法事件**当成重复消息吞掉——这是漏发，比重复更危险，因为它静默。现状 `sla_notified:{orderId}` 正是这个错误：它会把"SLA 超时"与"驳回超限"两类不同事件互相吞掉。
- **代价**：键更长、需要生产端在生成事件时确定版本并写进 outbox（消费端不得自行推导，否则重投时算出的版本可能不同，去重失效）；去重记录需持久化（MySQL 去重表，而不是只靠 Redis TTL）。
- **关联文档**：`ASYNC-SCHEDULING-PLAN.md` §5.2、`CLAUDE.md` §3 第 3 条

---

## D08 · SLA 告警政策：分级催办，而非"只发一次"

- **日期**：2026-09-23
- **问题**：SLA 超时后对管理员的告警节奏怎么定？
- **备选项**：① 每张工单只发一次；② 每满 24 小时重复催办直至完结；③ 固定间隔反复轰炸
- **选择**：② 分级催办——首次告警后**每满 24 小时再告警一次**，工单完结（`CLOSED`/`RELEASED`）即停
- **理由**：原表述"同一工单 24 小时内只发一次"是照抄实现缺陷（`sla_notified:{orderId}` 键粒度错误），会把同一工单的第二次**合法**告警吞掉。24 小时的节奏则避免变成骚扰。
- **代价**：管理员会周期性收到同一工单的重复提醒（这是设计意图）；幂等键必须按 `eventVersion` 递增，**新增了"生产端确定版本"的实现要求**。
- **关联文档**：`BUSINESS-SCOPE.md` F5-3、`ASYNC-SCHEDULING-PLAN.md` §5.2 递增表

---

## D09 · H4 定稿：SLA 重算以 `created_at` 为基准，重算后已过期则立即告警

- **日期**：2026-09-23
- **问题**：AI 异步分类写回后若类型/优先级变化，`sla_deadline` 怎么重算？重算结果已过期怎么办？
- **备选项**：基准 ① `created_at` / ② 分类完成时刻；过期 ① 立即告警 / ② 顺延到最小宽限期
- **选择**：基准 **`created_at`** + 过期 **立即告警**（且该次告警**计为 H1 的首次告警**，24 小时节奏自该时刻起算）
- **理由**：SLA 的语义是"从报修那一刻起算"，与人何时分类无关；用分类完成时刻会让分类越慢、时限越宽松，**等于奖励系统自身的延迟**，异步链路一抖动就静默放宽 SLA。已过期时立即告警是因为超时是既成事实，隐瞒比告警更糟，而且它能把"分类太慢"暴露成系统健康信号。
- **代价**：用户等待分类的时间被计入时限（这是事实，不是系统制造的）；分类抖动会造成告警噪音（由 24 小时递增节奏封顶）。
- **关联文档**：`BUSINESS-SCOPE.md` F1-4 的 H4 区块

---

## D10 · I4 定稿：兜底配置 + type 枚举校验 + 启动自检

- **日期**：2026-09-23
- **问题**：`submitOrder` 查不到 `t_sla_config` 时把 `sla_deadline` 落 NULL，而 NULL 与任何值比较都是 unknown，**该工单永远不会进入 SLA 扫描、永远不告警、全程无异常无日志**。怎么修？
- **备选项**：① 拒绝提交；② 兜底配置（`OTHER` + 普通）+ 计数；③ 落库但显式标记无 SLA（需加字段）
- **选择**：② + **type 枚举校验前置** + **启动自检 `ensureSlaConfigComplete()`**
- **理由**：不选①是因为它把接口可用性绑在配置表完整性上——漏配一条就让整类工单**报修不了**，对"任何时候都必须能报修"的后勤场景过硬；不选③是因为要改 DDL 加字段，还等于把不变量放宽成"非空**或**显式标记"。启动自检是复用本项目既有的"启动自愈"约定（`UserServiceImpl.ensureAdminSurvival()`），把"漏配"从运行期静默降级变成**启动期显式报错**，兜底因此退化成最后一道保险而非常规路径。
- **代价**：兜底会把紧急单按普通处理（8 小时时限），所以必须同时有「兜底触发计数」与启动自检，否则配置漏配只是从"静默无 SLA"变成"静默降级"。
- **反转留痕（测试名不副实）**：**原以为** `WorkOrderServiceTest.testSubmitOrder_slaDeadlineNull`（`@DisplayName` 写"deadline为null"）能证明该行为存在 → **后来发现**它是空测试：方法体用 `OTHER/0`（该组合在配置表里存在，走不到缺失分支），且全文没有任何 `slaDeadline` 断言，只有 `assertNotNull(order)` → **因此改为**用三条代码事实作证据（`WorkOrderServiceImpl.java:93-96,108`、`:73-91`、`WorkOrderMapper.xml:25`），并把"重写该测试为真正断言"列入 P0a 第 8 项。
- **关联文档**：`INVARIANTS.md` I4、§2；`ASYNC-SCHEDULING-PLAN.md` P0a 第 5/6 项

---

## D11 · 抢单链路保持单条原子 SQL + 乐观锁

- **日期**：2026-09-22
- **问题**：多人同时抢单是并发竞争点，要不要上分布式锁或消息队列做最终一致？
- **备选项**：① Redis 分布式锁；② MQ + 最终一致；③ 单条原子 SQL + 乐观锁（现状）
- **选择**：③ 保持现状，**禁止**改为分布式锁或 MQ
- **理由**：`UPDATE ... WHERE assignee_id IS NULL AND status='PENDING'` 一条语句就完成"检查 + 更新"，影响行数即胜负判据，天然幂等；引入锁或 MQ 会把确定性竞争改成最终一致，引入"双人接单/重复接单"的新风险，收益为零、风险为正。
- **代价**：抢单的并发正确性依赖 SQL 的 `WHERE` 条件而非应用层逻辑——**任何放宽该 `WHERE` 的改动都必须视为高危**（已写入 `CLAUDE.md` §3 架构不变量第 5 条）。
- **关联文档**：`BUSINESS-SCOPE.md` §2.8、`TECHNICAL-PLAN.md` §3.2

---

## D12 · 实施顺序重排：P1 与 outbox 合并，P5 提到 P2 之前

- **日期**：2026-09-23
- **问题**：v1 的顺序是 P0→P1→P2→P3(outbox)→P4→P5→P6→P7，第一个业务亮点排在很后面。
- **备选项**：① 保持 v1 顺序；② P1 与 outbox 合并、P5 提前；③ 全并行
- **选择**：② **P0 → P1（含 outbox）→ P4 → P5 → P2 → P6 → P7**，合计 18 人日
- **理由**：v1 让 P1 先实现 `convertAndSend` 直发、再让 P3 改成 outbox，**P1 交付的那个类注定要被 P3 重写**，而且中间态恰好是双写漏洞的状态；合并后一次做对。P5（Triage 异步化）只依赖 MQ 与消费幂等，**不依赖 xxl-job**，把它提到 P2 之前才能让"提交接口 P99 从约 5 秒降到 100 毫秒量级"这个亮点尽早到达。
- **代价**：严格串行、并行度下降、总工期更长；非全职投入下 18 人日约合 7–12 周日历时间（口径见 `ASYNC-SCHEDULING-PLAN.md` §七）。
- **关联文档**：`ASYNC-SCHEDULING-PLAN.md` §七

---

## D13 · 部署基线 2C4G，余量结论由"内存不是瓶颈"下调为"够用但紧"

- **日期**：2026-09-23
- **问题**：v1 算出常驻 2.0–2.6G、余量 1.4–2.0G，结论是"内存不是瓶颈"。
- **备选项**：① 沿用 v1 结论；② 按 v1 自己的公式重算
- **选择**：② 重算，并下调结论
- **理由**：v1 声明的公式是"堆 + Metaspace + Code Cache + 线程栈 + JVM 固定开销(100–150M)"，但它给 512m 堆的后端只算了 550–700M、给 192m 堆的 admin 只算了 320–400M，**两个 JVM 组件都低于自己公式的下限**。按公式重算后常驻 **2.3–2.9G**，余量 **0.8–1.4G**（扣除内核与不可用内存后），**不到总内存的 1/3**。
- **代价**：P0 阶段不再预先抬高堆与 buffer pool（改为按需、按监控信号逐级上调），否则会在没有负载时先吃掉 0.25–0.3G 余量；新增"组合态实测"与"容器内 swap 有效性实测"两项 P0a 交付。**RabbitMQ 的 180–260M 被标为本表最可疑的低估项**，P0a 实测若不达标需回改本表与结论。
- **关联文档**：`ASYNC-SCHEDULING-PLAN.md` §1.2 / §1.3 / §1.4

---

## D14 · 测试残留采用**删除**，而非预置去重标记

- **日期**：2026-09-23
- **问题**：P4 探针实测出 104 条未完结工单的 `sla_deadline` 为 NULL（全状态 108 条），如何处置这些数据？
- **备选项**：① 按 `eventVersion` 机制预置去重标记以抑制首次告警；② 回填 `sla_deadline`；③ 删除
- **选择**：③ **删除**（针对测试残留）；并明确"真实业务数据若将来发现 NULL → 回填 + 超时按正常流程告警、**不做任何抑制**"
- **理由（含反转）**：**原以为**这 108 条是真实的业务 NULL，需要"抑制首次告警"来避免回填瞬间给管理员灌入一批历史告警 → **后来发现**它们 **100% 是 `TST-%` 前缀的测试夹具残留**（`WorkOrderFlowServiceTest.setUp` 用 `transactionTemplate.execute` 直接写业务库、提交不回滚、无清理，4 个批次各 27 条），经应用路径产生的 3 条 `WO-%` 工单**没有一条**是 NULL → **因此改为**删除，并撤回"预置去重标记"方案。对真实业务数据，超时告警本就是正确行为，**抑制它是错的**。
- **代价**：删除不可逆（建议删前 dump 留档）；同时必须**在类级修复测试**（独立测试库 / 类级 `@Sql` 清理 / `@AfterEach` 按前缀删除），否则残留会重新长出来——只删数据不修测试等于没修（见 `INVARIANTS.md` I9）。
- **关联文档**：`INVARIANTS.md` I4(c)、§三、I9；`ASYNC-SCHEDULING-PLAN.md` P0a 第 7/9 项

---

## D15 · 简历可用检查点定在 P0a + P0b + P1 完成时

- **日期**：2026-09-23
- **问题**：什么时候可以把"可靠投递/success 故事"写进对外材料？
- **备选项**：① 全部阶段完成；② P0a+P0b+P1 完成；③ P5 完成
- **选择**：② **P0a + P0b + P1 完成时**先写"可靠性故事"（outbox 不丢、幂等不重、兜底不漏）；**P5 完成后再升级**描述（加上"提交 P99 从 5s 到 100ms"的异步化成果）
- **理由**：P1 完成时"消息不丢 + 重复不放大 + MQ 挂了有兜底"这三件事已经可以演示与自证，故事是完整的；而 P5 的异步化亮点要等到那时才有实测数据支撑。**提前写没有数据支撑的成果，会在追问下崩掉**。
- **代价**：简历与对外材料的更新时间被拆成两次，需要维护两个版本的描述；`docs/INTERVIEW-*.md` 与 `docs/PERFORMANCE-TUNING.md` 描述的是改造前系统，每次行为变更后必须同步修订（`CLAUDE.md` §4）。
- **关联文档**：`ASYNC-SCHEDULING-PLAN.md` §七、`CLAUDE.md` §4

---

## D16 · 密码 seed 唯一来源：只保留一条 `INSERT`

- **日期**：2026-09-23
- **问题**：`sql/init.sql` 里 `admin` 的密码有两处写入——一条 `INSERT`（注释写 `admin123`，实际哈希是 `123456`）和一段位于文件末尾的 `UPDATE`（把密码改成 `admin123`）。全新库的真实密码无人知道，注释具误导性。
- **备选项**：① 保留两段、把值改成一致；② 只留 `INSERT` 并写明明文；③ 用 `BCryptHashGeneratorTest` 重新生成一个新哈希
- **选择**：② 只留一条 `INSERT`，哈希与 `admin123` 一一对应
- **理由**：先用 `bcrypt.checkpw` 验证了两条哈希的真实明文（`:112` 是 `123456`、末尾 `UPDATE` 是 `admin123`），因此不需要重新生成哈希；保留末尾那条的哈希、删除前一条与整段 `UPDATE`，使**全新库导入后的密码与注释、README 三者一致**。判断标准是"种子管理员必须能登录"（探针 P12）。
- **代价**：删除 `UPDATE` 后，若历史环境曾依赖"重置 admin 密码"的副作用，需改为手工 SQL；`deploy/README.md:118` 已说明"改种子 admin 密码用 SQL 改 `t_user.password`"。
- **反转留痕（本文件诞生的直接原因）**：**原以为**收口 5 已"删除覆盖用 UPDATE、只留一条 INSERT"，交付报告也这么写了 → **后来发现**文件里其实有**两段** `UPDATE t_user SET password`，只删掉了前面那段，末尾那段仍在，报告与文件不符 → **因此改为**第二轮真正删除尾段，并推动 `CLAUDE.md` §6 新增第 5 项「回读校验」：**报告中的每一句断言都必须能被一条命令或一次阅读验证**。
- **关联文档**：`sql/init.sql:110-116`、`README.md` §三、`CLAUDE.md` §6 第 5 项、`INVARIANTS.md` P12

---

## D17 · 测试隔离：全部 `@SpringBootTest` 指向独立测试库

- **日期**：2026-09-23
- **问题**：`WorkOrderFlowServiceTest` 需要真实提交（各测试方法要在另一个事务里看到 `setUp` 插入的工单），无法用 `@Transactional` 回滚，历史上直写业务库并留下 108 行 `TST-` 残留。怎么隔离？
- **备选项**：① 只给"需要真实提交"的类加 `@ActiveProfiles("test")`；② 全量测试统一指向测试库；③ 用 `@AfterEach` 手工删数据
- **选择**：② 在 `src/test/resources/application.properties` 写一行 `spring.profiles.active=test`，让**所有** `@SpringBootTest` 连 `work_order_test`；类级 `@Transactional` 保留用于测试间隔离
- **理由**：①的问题是把隔离范围做成"逐类判断"，一旦将来有新的测试类需要提交就会再漏一次；②把"业务库被测试写入"从"靠记得回滚"变成"物理上不连它"。③（`@AfterEach` 手工删）被明确否决——它容易漏，且漏了不会报错。
- **反转留痕**：**原以为**污染源至少有两个类（`WorkOrderFlowServiceTest` 与 `WorkOrderServiceTest`）→ **后来发现** `WorkOrderServiceTest` 有类级 `@Transactional`（`:30`），它调用 `submitOrder` 时与被测代码共享同一事务、整体回滚，**根本不落库**；108 = 4 批次 × 27 行，而 `WorkOrderFlowServiceTest` 恰好 27 个 `@Test`，单类即可解释全部残留 → **因此改为**"单污染源"的判断，并顺带把隔离范围扩大到全部测试类。
- **代价**：需要一次性建库（`work_order_test`）并导入 `sql/init.sql`，已写入 `README.md` §6.1；测试库会累积少量测试数据（但它本来就是测试库）。
- **关联文档**：`README.md` §6.1、`INVARIANTS.md` §三、I9

---

## D18 · 启动自检不得中止应用启动

- **日期**：2026-09-23
- **问题**：`ensureSlaConfigComplete()` 要在启动时校验 `t_sla_config` 覆盖度，但它的实现方式（`@PostConstruct` 里跑一次数据库查询）会不会反过来让服务起不来？
- **备选项**：① 让异常抛出（启动失败，强制修复配置）；② 捕获异常并记 error，启动继续
- **选择**：② 捕获并记录，绝不中止启动
- **理由**：设计目标写的是"让问题可见，不是让服务不可用"；而且**如果自检能让服务起不来，那么"数据库连不上"这种更常见的问题会直接表现为应用无法启动**，排障难度陡增。
- **反转留痕（实测踩到）**：**原以为**只读日志的自检不会影响启动 → **后来发现** `mvn test` 时整个 Spring 上下文加载失败，根因是 `Error creating bean with name 'slaConfigStartupCheck': Invocation of init method failed`（当时本机 MySQL 因时区表缺失而连不上）→ **因此改为** `try/catch` 包裹查询并在失败时记 error 日志，启动继续。这也是本轮 86 个测试报错的直接教训：**任何 `@PostConstruct` 里的外部 I/O 都必须假定它会失败**。
- **代价**：数据库不可用时，自检的结论是"校验失败"而不是"配置缺失"，两类问题在日志里需要区分（已分别写明）。
- **关联文档**：`INVARIANTS.md` I5、`ASYNC-SCHEDULING-PLAN.md` P0a 第 6 项

---

## D19 · 测试残留清理：先留档、按前缀删除，并承认留档口径偏差

- **日期**：2026-09-23
- **问题**：108 条 `TST-` 工单残留怎么清理？关联日志要不要一起处理？
- **备选项**：① 直接删除；② 先留档再删除；③ 只删工单不动日志
- **选择**：② 先 `mysqldump` 留档到**仓库外的备份目录**（`<仓库外>/backup/`）、核验可恢复后再删；日志一并清理（否则成为孤儿）
- **理由**：删除不可逆；而"先留档"的成本只有几秒。留档文件不入 git（测试垃圾不应进版本库）。
- **反转留痕**：**原以为**关联日志是 311 条（按 `order_id JOIN 现有工单` 统计，也据此做了留档）→ **后来发现**日志表有冗余列 `order_no`，按它统计是 **1343** 条，差额 1032 条是 `order_id` 已不存在的历史孤儿日志 → **因此实际删除 1343 条，但留档只覆盖 311 条**。影响评估：全部为测试夹具数据（`data-generator.sql:55` 只生成 `WO-` 前缀，`TST-` 仅由测试夹具产生），3 条真实工单及其 16 条日志完好，**无业务损失**，但 1032 条未留档即删除、不可恢复。**教训**：删除前必须对着"最宽的那个口径"统计，而不是选一个看起来合理的连接条件。
- **代价**：删除动作依赖手工 SQL（本仓库暂无数据清理脚本）；后续若再出现测试污染，应先按最宽口径统计并留档。
- **关联文档**：`INVARIANTS.md` I4(c) 步骤 0、I9

---

## D20 · JDBC 时区用偏移量而非命名时区

- **日期**：2026-09-23
- **问题**：新增的测试数据源 URL 里 `connectionTimeZone` 该写什么？原 `application.yml` 用的是命名时区 `Asia/Shanghai`。
- **备选项**：① 沿用 `Asia/Shanghai`；② 用偏移量 `+08:00`；③ 完全不写，交给服务器默认
- **选择**：测试配置用 **`+08:00`**（且必须写成 `%2B08:00`）；生产配置 `application.yml` **本轮不动**，作为待裁决项上报
- **理由**：命名时区要求 MySQL 服务器加载了时区表（`mysql.time_zone_name` 非空），否则 `SET time_zone='Asia/Shanghai'` 直接报 **ERROR 1298**、连接建立失败。本机实测该表 **0 行**；官方 `mysql:8.0` 镜像默认同样不加载。上海自 1991 年起无夏令时，`+08:00` 与 `Asia/Shanghai` 在本项目语义等价。
- **反转留痕（两个坑）**：**原以为** `+08:00` 直接写进 URL 即可 → **后来发现** JDBC 按 `application/x-www-form-urlencoded` 解码参数，**裸 `+` 会被解成空格**，驱动拿到 `" 08:00"` 抛 `DateTimeException: Invalid ID for region-based ZoneId` → **因此必须写 `%2B`**。
- **代价**：偏移量不含夏令时规则，若将来业务扩展到有夏令时的地区，需要改为"加载 MySQL 时区表 + 命名时区"的路径，并同步调整两处 URL。
- **关联文档**：`src/test/resources/application-test.yml`、`README.md` §6.1；生产 URL 见 `src/main/resources/application.yml`（**待裁决**）

---

## D21 · 容器内存估算降级为"上界参考"，以本机 6 容器实测为准

- **日期**：2026-09-23
- **问题**：方案 §1.2 的逐组件 RSS 是公式推算（堆 + Metaspace + Code Cache + 线程栈 + 固定开销），其中 RabbitMQ 的 180–260M 被自己标为"全表最可疑的低估项"。这些数字到底偏高还是偏低？
- **备选项**：① 继续用公式推算；② 本机预演实测后回写；③ 等服务器批再测
- **选择**：② 用 Docker Desktop 本机预演实测 6 容器，把实测值写入 §1.6，并把原公式值**降级为"上界参考"**
- **理由**：RSS 统计的是**已触碰的物理页**，JVM 不会在启动时 commit/touch 满 `-Xmx`；因此"堆上限 + 开销"的公式对空载与轻载**必然系统性高估**。实测 6 容器合计 ≈1.2 GiB（空载 1.09 / 负载 1.16–1.18），远低于原估 2.3–2.9G。
- **反转留痕**：**原以为** RabbitMQ 的 180–260M 是低估（"management 插件与 Erlang 侧开销通常更高"）→ **实测空载 155.0 MiB、50 单/分钟负载下 155.6–156.0 MiB**，**低于该区间**；xxl-job-admin 同理，**原以为** 400–500M → **实测导入官方建表脚本后的健康态 299.2 MiB（负载下 325–334 MiB）** → **因此改为**：§1.2/§1.3 的估算整体下调为"上界"，2C4G 余量由 0.8–1.4G 上修为 **2.4–2.5G**。
- **代价（口径限制，必须随结论一起引用）**：① 只测了空载与 10× 峰值的轻载，无长稳与堆压力测试；② backend 用的是镜像默认 `-Xmx256m`（非目标 512m），不可直接外推；③ 宿主 7.612 GiB **未限制为 4G**，所以"4G 下会不会 OOM"仍未实测；④ mysql 用的是 compose 的 `buffer_pool=128M`（非建议的 256M）。**最终结论仍以服务器批实测为准。**
- **关联文档**：`ASYNC-SCHEDULING-PLAN.md` §1.6（实测表）、§1.3（估算降级说明）

---

## D22 · 时区探针 P15a 拆成"未来时间检查 + 新鲜度检查"

- **日期**：2026-09-23
- **问题**：P15a 原设计是"取最新工单，比较 `created_at` 与 DB 的 `NOW()`，期望偏差 0–5 秒"。它真的能测出"JVM 与 MySQL 时区不一致"吗？
- **备选项**：① 保留原设计；② 拆成"与年龄无关"的判定 + "新鲜度"判定
- **选择**：② 拆成 **P15a-future**（最新工单 `created_at` 不得超前 DB 的 `NOW()` 超过 5 秒——超前即 JVM 时区/时钟快，与工单年龄无关）+ **P15a-fresh**（最新工单年龄 ≤15 秒；否则该行 SKIP 且提示看 future 行）
- **理由**：原式测出来的其实是"最新工单已经创建了多久"，而不是时钟偏差——除非恰好刚提交完就运行。
- **反转留痕**：**原以为**该探针可以随时运行并判定时区 → 阶段 2 实测踩到：压测结束 41 秒后运行，读数正好是 **41**，被判 FAIL，而 `P15b`（DB 会话偏移 = -28800 秒）为 PASS，说明时区本身没问题、是探针语义有缺陷 → **因此改为**上述拆分，并让 future 检查承担确定性判定（若 JVM 快 8 小时，`created_at` 会超前 DB 时间约 28800 秒）。
- **代价**：探针从 1 行变成 2 行，且 future 行只能抓"JVM 快"这一类偏差（"JVM 慢"需要结合 P15b 与调度日志判断）；另外 probe 仍需在"刚提交过工单"时才有完整的判定力。
- **关联文档**：`sql/probes.sql`（P15a-future / P15a-fresh）、`INVARIANTS.md` I10

---

## D23 · 测试隔离之外还需"测试库自清理"

- **日期**：2026-09-23
- **问题**：P0a 把全部 `@SpringBootTest` 指向独立测试库 `work_order_test` 之后，测试还需要什么才能重复运行？
- **备选项**：① 只做库隔离，靠每轮重置；② 隔离 + 让会提交的测试类自清理
- **选择**：② 在唯一会提交的 `WorkOrderFlowServiceTest` 上增加 **id 水位线自清理**（每个用例开始前记录三张表的最大 id，`@AfterEach` 只删 id 大于水位线的行，先删子表再删工单）
- **理由**：隔离只解决了"污染业务库"，没有解决"测试库跨轮次累积"。
- **反转留痕**：**原以为**把测试指向独立库就足够了 → **后来发现**上一轮提交的 27 单留在测试库里，让下一轮 `WorkOrderMapperTest`（期望 5 行、实到 32 行）与 `NotificationServiceTest`（期望 3 条、实到 5 条）失败——**隔离把"污染业务库"换成了"测试不可重复"** → **因此改为**给唯一的提交方加水位线自清理。之所以用水位线而不是按 `order_no LIKE 'TST-%'` 删除：`t_notification` 没有指向工单的外键（`InAppNotifyChannel` 写入时根本没有填 `ref_type/ref_id`），无法按 order_no 反查通知，而自增 id 单调，按 id 水位线可以精确覆盖且不会误删种子数据。
- **代价**：清理逻辑与表结构耦合（将来新增"被测试写入的表"必须同步加入水位线列表，否则又会出现跨轮次累积）；`@AfterEach` 在用例失败时同样执行，但若 JVM 被强杀则残留会保留，需要靠每轮前的重置兜底。
- **关联文档**：`src/test/java/com/workorder/service/WorkOrderFlowServiceTest.java`、`INVARIANTS.md` I9、`README.md` §6.1

---

## D24 · 测试必须使用独立的 Redis DB，而不只是独立 MySQL 库

- **日期**：2026-09-23
- **问题**：P0a 把测试指向独立 MySQL 库后，测试就不会影响运行中的应用了吗？
- **备选项**：① 只隔离 MySQL；② Redis 也隔离（`spring.data.redis.database`）
- **选择**：② 在 `src/test/resources/application-test.yml` 设置 `spring.data.redis.database: ${TEST_REDIS_DB:1}`，应用仍用默认 DB 0
- **理由**：测试与应用共用同一个 Redis 实例与 DB 时，**测试会清掉应用正在用的键**。
- **反转留痕（本轮代价最大的一处）**：**原以为**隔离 MySQL 就够了 → P0a 预演压测时发现 **250 次"成功"提交其实全部业务失败**（HTTP 200 + body `code=500`，`Duplicate entry 'WO-20260923-00260' for key 't_work_order.order_no'`）→ **后来发现** `OrderNoGeneratorTest:72` 会 `redisTemplate.delete(key)` 删除每日编号 key `order:seq:<日期>`，而测试与运行中的应用共用 Redis DB 0，于是应用计数器被清零到 261，而库里今日单号已到 268，之后每次提交都撞唯一键 → **因此改为**测试用 Redis DB 1。另有两处连带教训：**① 压测脚本必须校验响应体的业务 `code`，只看 HTTP 状态会把"HTTP 200 + code=500"计成成功**（这是本轮"250 ok"假象的直接原因）；**② 共用外部状态（Redis、MQ、对象存储）的测试隔离必须逐项确认，不能因为隔离了数据库就认为完成**。
- **代价**：测试与应用的 Redis 数据不再共享，需要在测试库侧重建依赖的键（当前测试只依赖编号 seq 与驳回 token，均在测试内自建，无额外成本）；`TEST_REDIS_DB` 可覆盖，CI 若用独立 Redis 实例可设回 0。
- **关联文档**：`src/test/resources/application-test.yml`、`INVARIANTS.md` I9、`README.md` §6.1

---

## D25 · 评估过"用本地受限环境模拟 2C4G"，结论**不采用**

- **日期**：2026-09-23
- **问题**：能否在本机把 Docker Desktop 限到 2 CPU / 4 GB，以模拟目标服务器、直接验证"4G 下 6 容器会不会 OOM"？
- **备选项**：① 改 Docker Desktop 滑块（Settings → Resources）；② 改 `~/.wslconfig`（`memory=4GB` + `processors=2` + `wsl --shutdown`）；③ 不做本地限制，改用"参数上界推算 + 浸泡测试 + 服务器批实测"
- **选择**：**③ 不采用本地受限模拟**
- **理由**：
  1. **WSL2 后端的 VM 资源由 `.wslconfig` 决定，Docker Desktop 的滑块会被覆盖**——两个开关互相打架，"受限"到底生效于谁无法确定，测出来的结论不可信；
  2. **限制 WSL 会影响同机其他项目**：本机同时跑着另一个项目的容器（`petlife-*`），`wsl --shutdown` 会连带重启它们；测试完成后还必须还原设置，**副作用大于收益**；
  3. 目标问题（4G 够不够）**可以不靠受限环境回答**：参数本身有硬上界（`mem_limit` / `-Xmx`），按上界推算即可给出容量结论，而"会不会有缓慢爬升"这类真问题应当用**浸泡测试**发现——后者比"4G 受限"更容易暴露真实缺陷。
- **替代方案**：参数上界推算（§1.6.2 容量规划口径）+ 30 分钟浸泡与突发压测 + **服务器批权威实测**（`free -m` 与 `docker stats`）。
- **代价**：本地**永远得不到**"4G 下 OOM 与否"的直接观测；该结论以服务器批为准。因此 §1.4 的基线定稿**不能引用本地受限实验**，只能引用上界推算 + 实测无 OOM 的组合（见 D26 与 §1.4）。
- **关联文档**：`ASYNC-SCHEDULING-PLAN.md` §1.6.2、§1.4；`CLAUDE.md` §5（临时编排文件不入库）

---

## D26 · 堆与 buffer pool 定稿为基线，取消"受限环境无 OOM 才定"的条件式规则

- **日期**：2026-09-23
- **问题**：`-Xmx512m`（含 `-Xms256m`、`MaxMetaspaceSize=192m`、`Direct=64m`）与 MySQL `buffer_pool=256M` 要不要正式写进 `deploy/docker-compose.yml`？原先挂了一条前置条件："必须先在 4G 受限环境跑出无 OOM"。
- **备选项**：① 保留条件式规则、等受限环境；② 取消该条件，按上界推算 + 实测无 OOM 直接定稿
- **选择**：**② 定稿为基线**（已写入 compose），并删掉"按需上调"的待办；保留一条复核动作——服务器批用 `free -m` 与 `docker stats` 复核，若余量显著低于 1 GiB 再回收
- **理由**：三件事的组合足以支撑定稿——① 参数有**硬上界**（`mem_limit` 与 `-Xmx` 都是封顶值，RSS 不会无限增长）；② 按上界推算 4G 下余量约 **1 GiB**（§1.6.2 容量规划口径）；③ 目标参数下的 6 容器实测（30 分钟浸泡 + 突发压测）**无 OOM、无重启**。
- **反转留痕**：**原以为**"要定基线，就必须先在 4G 受限环境里跑出无 OOM" → **后来发现**这个前置条件建立在**一个不必要的验证**上：容量结论可以由上界推算得出（不需要受限环境），而本地受限环境既不可信也不可得（见 D25）→ **因此改为**取消条件式规则、直接定稿，并把"4G 下 OOM 与否"这个唯一不可本地验证的点，明确挂到服务器批复核上。
- **代价**：本地永远没有"4G 下 OOM 与否"的直接观测；若服务器实测余量低于 1 GiB，需要回收参数（下调堆或 buffer pool）。这条代价已写进 §1.4。
- **关联文档**：`deploy/docker-compose.yml`（mysql command 与 backend `JAVA_OPTS`）、`ASYNC-SCHEDULING-PLAN.md` §1.4 / §1.6.2、D25

---

## D27 · CPU 占用率在 N 核宿主的测量不得外推到 M 核目标机

- **日期**：2026-09-23
- **问题**：本轮压测在后端 CPU 利用率 **0.17–0.24%** 的读数下被记为"CPU 压力很小"，这个结论能不能用来判断 2 vCPU 目标机够不够？
- **备选项**：① 直接把百分比当作可外推的结论；② 明确标注"不可外推，必须在目标规格重测"
- **选择**：**② 不可外推**。任何 CPU 结论都必须写明测量宿主的核数，并在目标规格下重测后才可用于容量判断。
- **理由**：百分比是**相对宿主核数**的归一化值。0.24% × 20 核 ≈ **0.05 核**；同一个负载放到 2 vCPU 上，占用率会变成"约 2.4%（若线性缩放）"甚至更高（核少时上下文切换、GC 线程争抢、调度延迟都会放大），**同一份负载在两个核数下的占用率不是同一件事**；更关键的是，2 vCPU 下的瓶颈形态会变（排队、节流、长尾），这是 20 核宿主**永远测不出来**的。
- **与"轻载 RSS 不可用于容量规划"的关系**：**同一类方法学问题**——都是把"在甲条件下测得的瞬时值"当成"乙条件下的边界值"。三条并列留痕：D21（把 `-Xmx` 当 RSS 下限 → 高估）、§1.6.2（把轻载 RSS 当规划依据 → 低估）、**D27（把 N 核占用率外推到 M 核 → 结论失效）**。统一规则：**报告任何资源结论，必须同时写明"测的是什么条件"与"能不能外推"。**
- **代价**：本地测不出 2 vCPU 的 CPU 结论，必须等服务器批；在那之前，任何"CPU 够用"的说法都只能写作**待验证**。
- **关联文档**：`ASYNC-SCHEDULING-PLAN.md` §1.6.3 / §1.6.5、§1.6.2、D21

---

## D28 · 移除 compose 的真实感默认口令，改为"缺失即报错"

- **日期**：2026-09-24
- **问题**：`deploy/docker-compose.yml` 里 `MYSQL_ROOT_PASSWORD` 的默认值是 `WorkOrder@2026`——一个看起来像真实生产口令的字符串。部署前体检要决定：保留默认值、还是改成必需变量？
- **备选项**：① 保留默认值（方便本地一键起）；② 改成必需变量，缺失即报错；③ 换成明显的占位符（如 `change-me`）
- **选择**：**② 用 `${MYSQL_ROOT_PASSWORD:?必须提供…}`**，缺失时 compose 直接报错退出
- **理由**：① 这个默认值**会随推送进入公开历史**（它当前在本地未推送的提交里，见 §体检结论），把"看起来能用的口令"公开出去是最容易被误用的那类泄漏；③ 换成占位符仍可能被直接用于部署（"反正能跑起来"）；**只有"缺失就起不来"才能保证部署者主动设一次**。
- **代价**：本地/服务器首次启动多一步（`cp .env.example .env` 并填值），未填时 compose 报错而不是静默用默认口令。**这是有意为之的摩擦**。
- **未同步处理的一项（留给后续裁决）**：`src/main/resources/application.yml` 的 `${MYSQL_PASSWORD:123456}` 仍保留默认值。区别在于 `123456` 是**一眼可辨的开发默认值**，而 `WorkOrder@2026` 像是真实口令；若未来仓库转为公开，建议同样去掉该默认值——但那会让不带环境变量的 `mvn spring-boot:run` 无法直连本机库，属于开发体验变更，需单独决定。
- **关联文档**：`deploy/docker-compose.yml`、`.env.example`、`README.md` §五

---

## D30 · 移除 `MYSQL_PASSWORD` 死配置；根账号连库作为演示取舍

- **日期**：2026-09-24
- **问题**：`.env` / `.env.example` 里的 `MYSQL_PASSWORD` 到底有没有被消费？
- **备选项**：① 保留（"看起来更完整"）；② 移除并说明真实取值路径
- **选择**：**② 移除**，并在 `README` §5 写明后端实际用 root 账号连库
- **理由**：compose 里后端的环境变量 `MYSQL_PASSWORD` **取自 `${MYSQL_ROOT_PASSWORD}`**（`deploy/docker-compose.yml`），
  因此用户自己设的 `MYSQL_PASSWORD` 会被覆盖、**永远不生效**。它属于"写了不生效"的第二例
  （第一例是 `somaxconn`），与 D28 同源：**配置项的价值在于被消费，不被消费的配置项只会误导排障**。
- **同时明确的取舍**：后端连库用的是 **MySQL root 账号**（口令即 `MYSQL_ROOT_PASSWORD`）。
  这是演示环境的简化——**生产必须改为最小权限的专用用户**（只授予 `work_order` 库所需权限），
  该要求已写入 `README` §5.1。
- **代价**：`.env` 少了一个"看起来该有"的键，新同事可能疑惑"为什么不给后端单独口令"——
  因此 `.env.example` 里保留了"这里没有 MYSQL_PASSWORD，原因如下"的显式说明，而不是静默删除。
- **关联文档**：`.env.example`、`README.md` §5.1 / §5.4、`deploy/docker-compose.yml`

---

## D31 · P1 只接"释放检查"链路；SLA 告警链路保持现状（P4/P5 再迁）

- **日期**：2026-09-24
- **问题**：P1 要把"afterCommit 直发"改成 outbox，是否顺手把 `sendSlaEscalation` 一起迁到事件模型？
- **备选项**：① P1 一次迁两条链路；② P1 只迁释放检查，SLA 保持现状并在方法注释标注迁移计划
- **选择**：**② 只接释放检查**。`MessagePublishService.sendSlaEscalation` 保持现状，已在方法注释标注"P4/P5 迁移，届时删除"
- **理由**：SLA 告警链路的正确性依赖 **H1 的分级催办**与 **`eventVersion` 递增**（同一工单每满 24 小时要发新的合法告警，而不是被去重吞掉）——这两件事属于 P4 的幂等/死信设计。在 P4 就绪前迁移，只会把"漏发"风险从旧实现搬到一个还没验证的新实现上。**一次只动一条链路，才能把 outbox 的语义（事务内写、confirm 后标 SENT、重复投递被去重）验证干净。**
- **代价（过渡期两套接口并存）**：`MessagePublishService`（旧，`sendXxx(orderId)`）与 `MessagePublisher`（新，`publish(OrderEvent)`）会同时存在几个阶段；旧接口的 `sendReleaseCheck` 在 P1 后不再被调用（由 outbox 路径取代），但**不删除**——留到 P4/P5 与 SLA 链路一起清理，避免这轮出现"半迁移"状态。
- **关联文档**：`src/main/java/com/workorder/service/MessagePublishService.java`（方法注释）、`ASYNC-SCHEDULING-PLAN.md` §3.2 / §3.4 第 2 条、D03

---

## D32 · 延迟方案选 A（`x-delayed-message` 插件），不选四个固定档位

- **日期**：2026-09-24
- **问题**：outbox 记录的 `deliver_at` 已确定（= now + 该工单的 `accept_minutes`），但"到点投递"怎么实现？
- **备选项**：
  - **A · 延迟插件 `x-delayed-message`**：`setDelay(ms)` 支持任意值
  - **B · TTL + DLX 四档位队列**（对应 `accept_minutes` 的 10/30/60/120）+ 向上取整兜底
- **选择**：**A**（若步骤 6 验证镜像无法启用插件，再退 B，且兜底必须写明）
- **为什么选 A**：`deliver_at` 由 `accept_minutes` 推导，而**这个值可被管理员在界面上改**（`SlaConfigView` → `t_sla_config`）。B 的四个档位是**硬编码**的：管理员把某类改成 45 分钟，档位里没有 45，只能向上取整到 60——**实际延迟与配置不一致，而且没有任何报错**。这正是一种"写了不生效"，本项目已在 `accept_minutes` 上踩过一次（G5）。
- **替代方案（B）的代价**：档位化 → 配置变更即静默失真；需要"向上取整 + 投递侧用 `deliver_at` 二次校验"才能自洽（多一层兜底逻辑）；好处是零插件依赖、行为可预测。
- **选 A 的代价**：① 依赖插件可用（镜像必须能启用 `rabbitmq_delayed_message_exchange`，**步骤 6 要实测**）；② 延迟消息存在交换机内部，**不被队列积压指标覆盖**，堆积时难以观测，需单独监控；③ 集群/仲裁队列场景有行为限制（本项目单机单节点，风险可控）。
- **与表结构的关系（无论 A/B 都必须）**：`t_event_outbox` **必须有 `deliver_at` 列**——它是"该何时投递"的**唯一真相来源**，也是 B 方案向上取整后做兜底校验的依据。投递任务用 `deliver_at <= NOW()` 作为筛选条件，而不是把延迟语义藏进队列配置里。
- **更新（2026-09-24，P1 步骤 3 实测后，本条含两处反转）**：
  - **反转一**：**原以为**"官方镜像 `rabbitmq-plugins enable` 就能启用延迟插件"→ **后来发现**官方镜像**不含**该插件（`{:plugins_not_found, [:rabbitmq_delayed_message_exchange]}`，退出码 70，broker 3.13.7）→ **因此改为**自建镜像 `deploy/rabbitmq/Dockerfile`：45 KB 的 `.ez` **随仓库入库**（境内服务器下载 GitHub release 不可靠）、构建期 `--offline` 启用、并加两条构建期断言（broker minor 必须 3.13.x；插件必须出现在已启用列表）。
  - **反转二**：**原以为** `mandatory=true` + returns 回调是"不可路由即丢失"的保护网 → **后来发现**延迟插件对**每条**延迟消息都返回 NO_ROUTE（消息其实照常到点入队）→ **因此改为** `mandatory=false`（独立记为 D37）。
  - **结论不变，仍是 A**：三个前提已实测成立——插件可启用、delay=60s 真的延迟（t+62s 才进队列）、延迟窗口中途重启 broker 消息仍在。证据见 `ASYNC-SCHEDULING-PLAN.md` §5.3 的落地记录与 `deploy/rabbitmq/README.md`。
  - 原文末句"投递任务用 `deliver_at <= NOW()` 作为筛选条件"**已被 D35 推翻**：取数不卡 `deliver_at`，延迟由 broker 承担。
- **关联文档**：`sql/init.sql`（`t_event_outbox.deliver_at`）、`ASYNC-SCHEDULING-PLAN.md` §5.3、D02（R4 的 SLA 取值）

---

## D33 · outbox 模式下没有 Mock publisher 实现（与方案 §3.5 的差异）

- **日期**：2026-09-24
- **问题**：`ASYNC-SCHEDULING-PLAN.md` §3.5 的改动清单写的是"`RabbitMQPublishServiceImpl` + `MockMessagePublishServiceImpl` 双实现，用 `@Profile` 切换"。P1 的实现**没有** Mock 版本，是否偏离方案？
- **选择**：**偏离，且是有意的**。`OutboxMessagePublisher` 只有一个实现，本地与 CI 天然可跑。
- **理由**：**接口语义变了**。方案 §3.5 写于"publisher 直接发 MQ"的前提下，所以需要 Mock 来在没有 broker 时启动；P1 的 `MessagePublisher.publish()` 只写 outbox、**不做任何网络调用**——它退化成了一次 DB 写入。没有网络依赖，就不需要 Mock。"是否真的发到 MQ"这件事已经从 publisher 转移到**投递任务**，因此**开关也应该放在投递环节**（`@ConditionalOnProperty` 控制投递任务是否运行），而不是放在 publisher 上。
- **代价**：① 方案 §3.5 的表述需要同步（避免后来者按旧描述去找 Mock 实现）；② "本地不启 broker 也能跑"这条保证的证据形态变了——不再是"publisher 是 Mock"，而是"outbox 只写库 + 投递任务默认不启用"（步骤 3 落地后需重新验证）。
- **关联文档**：`ASYNC-SCHEDULING-PLAN.md` §3.5、`src/main/java/com/workorder/service/impl/OutboxMessagePublisher.java`、D31

---

## D34 · 投递任务用"原子抢占 + 提交后发送"，不用 `SELECT ... FOR UPDATE SKIP LOCKED`

- **日期**：2026-09-24
- **问题**：投递任务要"取一批 outbox 记录并发送"，怎么既保证多实例不重复投递、又不让网络 IO 占住数据库？
- **备选项**：① `SELECT ... FOR UPDATE SKIP LOCKED` 后在**同一事务内**发送；② 原子抢占（条件 UPDATE 置 `SENDING` + 写 owner），提交后再发送，最后按结果回写；③ 假定单实例，不做并发保护
- **选择**：**②**
- **理由**：①在 2 vCPU 机器上，只要 broker 卡一下，行锁与数据库连接就被一起占住，整个投递循环停摆；②的锁只活在一条 UPDATE 语句内，发送阶段无锁。②新引入的风险是"抢占后进程崩溃留下中间态"，由回收语句兜住（`status='SENDING' AND claimed_at < NOW()-INTERVAL 5 MINUTE → PENDING`）。
- **代价**：① 状态机多一个中间态 `SENDING`，DDL/列注释/索引都要同步（`sql/init.sql` + `sql/hotfix-outbox-sending-state.sql` 成套交付）；② 极端情况（进程卡死超过 5 分钟）会重复投递一次——可接受，因为消费端按 `eventId` 幂等、释放 SQL 有 `WHERE status='ACCEPTED'` 状态守卫；③ 回收阈值成了新的可调参数，必须与单轮最坏耗时保持数量级差距（本轮：单轮预算 15s、阈值 300s，20 倍余量）。
- **关联文档**：`src/main/java/com/workorder/scheduler/OutboxDispatchTask.java`、`mapper/EventOutboxMapper.java`、`sql/init.sql`（`t_event_outbox`）、`INVARIANTS.md` I11

---

## D35 · 投递取数**不带** `deliver_at <= NOW()` 门控（与任务书原文冲突，已上报）

- **日期**：2026-09-24
- **问题**：本轮任务书里两条指令互相矛盾——① 扫描条件写 `status='PENDING' AND deliver_at<=NOW() AND next_retry_at<=NOW()`；② 要求延迟由 `x-delayed-message` 承担、并把"延迟消息在交换机内部、队列指标看不到"记为其代价、还要求把观察方式写进文档。
- **冲突点**：按 ① 取数，消息只在"已经到点"时才发给 broker，`x-delay` 恒为 0——交换机里的延迟语义整体失效，②的代价与观察方式都成空话，且 P1 验收①（"抢单后管理台可见延迟消息且 30 分钟内不被消费"）不可能成立。
- **选择**：**按 ② 实现**。取数只卡 `next_retry_at`；延迟一律由 broker 执行，`x-delay = deliver_at - now`（已过期按 0）。
- **理由**："到点"的真相来源仍是 `t_event_outbox.deliver_at`，可追溯性没有丢失；而 ① 会让"延迟"退化为"5 秒轮询扫描"，正是 `CLAUDE.md` §3 第 9 条要避免的形态。
- **代价**：① 手工验证**不能**再用"记录一直 PENDING 直到到点"作为延迟生效的证据（记录下一轮就变 SENT），必须用"队列深度：到点前 0 → 到点后正数"；② 若将来要改回 ①，除了一行 SQL，还得同时拆掉自建镜像与插件依赖（否则白装一个组件）。
- **关联文档**：`EventOutboxMapper.claimPending`（注释里写明理由）、`ASYNC-SCHEDULING-PLAN.md` §5.3、`INVARIANTS.md` I11 易错点 2

---

## D36 · broker 不进启动依赖；启动期只做交换机类型校验，且日志分级

- **日期**：2026-09-24
- **问题**：投递链路接入后，broker 不可达时应用该怎么办？启动期要不要 fail-fast？
- **备选项**：① 与数据库一样 fail-fast（连不上就不让起）；② compose 里给 backend 加 `depends_on: rabbitmq(healthy)`；③ 不阻断启动，只把问题写进日志，投递任务自行重试
- **选择**：**③**，且 compose 的 backend **刻意不依赖** rabbitmq
- **理由**：outbox + 兜底扫描的设计前提就是"MQ 挂了业务照常"（§5.7）。让 MQ 决定应用能否启动，等于把一条可恢复的旁路故障升级为"整站不可用"；`depends_on healthy` 只是把同一问题提前到编排层，还会让"停 broker 仍能接单"的演练失效。
- **代价**：① 全新部署时启动校验可能先于 broker 就绪 → 因此按异常类型分级：`AmqpConnectException` 记 **WARN**（可能只是还没起），其余 `AmqpException`（插件缺失/交换机类型不符）记 **ERROR** 并写清后果与排查方向；② 误用官方镜像部署时，症状是"outbox 的 retry_count 一直涨"而不是启动失败——这条 ERROR 日志是第一现场，不能删。
- **关联文档**：`RabbitOutboxConfig.verifyDelayExchangeUsable`、`deploy/docker-compose.yml`（backend 的 `depends_on` 注释）、`ASYNC-SCHEDULING-PLAN.md` §5.7

---

## D37 · `spring.rabbitmq.template.mandatory` 设为 false（原以为是保护网，实测是假告警源）

- **日期**：2026-09-24
- **问题**：不可路由的消息要不要退回给发布方（`mandatory=true` + returns 回调）？
- **反转留痕**：**原以为**"`mandatory=true` + returns 回调 = 防止消息静默丢失的保护网" → **后来发现**延迟插件对**每一条**延迟消息都返回"无立即路由"，于是每条消息都会被退回并触发一条 ERROR（实测 2026-09-24 15:33:31：`replyCode=312 NO_ROUTE`；管理台 API 同样是 `routed=false`），而消息**并没有丢**（t+62s 队列深度 1）→ **因此改为** `mandatory=false`，并保留 returns 回调覆盖真正的"队列侧拒收"（如 max-length + reject-publish）。
- **理由**：一条"每条消息都报"的错等于没有报警——它会把真正的投递失败淹没，并诱导后来者去修一个不存在的问题。
- **代价**：失去"路由键写错"这类不可路由场景的即时退回提示；补偿手段是启动期声明校验（交换机/队列/绑定由 Spring 在首次连接时声明，失败会记 ERROR）+ 探针 P16a/P16b/P16c 看积压与中间态。
- **关联文档**：`src/main/resources/application.yml`（`template.mandatory` 的注释）、`INVARIANTS.md` I11 易错点 1、`deploy/README.md` §六

---

## D38 · 补记：步骤 2 移除了接单时的 Redis 超时标记

- **日期**：2026-09-24（反转发生在 P1 步骤 2 的提交 `c926472`，本条为补记）
- **反转留痕**：**原以为**把 afterCommit 里的 `convertAndSend` 换成"事务内写 outbox"时，可以原样平移那段 afterCommit 逻辑 → **后来发现** afterCommit 里还夹着一条 `redisTemplate.set("order:accept_timeout:{id}", ..., 30min)`：直接挪进业务事务会让**接单路径强依赖 Redis 可用**（Redis 挂了连单都接不了），而该 key **当前没有任何读取方**（兜底扫描看的是 `updated_at`）→ **因此改为**步骤 2 删除这段写入，等步骤 5 收敛"释放时限"时再以"TTL = 该工单 `accept_minutes`"的形式重新引入。
- **代价**：在步骤 5 落地前，Redis 中没有"接单超时"标记；当前仓库内无消费方（已全仓确认），所以不构成功能缺失，但任何新写的依赖该 key 的代码都会失去依据。
- **关联文档**：`WorkOrderServiceImpl.acceptOrder` 的注释、`BUSINESS-SCOPE.md` F4-1、`ASYNC-SCHEDULING-PLAN.md` §3.4 第 2 条

---

## D39 · 两处"验证盲区"的反转：mvn test 全绿 ≠ 能启动；不回读发现不了漂移

- **日期**：2026-09-24
- **问题**：本轮有两样东西"看起来已经验证过了"，事后证明**验证方式本身有盲区**。
- **反转一（配置装配）**：**原以为** `mvn test` 全绿（146 通过）就说明"投递链路的装配没问题" → **后来发现**真机启动直接失败：
  `Error creating bean with name 'rabbitOutboxConfig' ... Is there an unresolvable circular reference?`
  （配置类注入 `RabbitTemplate`，而它自己又定义了构造 `RabbitTemplate` 所需的 customizer）。根因是**测试 profile 里投递开关默认关闭**，
  这个配置类在测试中根本没被装配，所以测试对这类缺陷零覆盖 → **因此改为**新增 `RabbitOutboxConfigTest`
  （显式打开开关 + `spring.rabbitmq.port=1` 保证不碰真实 broker），把"开关打开时能装配"变成回归项；
  同时说明"默认关闭"这个安全默认值**自带覆盖盲区**，必须用一条专门的测试补上。
- **反转二（DDL 一致性）**：**原以为**热修脚本与 `sql/init.sql` 的差异只在我显式改过的那几列 → **后来发现**回读校验逐列比对时，
  `sent_at` 的列注释在步骤 2 之后改过而热修脚本没同步（注释长度 20 vs 8），于是"全新装库"与"老库迁移"会留下一条文档漂移 →
  **因此改为**热修脚本补 `MODIFY COLUMN sent_at`，并把"建临时库完整导入 `init.sql` + `information_schema` 逐列比对列与索引"
  作为 DDL 交付的**固定验证动作**（`CLAUDE.md` §4 的"成套交付"从此有了可执行的判定方式）。
- **代价**：① 装配测试要拉起一次 Spring 上下文（约 15s），是为"开关打开"这条路径付的固定成本；
  ② DDL 交付多一步"临时库导入 + 比对"，比只读一遍 DDL 文件慢，但它是唯一能发现"迁移结果 ≠ 全新建表"的手段。
- **关联文档**：`src/test/java/com/workorder/config/RabbitOutboxConfigTest.java`、`sql/hotfix-outbox-sending-state.sql`、`CLAUDE.md` §6 第 5 项（回读校验）

---

## D40 · `t_event_outbox` 投递索引改为 `(status, next_retry_at)`（并纠正一处前提误解）

- **日期**：2026-09-24
- **问题**：步骤 2 建的索引是 `(status, deliver_at, next_retry_at)`；步骤 3 把取数条件改成"不卡 `deliver_at`"之后，索引与查询还能不能说"一一对应"？
- **前提纠正（重要，不按推测行事）**：有一种推测是"把 `next_retry_at` 的初值设成 `deliver_at`，两个语义就统一了，所以索引可以简化"。
  **实测不是这样**：`next_retry_at` 的初值是 **NULL（= 立即可投）**，只在**投递失败**时写成"退避后的时刻"；
  `deliver_at` 是业务时限（= 接单时刻 + `accept_minutes`），只用于计算 `x-delay`。两者**没有、也不应该统一**——
  一个是"业务上最早何时该检查"，一个是"投递层失败后多久再试"。索引变化的真正原因是 D35：取数条件里**不再出现 `deliver_at`**，
  把它留在索引里就违反了步骤 2 定下的"索引 shape 与查询一一对应"。
- **实际查询语句**（`EventOutboxMapper.claimPending` 的 WHERE，逐字）：
  `UPDATE t_event_outbox SET status='SENDING', owner=?, claimed_at=NOW() WHERE status='PENDING' AND (next_retry_at IS NULL OR next_retry_at <= NOW()) ORDER BY id LIMIT 100`
- **实测（5 万行、PENDING 占 0.2% 的真实分布，`ANALYZE` 后 `EXPLAIN`）**：
  `type=range, possible_keys=idx_dispatch, key=idx_dispatch, key_len=72, rows=67, Extra=Using index; Using filesort`
  → **索引被真正用上**。退化场景（**全部**行都是 PENDING）下优化器改选 `PRIMARY`（沿主键顺序走到 100 条即停，省掉排序）——
  这是优化器的正常选择，不代表索引失效。
- **代价与边界**：① `ORDER BY id LIMIT n` 带来一次**有界 filesort**（排序集合是"匹配到的 PENDING 行"，不是全表）；
  ② 要消掉它得去掉 `ORDER BY`（牺牲"先到先投"的公平性）或引入规范化列（如 `deliverable_at`），都不划算——
  待投递集合的规模由吞吐决定（每 5s ≤100 条），不随表增长。
- **关联文档**：`sql/init.sql:289`、`sql/hotfix-outbox-sending-state.sql`、`EventOutboxMapper.claimPending`、D35

---

## D41 · DDL 交付规则补全：迁移脚本必须成套、必须幂等、命名统一 `hotfix-*`

- **日期**：2026-09-24
- **问题**：`CLAUDE.md` §4 的"成套交付"只写了"变更语句 + 同步 `init.sql` + 影响面 + 归档策略"，**没有要求交付可执行的迁移脚本**，也没有规定命名；
  而仓库里同时存在 `sql/migration-p0b-order-type.sql` 与 `sql/hotfix-*.sql` 两套前缀。
- **事实（本轮逐条核对）**：`init.sql` 侧是同步的（`owner`/`claimed_at` 在 `sql/init.sql:286-287`，新索引在 `:289`）；
  但规则缺口与命名分叉真实存在——部署者面对两个前缀无法判断"该跑哪个"。
- **选择**：① 统一前缀为 `hotfix-`（`hotfix-role-permissions.sql`、`hotfix-outbox-sending-state.sql` 已是该前缀，且被 README/INVARIANTS 引用），
  把 `sql/migration-p0b-order-type.sql` 改名为 `sql/hotfix-p0b-order-type.sql`；② 在 `CLAUDE.md` §4 写明"必须同时交付幂等迁移脚本 + 命名约定 + 可执行判定方式"。
- **顺带发现并修掉的自相矛盾**：本轮自己新增的 `hotfix-outbox-sending-state.sql` **首版不可重跑**（`ADD COLUMN` 重跑直接报错），
  与"幂等"要求矛盾 → 改为"先查 `information_schema` 判断现状，再按缺什么拼 DDL"，并对三个分支各验证一次
  （已应用→跳过且结构不变；步骤 2 旧结构→迁移后与 `init.sql` 完全一致；只坏一处注释→只修注释且与 `init.sql` 一致）。
- **理由**：老库不会重建——只改 `init.sql` 等于"新库对、老库错"，而这类错误在部署当天才暴露；命名分叉是纯人为成本。
- **代价**：① 改名会让任何已写在别处的命令失效（本轮全仓 grep：除脚本自身头部的用法行外无其他引用，已同步更新）；
  ② 既有脚本的幂等性要逐个核对——`migration-p0b-order-type.sql`（现 `hotfix-p0b-order-type.sql`）自述幂等且按旧值精确匹配，可安全重跑；
  ③ 幂等脚本要写 `information_schema` 判定 + `PREPARE/EXECUTE`，比直白的 `ALTER TABLE` 难读，这是为"能重跑"付的阅读成本。
- **关联文档**：`CLAUDE.md` §4 与 §7、`sql/` 目录、D39（判定方式来源）

---

## D42 · 接单时限解析不出来时**不写 outbox**（而不是"写一条立即投递的记录"）

- **日期**：2026-09-24
- **问题**：`resolveAcceptMinutes` 连兜底组合（`OTHER + 普通`）都查不到时该怎么办？它决定 `deliver_at`，而 `deliver_at` 决定这条释放检查事件什么时候生效。
- **反转留痕**：**原以为**"取 0 = 立即可投递"是保守兜底（至少不发明新的魔法数字，也不阻断接单）→ **后来发现** `deliver_at = now` 意味着这条事件**马上**被投递，消费端/兜底一比对就把**刚接的单立刻释放**——处理人视角是"抢到的单莫名消失"，而且这是一条**没人验证过的新分支**（P1 之前根本没有 MQ 通道）→ **因此改为**：解析不出来时**不写 outbox、记 ERROR**，让 `ReleaseTimeoutScheduler` 按老路径兜底释放，接单本身照常成功。
- **选择**：`resolveAcceptMinutes` 返回 `Integer`，`null` 表示"不可解析"；`publishReleaseCheck` 见到 `null` 就只记 ERROR 并 `return`（不 publish）。
- **理由**：缺配置时的正确行为是**退化成 P1 之前的样子**（没有 MQ 这条通道，仍由兜底扫描释放），而不是进入一个新分支；同时"不发明魔法数字"的顾虑依然成立——这里不是换个默认值，而是**不投递**。
- **代价**：① 缺配置的那类工单失去 MQ 路径的"到点精确检查"，只剩兜底扫描（当前兜底是硬编码 30 分钟，比配置值更粗）——这正是"必须补齐 `t_sla_config`"的动机，ERROR 日志已写明修复动作；② `resolveAcceptMinutes` 的返回值从 `int` 变成 `Integer`，调用方必须显式处理 `null`（已收敛到 `publishReleaseCheck` 一处，两个调用点不再各写一遍）。
- **关联文档**：`WorkOrderServiceImpl.publishReleaseCheck` / `resolveAcceptMinutes`、`sql/probes.sql` 的 P14c、`INVARIANTS.md` I5、`ASYNC-SCHEDULING-PLAN.md` §5.2

---

## D43 · 消费端 ACK/NACK 契约：三态映射，且"ERROR 也 ACK"（P4 改 NACK）

- **日期**：2026-09-24
- **问题**：第一个 MQ 消费者（`OrderReleaseListener`）在"处理失败"时该 NACK（让消息重投）还是 ACK？三态又怎么映射到 ACK/NACK？
- **选择**：

| 结果 | 动作 | 日志 | 依据 |
| --- | --- | --- | --- |
| `RELEASED` | ACK | INFO | 真释放成功 |
| `SKIPPED` | ACK | DEBUG | 状态守卫未命中（已被 START/COMPLETE 改过）或重复投递；**这是正常结论不是失败** |
| `ERROR` | **本轮也 ACK** | ERROR | 无 DLX；NACK + `requeue=false` = 消息静默消失且无痕 |
| 异常抛出 | 捕获后 ACK | ERROR（带栈） | 同上；异常绝不能逃出监听方法 |

- **为什么 ERROR 也 ACK**：**释放的权威通道是兜底扫描 `ReleaseTimeoutScheduler`，MQ 只是"更早触发"**（plan §3.4 第 5 条）。这里丢一条消息不会让工单永远不被释放，只会晚一点由兜底扫描释放；而没有 DLX 时 NACK 是"无声的损失"——**ACK + ERROR 日志至少留下可查的痕迹**。
- **代价**：① 本轮"处理失败"的消息**不会被自动重试**（P4 接入死信 + 退避后才恢复重试能力）；② 因此失败只能靠日志与探针 `P16a/b/c` 发现，运维必须真的看日志；③ "暂时 ACK 掉"这个选择**必须在 P4 显式改回 NACK**，否则它就永久留在代码里了（注释里已写死这句话）。
- **落地位置（可核对）**：`ReleaseResult` 枚举注释（三态 ↔ ACK 对应表）、`OrderReleaseListener` 类注释"一、为什么 ERROR 也 ACK"与方法注释、`OrderReleaseListenerTest` 里的 `verify(channel, never()).basicNack(...)`。
- **关联文档**：`docs/DECISIONS.md` D36（同类的"不 fail-fast"取舍）、`ASYNC-SCHEDULING-PLAN.md` §3.4 第 5 条与 P4 阶段

---

## D44 · 两条"硬杀"实测：延迟消息扛 SIGKILL；进程被 kill -9 后 outbox 记录被补投

- **日期**：2026-09-24
- **背景**：延迟消息"存在交换机内部"是 D32 明确记下的代价，因此必须回答"交换机里那份到底有没有落盘"。
- **实测一（broker 被 SIGKILL）**：accept 一张工单（`accept_minutes=1` → `deliver_at = +60s`）→ **t+10s `docker kill -s KILL`**（容器 `exit=137`，无优雅停机）→ t+18s 重启 broker → **t+70s 工单转 `RELEASED`、队列深度 0**。
  → 结论：延迟消息**扛得住硬杀**（durable 交换机 + persistent 消息），不是"只活在内存里"。
- **实测二（后端进程被强杀）**：停 broker → accept（outbox 记录 `PENDING`、`retry_count=1`、`next_retry_at` 后移）→ **t+12s 强制杀后端进程**（等价 `kill -9`）→ 恢复 broker → 重启后端 → **t+40s 记录转 `SENT`（`retry_count` 仍为 1，成功那次不计数）、t+62s 工单 `RELEASED`**。
  → 结论：**进程被杀也不会丢事件**（outbox 的第二重价值）；并且投递时 `x-delay` 是**按剩余时间重算**的（19:28:41 发出、19:29:08 才落地 = `deliver_at`）。
- **代价与边界**：① 两次都是**单机单节点**；集群/仲裁队列下延迟插件行为不同（D32 已记）；② "扛硬杀"只覆盖 broker **进程**被杀，不覆盖容器被 `rm` 或磁盘损坏——本次验证容器没挂 volume，数据在可写层，`rm` 就没了；③ 后端被强杀期间"到点"这件事无人执行，靠的是重启后重算剩余时间 + 兜底扫描兜底。
- **关联文档**：`ASYNC-SCHEDULING-PLAN.md` §5.3 落地记录、`deploy/rabbitmq/README.md`（延迟消息观察方式）、D32 / D37

---

## D45 · 重建临时 Redis 会清掉应用的"每日单号计数器"（实测：HTTP 200 + body 500）

- **日期**：2026-09-24
- **事实**：本机验证时我删掉并重建了临时 Redis 容器（无 AOF）。随后**提交工单返回 HTTP 200 但 body `code=500`**，日志为 `Duplicate entry 'WO-20260924-00001' for key 't_work_order.order_no'`。
- **根因**：单号来自 Redis 计数器 `order:seq:<yyyyMMdd>`（`OrderNoGenerator`）。计数器随 Redis 一起清零，而**库里当天已有 00001/00002**，于是从 1 重新开始 → 撞唯一键。
- **处置**：`SET order:seq:<今日> <库里今日最大序号>` 恢复；并把这条写进 README 的"测试环境准备"（那里也补了"不启 Redis 时 38 个 error"的实测）。
- **教训（同源坑的另一个方向）**：**Redis 不是"纯缓存"**——它还承载 Sa-Token 会话、驳回幂等键与单号计数器，把它当作可随手清空的临时设施，代价是"业务写入失败"。这与 D24（测试删了应用正在使用的编号 key）是同一个坑的两面：那次是测试污染应用，这次是环境重建清掉业务计数。
- **代价**：本地/CI 环境必须保证单号计数器与业务库当日序号对齐；长期方案是把单号生成移出 Redis（数据库序列/号段），但那属于"要不要为演示环境引入新机制"的问题，**本轮不做**，只留记录。
- **关联文档**：`README.md`（测试环境准备）、D24、`src/main/java/com/workorder/utils/OrderNoGenerator.java`

---

## D46 · 延迟消息的**持久化边界**：local compose 实测 `kill -s KILL` 后消息存活

- **日期**：2026-09-24（P1 步骤 4 手测补做，按要求用 **compose 里的自建镜像**重跑一遍，结论与上一轮 `docker run` 版一致）
- **问题**：方案把"延迟窗口"的计时责任交给了 broker（D35/D40）。那这一段是不是**整条可靠性链条上唯一没有数据库兜底、且扛不住进程崩溃的一段**？
- **实测（容器 `workorder-local-rabbitmq`，镜像 `workorder-rabbitmq:3.13-delayed`，broker 3.13.7，卷 `deploy_rabbitmq-data`）**：

| 步骤 | 命令 / 观察 | 输出 |
| --- | --- | --- |
| 起服务 | `docker compose -f docker-compose.yml -f docker-compose.local.yml up -d rabbitmq` | `Container workorder-local-rabbitmq Started` |
| 插件 | `docker exec workorder-local-rabbitmq rabbitmq-plugins list -e \| grep delay` | `[E ] rabbitmq_delayed_message_exchange 3.13.0` |
| 投消息 | 管理 API `POST /api/exchanges/%2f/workorder.delay.exchange/publish`，`x-delay=120000`、`delivery_mode=2` | `routed=false`（延迟消息的正常回执，D37） |
| 延迟窗口内 | `GET /api/queues/...`（t+5s） | 队列深度 **0**；交换机 `publish_in` **=1**（投递前为空=0） |
| **硬杀** | `docker kill -s KILL workorder-local-rabbitmq`（t+15s） | `Status=exited ExitCode=137 Running=false OOMKilled=false` |
| 重启 | `docker start workorder-local-rabbitmq` | Erlang 节点 t+34s 起、**rabbit 应用 t+85s 就绪**；拓扑恢复：`workorder.delay.exchange x-delayed-message`、队列 `0 / durable=true` |
| 到点 | t+125s / t+140s / t+160s | 队列深度 **1**（= 消息在 `deliver_at` 到点后进了队列） |
| 取回验证 | 管理 API `queues/.../get` | `payload={"orderId":900001}`、`x-event-id=order:hardkill:v1:ORDER_RELEASE_CHECK`、`delivery_mode=2` |

- **结论：消息存活**（不是丢失）。因此"方案 (b)：把延迟窗口的持久性交给 broker"在本项目的用法下**成立**，不必退化为方案 (a)。
- **持久化边界（这一段才是重点）**：
  1. **成立的前提**：交换机 **durable** + 消息 **persistent（delivery_mode=2）**。任一不满足，SIGKILL 后消息随内存消失。
  2. **异步落盘的窗口没被测到**：本次是在**发布后 15 秒**硬杀的，说明那时已经落盘；但**没有验证"发布后毫秒级被杀"**——插件是按 Mnesia 事务日志异步刷盘的，那一小段的语义**未知**。这是本结论的显式边界，不要当成"任何时刻被杀都不会丢"。
  3. **不覆盖**：`docker rm` 掉容器或磁盘损坏（那属于卷/磁盘层面，不是插件语义）；集群/仲裁队列下延迟插件本身有行为限制（D32 已记）。
  4. **没有对账手段**：broker 侧丢了不会有人告诉应用（没有"发送后到点确认"）。这就是为什么**兜底扫描 `ReleaseTimeoutScheduler` 必须继续作为释放的权威通道**，而不是"MQ 已可靠所以可以撤掉兜底"。
- **若将来出现"毫秒级硬杀丢消息"的实证，备选方案（标注为待决，本轮不实施）**：退化为**方案 (a)** —— 由 `t_event_outbox.deliver_at` 卡住投递时机（取数加 `deliver_at <= NOW()`，投递时 `x-delay=0`），把计时责任收回数据库；代价是回到 5s 轮询精度、且 D35 结论作废（延迟不再由 broker 承担）。
- **关联文档**：`ASYNC-SCHEDULING-PLAN.md` §5.3、`deploy/rabbitmq/README.md`、D32 / D35 / D37 / D44（上一轮 `docker run` 版的同类实测）

---

## D47 · `.env` 必须在 `deploy/` 下（compose 按 compose 文件目录找它）

- **日期**：2026-09-24
- **事实**：按 README 的部署步骤（"`cp .env.example .env`"放仓库根目录）执行 `docker compose -f docker-compose.yml -f docker-compose.local.yml up -d rabbitmq` 时，compose **读不到**根目录的 `.env`，直接报
  `error while interpolating services.mysql.environment.MYSQL_ROOT_PASSWORD: required variable MYSQL_ROOT_PASSWORD is missing a value`（明明已经填了）。
  把文件移到 `deploy/.env`（与 compose 文件同级）后立刻正常。
- **选择**：统一放在 **`deploy/.env`**（`.gitignore` 的 `.env` 规则对任意层级生效，实测 `git check-ignore -v deploy/.env` 命中），并修正三处文档：`.env.example` 用法头、根 `README`（§二/§5.4/§5.5）、`deploy/README` 的步骤 0。
- **为什么不是"保留根目录 + 每次加 `--env-file ../.env`"**：那要求每条 compose 命令都记得加参数（`config`、`up`、`logs`…），漏一次就是同类故障；放同级只需记住一件事。
- **代价**：`.env.example` 在根目录、真实 `.env` 在 `deploy/`，两者不同目录——所以文档必须把路径写死（本轮已写），否则下一个人还会踩。
- **附带修正**：`deploy/docker-compose.local.yml` 里 rabbitmq 的口令原本硬编码 `local_pw`，会与 `.env` 的 `RABBITMQ_PASS` 分叉（broker 用 A、后端用 B，连不上且报错指向不明）→ 改为读 `${RABBITMQ_PASS:?}`，口令来源收敛到 `.env` 一处。
- **关联文档**：`README.md`、`deploy/README.md`、`.env.example`、`deploy/docker-compose.local.yml`（本地专用，不入库）

---

## D48 · 彻底移除 Redis 键 `order:accept_timeout:{id}`（"写入但无人读取的键就是死配置"）

- **日期**：2026-09-24（P1 步骤 5）
- **编号说明（冲突已上报）**：任务书要求"记 D46"，但 **D46 已被上一轮的"延迟消息持久化边界"占用、D47 被".env 位置"占用**，
  为不打断既有编号，本决定记为 **D48**；代码注释里写的是"D48（任务书原写 D46，编号冲突后顺延）"，不留下指向不存在条目的引用。
- **问题**：接单时曾有 `redisTemplate.set("order:accept_timeout:{id}", ..., 30min)`（写在 afterCommit 里），
  `startOrder` 里还有对应的 `redisTemplate.delete("order:accept_timeout:" + orderId)`。这个键要不要在步骤 5 随"时限归一"重新引入？
- **选择**：**不引入，并彻底移除两侧调用**（写入侧步骤 2 已删；本轮删除 `startOrder` 里的 delete）。
- **三条理由**：
  1. **从来没有被读取**：全仓无任何 `get("order:accept_timeout:*")`；它是纯写入 + 纯删除的自我循环。
  2. **真相来源已经明确且唯一**：MQ 路径的时机 = `t_event_outbox.deliver_at`（= 接单时刻 + `accept_minutes`），
     兜底路径的时机 = `t_sla_config.accept_minutes`（本轮步骤 5 收敛）。Redis 里再来一份副本只会制造第二个真相来源。
  3. **重新引入会把耦合加回来**：写入若放回接单事务链路上，就把"接单"绑到 Redis 可用性上（Redis 挂了连单都接不了）；
     而它带来的收益是 0（没人读）。
- **一般原则（写进本文件，供后续收敛共用）**：**一个"写入但无人读取"的键/字段/配置项就是死配置**——
  它与 I8（声明的可配置项必须被消费）是同一类问题，只是发生在存储层。收敛常量的目标是**让每处配置都有消费者**，
  不是再造一份副本；发现死配置时的默认动作是**删掉它并留痕**，而不是"留着以备将来用"。
- **代价**：① 少了一个"也许将来会用到"的旁路标记——如果将来真需要"接单超时"的实时信号，必须重新设计并说明消费者是谁（不能只是再写一个键）；
  ② `startOrder` 少了一次 Redis 往返（这是行为变更，已用 `StartOrderNoRedisTest` 覆盖：`delete(...)` 零调用 + 状态流转不变）。
- **关联文档**：`WorkOrderServiceImpl.startOrder/acceptOrder` 的注释、`docs/PENDING-RESTORE.md`、D38（步骤 2 移除写入端的记录）

---

## D49 · 测试自己会污染测试库：`claimPending` 是全局带 LIMIT 的查询（实测让全量测试失败）

- **日期**：2026-09-24（P1 步骤 5 全量跑时暴露）
- **反转留痕**：**原以为** `EventOutboxClaimReclaimTest` 只影响自己插入的那几行 → **后来发现**它调用的
  `claimPending` 是"全库 PENDING + 按 id 升序 + LIMIT"的查询：测试库里的历史残留会把 LIMIT 占满，
  **本次新插入的行反而抢不到**（实测报错 `expected: <SENDING> but was: <PENDING>`）；更糟的是，它抢走的那些行
  在清理时按"id 水位线"只删自己插入的，**残留行会永久停在 SENDING**（实测测试库积累 234 行，其中 200 行是永久租约）
  → **因此改为**：抢占调用传一个远大于残留量的 LIMIT（只断言自己那几行）+ 清理时按 owner 一并清掉本次抢占的租约。
- **代价**：① 测试不再"精确模拟生产参数"（生产用 100，测试用 100000）——这是为独立性付的代价，已在类注释写明；
  ② 仍然会留下少量 PENDING 残留（本次全量跑后 26 行，不再是永久 SENDING），彻底清零靠 `DELETE FROM work_order_test.t_event_outbox`。
- **附带发现（同一类问题的另一面）**：我手工插入的合成工单 706（步骤 4 用来构造 SKIPPED 场景）没有 `sla_deadline`，
  它是"未完结工单"里唯一 NULL 的一行 → **让不变量探针 P4 误报 FAIL**。已按 D19 最宽口径统计（日志 0/0、通知 0、outbox 0、工单 1）后删除，
  P4 恢复 PASS。教训：验证用的合成数据会被探针当成真实缺口，收尾时必须清理或让它满足不变量。
- **关联文档**：`EventOutboxClaimReclaimTest` 类注释（两个坑）、`sql/probes.sql`（P4/P16）、D24（测试污染应用的另一方向）

---

## D50 · 超长表格行的机械替换必须"先备份可恢复 + 立即按行数校验"（一次真实误操作留痕）

- **日期**：2026-09-24（P1 步骤 5 改文档时）
- **背景**：`INVARIANTS.md` 的 I8/I11 是**单行超长表格**（一行 700+ 字符），`apply_patch` 的行级匹配难以局部替换，
  因此用 PowerShell 做精确子串替换。
- **反转留痕**：**原以为**"传一组 [旧,新] 配对给脚本没问题" → **后来发现** PowerShell 把嵌套数组**展平成字符串数组**，
  于是 `$pair[0]` 成了**单个字符**，脚本对 `INVARIANTS.md` 做了逐字符替换——
  实测 `git diff --numstat` 显示 **73 行被改动**（全文的 `m` 被替换掉），文件被破坏 →
  **因此改为**：① 立刻 `git checkout -- INVARIANTS.md` 从 HEAD 恢复（本轮的编辑重做，代价只有几分钟）；
  ② 脚本改用**两个平行数组**（`$olds`/`$news` + 下标循环），不再依赖嵌套数组；
  ③ 每次替换后**立刻 `git diff --numstat` 校验改动行数**（这次应只有 2 行），而不是只看"命中/未命中"的打印。
- **教训（可复用）**：机械替换的风险不在"替换对不对"，而在"**替换范围是不是你以为的那个**"；
  只要按行数/字数校验一次，这类错误在 3 秒内就能发现——这正是 §6 第 5 项"回读校验"要解决的问题，
  只不过这次被校验的对象是**编辑动作本身**。
- **代价**：① 需要一次从 HEAD 恢复（仅在本轮未提交的编辑上做，不动已提交内容）；
  ② 长行文档更适合"拆行重构"而不是"子串替换"——本轮没拆（那会改动大量表格结构），留作后续。
- **关联文档**：`INVARIANTS.md`（I8/I11 行）、`CLAUDE.md` §6 第 5 项（回读校验）

---

## D51 · 服务器升级路径：老库只差一张表，因此只跑两个脚本（已用三代库比对验证）

- **日期**：2026-09-24（P1 步骤 6，服务器侧开工前由项目对话交付）
- **问题**：服务器代码停在 `0988ad6`、库也是当时建的 —— **没有 `t_event_outbox`**；而 `sql/hotfix-outbox-sending-state.sql`
  是"表已存在时的增量"（`ADD COLUMN` / `MODIFY` / `DROP INDEX`），直接跑会报 `Table ... doesn't exist`。
  这正是 CLAUDE.md §4 那条"老库不会重建，必须成套交付迁移脚本"要防的场景，而它真的发生了。
- **选择**：新增 `sql/hotfix-p1-outbox-init.sql`（最终形态的 `CREATE TABLE IF NOT EXISTS`），并把升级顺序定为
  **① `hotfix-p1-outbox-init.sql` → ② `hotfix-outbox-sending-state.sql`**（后者的"跳过"输出就是它自己的判据）。
- **依据（用 D41 定的可执行判定方式实测，不是推断）**：

| 库版本 | 处理 | 与"当前 `init.sql` 全新建库"比对（information_schema 列含注释 + 索引） |
| --- | --- | --- |
| A1 = `git show 0988ad6:sql/init.sql`（9 张表、无 outbox） | ① → ② | **True**（71 列） |
| A2 = `git show c926472:sql/init.sql`（outbox 是步骤 2 形态） | ①（无操作）→ ②（ALTER） | **True**（71 列） |
| 两脚本各重跑一遍 | — | 打印"已应用，跳过（影响 0 行）"，结构不变 |

- **顺带确认（决定了要不要跑别的脚本）**：`0988ad6` 与当前 `init.sql` 的差异**只有新增 `t_event_outbox`**——
  其余 9 张表的 `CREATE` 语句逐字节一致，30 条种子 INSERT（含 SLA 8 行、角色、权限、权限绑定）也逐条一致
  → **本服务器不需要** `hotfix-p0b-order-type.sql` / `hotfix-role-permissions.sql`（只在探针 P5/P11/P8 报错时才补）。
- **代价**：① 多了一个脚本要维护（`hotfix-p1-outbox-init.sql` 与 `init.sql` 的建表语句必须同步——由 D41 的比对方式兜住）；
  ② "两个脚本都要跑"比"一个脚本"多一步，运维容易漏第二步；因此清单里把第二步的"已应用，跳过"输出直接写成判据
  （没看到那行就等于没跑）。
- **服务器侧的操作清单**：`deploy/UPGRADE-P1.md`（含三个验证与 5 容器采样，可逐条粘贴）。
  服务器实测结论（三个验证 + 5 容器 RSS）将在拿到原始输出后追加为 D52 及后续条目。
- **关联文档**：`sql/hotfix-p1-outbox-init.sql`、`sql/hotfix-outbox-sending-state.sql`、`deploy/UPGRADE-P1.md`、`deploy/README.md`（升级章节）、D41（判定方式）

---

## D52 · 消费端幂等表 `t_consume_record`：事务边界第一，保留 30 天

- **日期**：2026-09-25（P4 步骤 1）
- **问题**：重复投递要不要去重？去重记录与业务写怎么放，才算"不会把消息静默吃掉"？
- **选择**：
  · 新建 `t_consume_record(event_id, consumer, consumed_at)`，**`UNIQUE(event_id, consumer)`**（`SHOW CREATE TABLE` 实测该唯一键存在，
    不是只看 DDL 文件）；consumer 取值约定 `order-release-listener`（写进列注释）。
  · 消费顺序 = **同一事务内：先 INSERT 去重记录 → 再执行释放**。
  · 重复投递 → UNIQUE 冲突 → **不执行业务**、ACK、记 DEBUG。
  · 保留 **30 天**，清理由 P6 的归档任务分批 `DELETE ... WHERE consumed_at < NOW() - INTERVAL 30 DAY LIMIT 1000`（走 `idx_consumed_at`）。
- **为什么把事务边界单独放到一个类**（`ConsumeRecordService`）：`@Transactional` 只在**跨 bean 调用**时生效；
  若 listener 自己开事务并在同一个类里自调用 `releaseOrder`，既绕过代理语义，也会绕过 `@OrderAction` 切面
  ——释放操作的审计日志会**静默丢失**。所以边界放在 `ConsumeRecordService`：它开事务，再通过注入的
  `WorkOrderService` **代理**调业务（`REQUIRED` → 加入同一事务，切面照常生效）。
- **顺序为什么不能反**（plan §5.2 原话）：先提交去重记录、再执行业务 → 业务一失败去重记录已落地 →
  重试被永久跳过 = **消息被静默吃掉，比重复更危险**。本轮用**测试**证明而不是读代码：
  让真实 `releaseOrder` 抛错，断言 `t_consume_record` 与工单表**都没留痕**（`ConsumeRecordIdempotencyTest`），
  并追加一例"回滚后重试仍能正常执行"（证明没被去重表永久拒之门外）。
- **保留 30 天的依据**：任何可能的重投窗口都远短于它——outbox 重试上限 20×30s≈10 分钟、手工重投按天计、
  broker 重启后延迟消息到点即投；30 天留两个数量级余量。表规模按日均 300–800 单≈2.4 万行/月，可忽略。
  **代价**：超过 30 天以后再重投的同一事件会被当成新事件处理一次——可接受，因为释放还有状态守卫兜底（判 `SKIPPED`）。
- **两道防线的职责**（写进 `OrderReleaseListener` 类注释与 `ConsumeRecord` 实体注释，不要因有了去重表就删状态守卫）：
  去重表回答"**这条事件消费过吗**"；状态守卫（`WHERE status='ACCEPTED'`）回答"**这张单现在该被释放吗**"。
- **开关**：沿用 `workorder.outbox.dispatch.enabled`，**不新增开关**——一个开关管整条链路，避免"发了没人收"或"收的人没开"。
- **反转留痕（顺带修掉一个真缺陷）**：**原以为** listener 里的 `String.valueOf(headers.get(HEADER_EVENT_ID))` 只是"取头" →
  **后来发现** 缺这个头时它返回字符串 `"null"`，于是**所有缺头消息共用一条去重键**，去重表会把后到的全部误判为"已消费"
  （在去重表之前的实现里不可见，因为当时没有去重）→ **因此改为** 显式判空、把 `null` 传下去（缺 eventId 时只记 WARN、
  不做去重但照常执行业务，剩状态守卫兜底），并加单测断言"绝不传 `\"null\"`"。
- **关联文档**：`sql/init.sql` 第 11 节、`sql/hotfix-p4-consume-record.sql`、`ConsumeRecordService`/`ConsumeRecord`/`OrderReleaseListener` 注释、`ASYNC-SCHEDULING-PLAN.md` §5.2

---

## D53 · 失败重投：两张表的事务要求相反；生产端短固定退避、消费端才用阶梯

- **日期**：2026-09-25（P4 步骤 2）
- **问题**：消费失败怎么重试？"退避"该用一套参数还是两套？以及……重试记录与去重记录能不能放同一个事务？
- **核心结论（写进 `ConsumeRecordService` / `MessageRetryService` / `ReleaseCheckConsumeService` 三处类注释）**：

| 表 | 与业务事务的关系 | 为什么 |
| --- | --- | --- |
| `t_consume_record` | **必须同事务** | 业务失败要连去重记录一起回滚，否则重试会被永久跳过（消息被静默吃掉） |
| `t_message_retry` | **必须在业务事务之外** | 业务失败**恰恰是要重试的原因**；跟着一起回滚就变成"失败了但没人记得要重试" |

- **实现选择（REQUIRES_NEW 还是独立 bean）**：**两者都用**——事务边界放在独立的 `MessageRetryService` bean（避免同类自调用绕过代理，
  也避免绕过 `@OrderAction` 切面），其写方法再标 `@Transactional(propagation = REQUIRES_NEW)`。
  理由：调用点既可能在业务事务的 catch 里（那时业务事务尚未回滚完），也可能在业务事务结束之后；
  `REQUIRES_NEW` 会挂起外层事务、用独立连接提交，对两种调用点都成立。代价：瞬时多占一个数据库连接，且**不允许**把它的方法当普通内联调用理解。
- **阶梯（怎么由 attempt 推出 next_retry_at）**：`attempt` = 已失败次数；第 N 次（N ≤ 5）→ `next_retry_at = now + LADDER[N-1]`，
  阶梯 = **1m → 5m → 15m → 1h → 6h**；第 6 次 → `status='PARKED'`、`next_retry_at=NULL` + ERROR 日志（人工介入入口）。
  累计跨度约 **7 小时 21 分**。
- **生产端为什么不用同一个阶梯**：outbox 的失败几乎总是"基础设施不可用"（broker 挂/网络不通），要的是 **RTO**——恢复后尽快把积压发出去；
  等 15 分钟/1 小时只会延长不可用时间。消费端的失败可能由脏数据/业务异常引起，阶梯能避免无意义反复重试。
  因此保持"**生产端 30s 固定、消费端阶梯**"两套，并把这条理由写进 `OutboxDispatchTask` 的注释（原来那句"P4 换成阶梯"已删除）。
- **抢占写法（与 `OutboxDispatchTask` 刻意不同，底线相同）**：本表状态枚举按设计只有 `PENDING/SUCCEEDED/PARKED`（没有 `SENDING`），
  所以用**时间租约**：先取 id（走 `idx_retry_dispatch`），再逐行 CAS
  `UPDATE ... SET next_retry_at = NOW()+lease WHERE id=? AND status='PENDING' AND next_retry_at<=NOW()`，**抢到的人才投**。
  好处：进程崩在投递中途时租约到期自动可重投，**不需要单独的回收任务**（比 outbox 那套简单）。
  代价：投递失败的那行看上去"还没到期"（最多晚 `lease` 秒），且没有 owner 列，排查"谁在投"只能靠日志。
- **反转留痕（本步最容易踩的坑，实测发现）**：**原以为**"三态 `ERROR` 是正常返回、事务会提交"没问题 →
  **后来发现** 去重记录会被留下：重投时 INSERT 命 UNIQUE → 被判"已消费"直接跳过，**重试账本被空转关掉、业务永远不会再执行** →
  **因此改为** `ConsumeRecordService` 在 `releaseOrder` 返回 `ERROR` 时显式 `setRollbackOnly()`
  （`ERROR` 也属于业务失败，去重记录必须一起回滚），并加测试断言"ERROR 路径：去重 **0** 行 + 重试账本 **1** 行"。
- **保留 30 天**：与 `t_consume_record` 同口径（依据见 `sql/init.sql` 第 11 节注释），P6 归档任务按 `created_at` 分批删除。
- **关联文档**：`sql/init.sql` 第 12 节、`sql/hotfix-p4-message-retry.sql`、`MessageRetryService` / `MessageRetryDispatchTask` / `ReleaseCheckConsumeService` 类注释、`ASYNC-SCHEDULING-PLAN.md` §5.5

---

## D54 · DLX / 停车队列：**不做**（DB 账本已覆盖它的职责，避免两条并行失败通道）

- **日期**：2026-09-25（P4 步骤 3）
- **问题**：RabbitMQ 的原生失败通道（DLX + 死信队列 / 停车队列）要不要上？
- **选择**：**不做**。（这是明确结论，不是"以后再说"。）
- **理由（三条）**：
  1. **职责已被覆盖**：`t_message_retry` 已经提供了 DLX 的全部能力——重试（阶梯 1m/5m/15m/1h/6h）、超限停车（`PARKED`）、
     可视化（SQL + 探针 P16d）、人工干预（一条 SQL 重放）。DLX 能给的，这里一样都不缺。
  2. **两条并行失败通道**：若再加 DLX，同一条消息的失败会同时出现在 broker 死信队列与 DB 账本里，
     **同一类事件就有两个处理点**——谁负责重试、谁负责停车、两边状态不一致时以谁为准，都会变成新的排障负担。
     这与本项目反复强调的"每类事件只有一个权威处理点"直接冲突。
  3. **运维面更宽**：死信队列需要额外的监控（深度/堆积）、清理策略、重放工具与演练；而 DB 表已有现成的探针（P16d/P16e）与清理路径（P6 归档）。
- **代价（如实写）**：
  · **消息不在 broker 的死信队列里**——排障时看数据库表（`t_message_retry` 的 `last_error`）而不是 RabbitMQ 管理台；
    习惯"去管理台看死信"的人需要一次观念切换。
  · 消费失败时**所有消息一律 ACK**（broker 侧不留副本），失败信息只有 DB 一份 → **DB 丢数据就没有第二份副本**。
    这与"outbox 是唯一投递出口"是同一类取舍：把可靠性押在数据库上（本项目接受这个取舍，因为释放还有兜底扫描兜底）。
- **何时需要重新评估**：出现"broker 侧需要统一失败视图"（例如多应用共用一套 MQ、运维统一在管理台看死信），
  或 DB 账本被证明不可靠（跨库/多租户）时。**本轮不存在这两个前提。**
- **关联文档**：`ASYNC-SCHEDULING-PLAN.md` §5.5（原生方案已标注"评估后不采用"）、
  `README.md`（排障小节的 PARKED 重放 SQL + "可靠性叙事"一节）、`sql/probes.sql`（P16d/P16e）

---

## D55 · Triage 异步化：提交不再等 LLM，落库 + 事件 + 消费端修正（含三项定稿）

- **日期**：2026-09-25（P5 步骤 1）
- **问题**：提交时缺 type/priority 会同步等 LLM（超时 5000ms），把**数据库连接**也一起占住（实测连接池 20 条被占满）。
  怎么改成"先落库、异步修正"，并且不让迟到/重复的分诊结果覆盖人工修改？
- **选择（四项定稿）**：
  1. **提交路径**：不再调 LLM。缺字段时用兜底值（`OTHER`/`0`，兜底组合来自 `t_sla_config`，不硬编码）落库、
     `triage_status='PENDING'`，并在**同一事务内**发 `ORDER_TRIAGE` 事件（复用 outbox，与 `publishReleaseCheck` 同一套写法）。
     字段齐全则 `triage_status='DONE'` 且不发事件。
  2. **"哪些字段未提供"的判定方式 = 消息元信息**：payload 里带 `missingFields:["type","priority"]`（瘦消息允许带这种元信息）。
     **为什么不用 NULL 表示"未提供"**：`t_work_order.type/priority` 是 NOT NULL（SLA 计算与前端都依赖它们有值），
     落库时必须写兜底值，NULL 语义不可用；而"消费时再查一次请求参数"更不可能——请求早就结束了。
     元信息随消息走还有个好处：它同时被 outbox 与重试账本持久化，**重投时不会丢**。
     因此消费端只写回 `missingFields` 里的字段，**用户手工填过的一律不覆盖**。
  3. **H4 重算规则**：基准 = `created_at + 新的 finish_minutes`（**不是 now**——否则"放了很久才分诊"的工单会被凭空续命）；
     重算后若已过期 → **立即告警**，并写同一个去重键 `sla_notified:{orderId}`（24h TTL），即**计为 H1 的首次告警，催办节奏自该时刻起算**。
     去重键与 TTL 从 `SlaEscalationScheduler` 暴露出来复用（`notifiedKey()` / `SLA_NOTIFIED_TTL`），不复制第二份常量。
  4. **失败复用 P4 的重试账本**：LLM 不可用/超时/返回非法值/找不到 SLA 配置 → `FAILED` → 去重记录回滚 + `t_message_retry`
     （consumer=`order-triage-listener`）按 1m/5m/15m/1h/6h 阶梯重投、超限 PARKED。**不另建一套重试机制**。
- **两道防线（与释放链路同构，守卫字段不同）**：去重表（`t_consume_record`）答"这条事件消费过吗"；
  状态守卫 `WHERE triage_status='PENDING'` 答"这张单现在还需要分诊吗"——后者专门挡**迟到的分诊结果覆盖人工修改**。
  实现上把守卫写进 UPDATE（`AND triage_status='PENDING'`），影响 0 行即 SKIPPED，消除"先查再写"的并发窗口。
- **存量工单的 triage_status 默认值 = `DONE`**（不是 NULL、不是 PENDING）：
  ① 语义正确——存量单要么用户填了类型、要么改造前已同步分诊过，都属"已定稿"；
  ② 不会被误触发——派发查询是 `WHERE triage_status='PENDING'`，默认 DONE 不会让老单被重新分诊（**危险区自查项**）；
  ③ 列是 NOT NULL 三值枚举，用 NULL 会多出第四种实际取值，与"只挑 PENDING"的简单查询冲突。
- **实测对照（同口径：本机、stub LLM 固定 3s 延迟、压测脚本校验业务 code、预热分离）**：

| 场景 | 请求数 / 业务成功 | P50 | P95 | P99 | max | MySQL 连接数 |
| --- | --- | --- | --- | --- | --- | --- |
| 改造前（同步等 LLM，并发 30） | 150 / 150 | 3097.8ms | 3110.6ms | **3110.9ms** | 3111ms | **恒为 21（= 池上限 20 全占 + 采样器 1）** |
| 改造后（异步 triage，并发 30） | 4680 / 4680 | 110.7ms | 202.7ms | **230.7ms** | 240.2ms | 21（池按需扩容后保持，但**在忙时间从 3s 降到毫秒级**） |
| 改造后（并发 5） | 4420 / 4420 | 21ms | 26.5ms | **33.5ms** | 169.5ms | — |

> ⚠ **本表两行的绝对值已作废，只作历史留痕**（ps1 的"波最慢值"计时缺陷 + "慢的 400"样本，更正见 D56）：
> **现行口径见 README §9.1**——P50 3130.5ms→149.4ms、P99 6185.6ms→264.4ms（三行同参数、正反各一遍，取数 2026-09-25）。

  吞吐从 7.5 req/s 提到 234 req/s（31×），且**提交延迟与 LLM 无关**。
  ⚠ 本机没有可用的 LLM key，上面的"改造前"数字用**固定延迟 3s 的 stub**取（`scripts/stub-llm.py`），
  是**同口径对照**而非真实模型基线；真实模型的绝对值必须在配好 key 的机器上重取（见 D56）。
- **顺带修掉一处漂移**：`OrderTriageServiceImpl` 的 `VALID_TYPES` 与 prompt 仍是 R4 之前的旧类型集合
  （REPAIR/LEAVE/REIMBURSE/OTHER）——LLM 即使返回新类型也会被判非法并静默回落成 OTHER。已改为
  NETWORK/UTILITY/DORM/OTHER（与 `WorkOrderServiceImpl.ALLOWED_TYPES` 一致），并更新了对应测试。
- **代价**：① 提交后 type/priority 可能短暂是兜底值（用户看到"其他/普通"直到分诊完成）——用 `triage_status` 与修正日志可见；
  ② 多一条事件与一个消费者（triage 队列、监听器、开关复用同一属性）；③ LLM 慢不再拖提交，但**分诊结果可能迟到**
  （迟到的结果由状态守卫丢弃，见上）。
- **关联文档**：`sql/init.sql`（`triage_status`）、`sql/hotfix-p5-triage-status.sql`、`OrderTriageConsumeService` / `OrderTriageListener` / `WorkOrderServiceImpl.submitOrder` 注释、`README.md` 的 P5 小节、`ASYNC-SCHEDULING-PLAN.md` §2.2 与 P5 进展

---

## D56 · 本机没有 LLM key：基线用可控延迟的 stub 取，真实模型数字待重取

- **日期**：2026-09-25（P5 步骤 1）
- **事实**：本机 `LLM_API_URL` / `LLM_API_KEY` 均未设置（实测 `$env:LLM_API_KEY` 为空），
  因此**取不到"真实 LLM 可用"前提下的改造前基线**。用户明确要求"基线必须在 LLM 真实可用的前提下取"——
  这一条**未满足**，必须显式上报，而不是拿一个近似值冒充。
- **处置**：新增 `scripts/stub-llm.py`（可配 `STUB_DELAY_MS`，响应体与 OpenAI 兼容格式一致），
  用它把"提交时同步等外部调用"的行为**原样复现**在同一条代码路径上（`RestTemplate` + 5s 超时 + 事务内调用），
  取到**同口径相对对照组**（改造前 P99 3111ms → 改造后 231ms@并发30 / 33.5ms@并发5）。
- **代价与边界**：stub 的数字只说明"延迟被消除了"与"连接不再被占用"，**不能**当作真实模型的绝对延迟；
  `scripts/loadtest.sh`/`loadtest.ps1` 与 stub 都已入库，配好 key 的机器上重跑即可得到真实基线（步骤见 README 的 P5 小节）。
- **关联文档**：`scripts/stub-llm.py`、`scripts/loadtest.ps1`（Windows 等价压测，含"为什么不能用 `$env:USERNAME`"）、`scripts/loadtest.sh`（新增 `OMIT_TYPE=1`）、D55

---

## D57 · "声明了但没接上"第三次：LLM 两个变量从未传进容器（附 .env.example 全键审计）

- **日期**：2026-09-25（P5 收口）
- **事实**：`.env.example` / `deploy/.env` 都列了 `LLM_API_URL` / `LLM_API_KEY`，README 也写了用途，
  但 `deploy/docker-compose.yml` 的 `backend.environment` 里它们**是注释掉的**（原文 L153-155：`# LLM_API_URL: ""`）——
  容器内两值恒为空 → `OrderTriageServiceImpl` 每次 `triage()` 直接返回 `TriageResult.fallback()`（OTHER/0）且**不记日志**。
  线上表现："AI 把所有单都判成 OTHER"，而日志里没有任何异常。

  > **追加（P5 步骤 3，2026-09-25）**：当时描述的"每次直接返回 fallback 且不记日志"是**那一轮的事实**；
  > 本轮之后该路径已不存在——`triage()` 在未配置/调用失败时**抛 `TriageUnavailableException`** 并记 WARN，
  > 由消费端走重试账本（见 D62）。也就是说 D57 修的是"配置没传进来"，D62 修的是"传进来了但失败被当成成功"。
- **这是同类问题的第三次**：① `somaxconn`（写了不生效）；② `MYSQL_PASSWORD`（compose 从不读该键，实际读 `MYSQL_ROOT_PASSWORD`，D30）；
  ③ 本次 `LLM_API_URL/LLM_API_KEY`（列了但没传进容器）。三次的共同点：**"声明"与"生效"之间没有任何检查**，
  失败形态都是"静默降级/静默无效"。
- **修复（三件事，缺一不可）**：
  1. compose 的 `backend.environment` 补上 `LLM_API_URL: ${LLM_API_URL:-}` / `LLM_API_KEY: ${LLM_API_KEY:-}`。
     **用 `:-`（允许为空）而不是 `:?`（必填）**：缺 key 时"提交仍成功、triage 降级"是设计好的降级路径，
     用必填会让"可降级依赖"变成"硬依赖"（服务起不来）。代价是没有硬报错，所以配了第 2 件事。
  2. `OrderTriageServiceImpl` 增加**启动期 WARN**：配置为空时明确提示"triage 将始终降级为 OTHER/普通（提交仍成功），
     容器部署请确认变量已通过 compose 传进容器"。**只在启动时打一次**（triage 是热路径，每单一条 WARN 会刷爆日志；
     而"没配 key"是启动期就能确定的事实）——这条是本类的通用解药：**让静默降级变得可见**。
  3. 部署冒烟项新增"容器内这两个变量非空"（与 P12a 登录、P15 时区并列，见 `deploy/UPGRADE-P1.md` §6.4），
     README 的环境变量清单写明"为空时的行为"与"必须经 compose 传入"。
- **顺带审计 `.env.example` 的每一个键**（用户要求"不要只修这一个"）——16 个键逐个核对是否有 compose 落点：

| 键 | compose 落点 | 结论 |
| --- | --- | --- |
| `MYSQL_HOST` / `MYSQL_PORT` / `DB_NAME` / `MYSQL_USER` | `${VAR:-mysql/3306/work_order/root}` | **本轮从硬编码改为参数化**（原来 .env 里写了不生效） |
| `REDIS_HOST` / `REDIS_PORT` | `${VAR:-redis/6379}` | 同上 |
| `RABBITMQ_HOST` / `PORT` / `USER` / `PASS` | `${VAR:-rabbitmq/5672/workorder}` / `${VAR:?}` | 已有（P1 步骤 3 接的） |
| `MYSQL_ROOT_PASSWORD` | `${VAR:?}`（mysql 与 backend 各一处） | 已有（必填，缺了直接报错） |
| **`OUTBOX_DISPATCH_ENABLED`** | 原为字面量 `"true"` → 现为 `${VAR:-true}` | **审计发现的第二处"列了但没接上"**：.env 里写 false 原本不生效（已修） |
| **`LLM_API_URL` / `LLM_API_KEY`** | 原为注释 → 现为 `${VAR:-}` | 本条主体（已修） |
| `TEST_DB_NAME` / `TEST_REDIS_DB` | 无（由 `application-test.yml` 消费） | **不是缺陷**：只在 `mvn test` 时用，compose 本就不该传 |

  审计命令（可复跑，逐键确认落点）：把 `.env.example` 里的键逐个在 `deploy/docker-compose.yml` 里找 `KEY:` 赋值行；
  再用 `docker compose config | grep -E 'LLM_API_URL|OUTBOX_DISPATCH_ENABLED'` 看容器**最终**拿到的值（实证比读文件可靠）。
- **代价**：① 参数化容器内地址（`MYSQL_HOST` 等）后，把 `.env` 写成 `localhost` 会让容器连自己——
  已在 `.env.example` 与 compose 注释里写明"留空即用服务名"；② 启动 WARN 只覆盖"没配"，
  覆盖不了"配了但 key 失效/模型改名"——那类要靠运行期日志（`LLM triage失败` 的 WARN 已经在打）。
- **关联文档**：`deploy/docker-compose.yml`（backend.environment 注释）、`OrderTriageServiceImpl.warnIfNotConfigured`、
  `.env.example`（三个块的说明）、`README.md` §5.1、`deploy/UPGRADE-P1.md` §6.4、D30（同类第二次）

---

## D58 · 模型名参数化 + LLM 启动期探测自检（附 .env.example 17 键复核）

- **日期**：2026-09-25（P5 收口第二步）
- **编号口径说明（冲突已上报）**：任务书写"这是同类问题第三次（配置项没有落点）"，但 **D57 已经把
  "LLM_API_URL/KEY 没传进容器"记为第三次**。这里的处理是：同一"声明与生效脱节"家族按**形态**分开记——
  · 前三次（somaxconn、MYSQL_PASSWORD/D30、LLM 两变量/D57）是**"声明了但没接上"**；
  · 本条是**反方向**："值存在但没有任何配置落点"（模型名 `gpt-3.5-turbo` 硬编码在代码里，用户无法换模型，不属于任何配置项）。
  两者是同一家族的两个方向，故不合并、也不改 D57 的措辞，而是把家族关系写清。
- **问题**：① 模型名硬编码；② LLM 配错（模型名不对/key 无效/URL 不通）与"没配"在运行期表现完全一样——全是**静默降级**成 OTHER/普通。
- **选择（三件）**：
  1. **`LLM_MODEL` 参数化**：`application.yml` 的 `llm.api.model: ${LLM_MODEL:gpt-3.5-turbo}` + compose 传 `${LLM_MODEL:-}` +
     代码侧兜底 `DEFAULT_MODEL`（**空串 ≠ 未设置**：Spring 的 `:默认值` 只在属性缺失时生效，而 compose 传进来的是空串）。
  2. **启动期探测自检 `LlmStartupCheck`（`@PostConstruct`）**：未配置 → **ERROR**（"未配置 LLM，triage 将始终降级"）；
     配置存在 → 打一次**最小请求**，失败按状态码分类打 ERROR：**400=模型名不对 / 401·403=key 无效 / 连不上超时=URL或网络**。
     **不阻止启动**（与 `SlaConfigStartupCheck` 同模式；`DataSourceAvailabilityCheck` 才是 fail-fast）。
  3. **README 排障三步**：①查 `.env` 三个键 → ②查变量是否进容器（`docker compose config` + `docker exec printenv`）→ ③手工 curl 打一次模型接口。
- **代价**：① 配了 LLM 时启动会多一次外部请求，最坏等满 `llm.api.timeout`（默认 5s）才继续——不阻止启动，但会拖慢启动；
  ② 探测只覆盖"启动那一刻"的可用性，运行期失效仍靠 `LLM triage失败` 的运行日志；
  ③ 探测用 `ping` 这一类最小请求，会消耗一次极小的 token 额度（可接受）。
- **`.env.example` 17 键复核**（在 D57 的 16 键基础上新增 `LLM_MODEL`）：逐键在 `deploy/docker-compose.yml` 里找赋值行 →
  **15 键有 compose 落点**（含本轮新增的 `LLM_MODEL`），`TEST_DB_NAME` / `TEST_REDIS_DB` **仅测试用**、compose 本就不该传（设计如此）；
  并用 `docker compose config` 复核容器最终拿到的值（实证优先于读文件）。
- **实测（三种自检输出）**：
  · 空配置：`ERROR [启动自检] 未配置 LLM_API_URL，triage 将始终降级为 OTHER/普通（提交仍成功…）`
  · 错 key（stub 返回 401）：`ERROR [启动自检] LLM 探测失败：HTTP 401（LLM_API_KEY 无效或无权限）：…`
  · 正确配置（stub 200）：`INFO [启动自检] LLM 探测通过（triage 可用）`
  三种情况应用都**照常启动**（不阻止启动已由三次启动日志证明）。
- **追加（同日，去掉默认模型名）**：原实现给了 `DEFAULT_MODEL = "gpt-3.5-turbo"`（`application.yml` 的 `:gpt-3.5-turbo` 与 `.env.example` 的示例值也是它）。
  **问题**：默认值必须与 `LLM_API_URL` 所属供应商匹配，而供应商彼此不通用——DeepSeek 只认 `deepseek-flash`/`deepseek-v4-pro`，
  配了 DeepSeek 的 URL 再带上这个默认值，**每次调用都 400**，而 400 又被降级吃掉，表现仍是"AI 全判 OTHER"。**默认值把一个必然失败的目标写进了每一次调用**。
  **改动**：① `application.yml` 改 `model: ${LLM_MODEL:}`（无默认值）；② 删掉 `DEFAULT_MODEL` 兜底，模型名为空走与"配置为空"同一条自检分支 → ERROR"未配置 LLM_MODEL（必须显式配置，且要与 LLM_API_URL 所属供应商支持的模型名一致）"，
  **不退化成"发一个空模型名"**；③ `.env.example` 的 `LLM_MODEL` 从 `<optional:…>` 改为**显式示例值** `deepseek-flash` 并注明必须与供应商一致；④ 400 的文案改成"模型名可能不被支持…必须与 LLM_API_URL 所属供应商匹配"，README 排障表补这一行。
  **原则（本条要传下去的一句话）**：**"指向错误目标的默认值比没有默认值更糟"**——没有默认值时错误是**启动期可见**的（自检 ERROR），
  而错误的默认值会把错误推迟到**每一次运行期调用**，且被降级路径静默吞掉。默认值只应在"所有部署形态下都成立"时给出（如容器内服务名 `redis`）。
- **关联文档**：`OrderTriageServiceImpl.probeFailure`、`LlmStartupCheck`、`application.yml`、`deploy/docker-compose.yml`、
  `.env.example`、`README.md`（§5.1 与排障章节）、`scripts/stub-llm.py`（新增 `STUB_HTTP_STATUS` 用于演练 401/400 分类）、D57

---

## D29 · 不处理历史中的 `WorkOrder@2026`；将来若要公开则新建仓库

- **日期**：2026-09-24
- **问题**：`bc9700e` 提交把默认库口令 `WorkOrder@2026` 写进了 compose（工作区已移除，但**它仍在 git 历史里**）。要不要改写历史把它抹掉？
- **备选项**：① 用 `git filter-repo`/`filter-branch` 重写全部历史；② 保持现状、只在文档里记录；③ 直接删掉 `.git` 重新初始化
- **选择**：**② 保持现状**。理由组合：仓库**保持私有** + 该口令**从未用于任何真实部署**（它只是 compose 里的示例默认值，本机与服务器都用环境变量覆盖）+ **改写历史的收益低于风险**（会重写全部提交哈希、破坏与 `origin/master` 的关系、需要强推，且任何误操作都可能丢数据）。
- **反转留痕**：**原以为**"发现口令进过历史就要清理历史" → **后来发现**清理的前提是"数据会被公开且口令真实有效"；这里两条都不成立（保持私有、从未真实使用），而强推重写历史的代价是确定发生的 → **因此改为**不改历史，只做两件事：**工作区移除**（已完成）+ **在文档里留下判断依据**（本条）。
- **若将来要公开，正确做法是新建仓库**：以**当前状态**（已无该口令）作为新仓库的**首次提交**，而不是把现有仓库的可见性从私有改为公开——后者会把全部历史一起暴露。这样既达到公开目的，又不需要重写历史。
- **代价**：现有私有仓库的历史里长期存在这个口令字符串；对任何能读该私有仓库的人（协作者）它是可见的。因为它从未用于真实部署，判定为可接受风险。
- **关联文档**：`docs/DECISIONS.md` D28、`deploy/docker-compose.yml`、`.env.example`

---

## D59 · 提交通知：接收人按角色广播；第二道幂等键选 `event_id` 而不是 plan 提的四元组

- **日期**：2026-09-25
- **问题**：P5 步骤 2 要把"提交后通知处理人"落地，两件事必须先定死：
  ① **通知谁**——plan §2.1 的论证前提是"一个校区 30–50 名处理人，逐个 insert 就是 30–50 次单行插入"，即全部处理人；
  ② **第二道幂等键选哪个**——plan §5.2 为通知设计的"天然业务键"是 `(ref_type='ORDER', ref_id, target_user_id, event_type)`，
  并说"可在 `t_notification` 上补唯一索引"，**但这条在当前代码上建不起来**：`ref_type/ref_id` 从不回填
  （P1 步骤 3 收口实测业务库 82 条通知里两列各 82/82 为 NULL），而 NULL 在唯一索引里互不相等 → 索引形同虚设。
- **备选项**：
  - a) 先补 `ref_type/ref_id` 的回填，再建 plan 那个四元组唯一索引；
  - b) 在 `t_notification` 上加 `event_id` 列，建 `UNIQUE(event_id, user_id)`；
  - c) 只靠去重表 `t_consume_record`，不做第二道防线。
- **选择**：**b**。理由三条：
  1. **plan 的四元组缺"事件版本"维度**：两次合法但同类型的通知（例如同一工单 T0 首次 SLA 超时、T0+24h 第二次催办）
     在 `(ref_type, ref_id, user_id, event_type)` 上**完全相等**，唯一索引会把第二次判为重复 → **静默漏发**。
     这正是 plan §5.2 自己立下的规矩被违反的地方：那条规矩是"**幂等键必须是事件维度，不能是实体维度**"，
     而 `event_id` 天然带 version（`order:{id}:v{version}:{eventType}`）。
  2. **`ref_*` 只有新链路能填**：SLA 超时与驳回达上限两条老通知链路本轮按边界不动，它们的 `ref_*` 仍为 NULL。
     唯一索引允许多个 NULL 并存 → 四元组索引对这两条链路**等于没有约束**。
     "部分生效的防线"比"明确只覆盖一部分"更危险：它会让人误以为重复通知已经被拦住。
  3. `event_id` 与消费端的去重键**同源**：一个键同时贯通"消费去重"与"通知去重"，排障时不需要在两种键之间做映射。
- **代价 / 遗留**：plan §5.2 的那条设计**仍未实现，只是被替代**——`ref_type/ref_id` 现在只有提交通知这条新链路回填
  （前端跳转要用 `NotificationVO.refType/refId`），**另外两条链路仍未回填**；这仍是待办的一部分（README §四已标注）。
  另外 `t_notification` 多一列、多一个唯一索引，且每张工单会产生"处理人数"行（30–50 行/单）——这正是接收人解析
  必须放在消费端、不能进提交事务的直接原因（plan §2.1）。
- **通知对象口径**：**按角色**（`HANDLER` 全体）。**不按部门**——工单表 `t_work_order` 没有部门字段
  （只有 `t_user.dept_id`），按"提交人所在部门"解析等于发明一条文档里不存在的规则；登记为"已评估、不采用"
  （见 `BUSINESS-SCOPE.md` F1-1 的「提交通知规则」表）。
- **顺带把规则写进业务基线**：`BUSINESS-SCOPE.md` F1-1 新增验收项 + 「提交通知规则」表（通知谁/条件/渠道/幂等/失败处置），
  **不新增功能条目**——它是"提交工单"的副作用，不构成独立功能，功能总数保持 **25**。
- **关联文档**：`OrderSubmittedConsumeService`、`OrderSubmittedListener`、`InAppNotifyChannel.sendOnce`、
  `NotificationServiceImpl.sendToRoleOnce`、`sql/hotfix-p5-submit-notification.sql`、`sql/init.sql`（t_notification 的注释块）、
  `BUSINESS-SCOPE.md` F1-1、`ASYNC-SCHEDULING-PLAN.md` §2.1 / §5.2

---

## D60 · 压测工具的三个缺陷：过去"改造前"的数字是"慢的 400" + "波最慢值"（三行同参数重测取代旧值）

- **日期**：2026-09-25
- **问题**：要交一张"改造前 / 改造后 / 改造后+通知"三行同参数对照表。取数过程中发现**工具本身**有三处缺陷，
  它们都不会报错、只会让数字看起来"正常"：
  1. **桩读不到 chunked 请求体**：`scripts/stub-llm.py` 只按 `Content-Length` 读 body，而 Java/Spring 的 POST 用
     `Transfer-Encoding: chunked`（实测请求头无 `Content-Length`）→ 桩读到 0 字节 → `model=None` →
     白名单判它"模型名不对" → **返回 400**，且在客户端仍在发送时就写回响应（客户端报"连接被中止"）。
     **后果**：过去所有"改造前（同步等 LLM）"的数字都是**慢的 400**——延迟量级仍由桩的固定延迟决定，
     但**一次成功的 LLM 往返都没有**，响应 `type` 是兜底的 `OTHER`。
  2. **桩的监听队列太小**：`socketserver` 默认 `request_queue_size = 5`，30 并发下被内核打满，
     Windows 上表现为客户端 `Connection refused: getsockopt`（应用降级成兜底值）→
     压测行被污染成"部分走通、部分兜底"（实测 18%）。
  3. **ps1 的计时口径错**：`loadtest.ps1` 在 `Task.WaitAll` 之后才逐个 `Stop()`，于是同一波里每个样本记录的
     都是**该波最慢请求**的耗时，而不是它自己完成的时刻。bash 版用 `curl -w '%{time_total}'` 一直是每请求真实值
     → **两版脚本口径不同**（正是 `CLAUDE.md` §5 那条规则要防的事，只是这次差的不是判据而是**计时**）。
- **选择（三处都修，不用"标注限制"了事）**：
  1. 桩支持 chunked 请求体（`_read_body`）；
  2. 桩的监听队列 5 → 128（`StubServer.request_queue_size`）；
  3. ps1 改为**按完成顺序收割**（`Task.WaitAny` 循环），与 bash 版口径对齐。
  另在桩的文件头记下 Windows 的一个陷阱：`allow_reuse_address=True` 允许**两个进程同时绑同一端口**，
  于是"重启桩"可能只是又起了一个进程、请求仍打到旧的（未修复的）那个——必须先确认旧进程已退出。
- **验证（可复现）**：修复后——① Java 客户端提交一次，响应体 `type=OTHER`、`priority=1`（= 桩的返回值，
  降级只会是 `fallback()` 的 0），应用日志 triage 失败 **0** 条；② 30 并发跑改造前构建，
  **P50 3.13s / P95 6.18s / P99 6.19s，且分布是双峰**（80 个样本 <3.5s、40 个 ≥6s）——
  双峰正是"池容量 20 vs 并发 30"的真实形状；修复前同一场景是**全部 6.15s 的单峰**（= 波最慢值）。
- **代价 / 对历史结论的影响**：
  - README §九 里旧表的两行**不要再引用绝对值**（"改造前 3110.9ms"是慢的 400 + 波最慢值；
    "改造后 230.7ms"是波最慢值，同一构建修正后是 **P99 193.8–206.4ms**）。**结论方向不变**：提交不再等外部调用。
  - 服务器批已取的数字若来自 bash 版（`curl %{time_total}`），**不受**缺陷 3 影响；但若当时用的是桩，
    则受缺陷 1/2 影响（服务器批的"改造前"数字需按同一口径重取，已登记为待办）。
  - 教训（与本项目一贯的口径一致）：**压测工具的容量与计时语义必须和被测系统一起被验证**；
    "工具不报错"不等于"工具在正确测量"。
- **关联文档**：`scripts/stub-llm.py`（文件头的两处修复说明）、`scripts/loadtest.ps1`（计时修复 + 头注释）、
  `CLAUDE.md` §5（"口径"包含计时）、`ASYNC-SCHEDULING-PLAN.md` §1.6.5（第二条压测方法学）、`README.md` §6.3 与 §9.1

---

## D61 · P5 步骤 3 的前端部分：**不做"分类中"的本地猜测**（缺的是后端 VO 字段，不是前端）

- **日期**：2026-09-25
- **问题**：P5 步骤 3 要求前端"放开 type 必填 + 展示分类中/已分类/分类失败三态"，并声明"不改后端（triage_status 列与接口已就绪）"。
  实测这个前提**只对了一半**：
  1. **列在、接口不在**：`git grep triageStatus src/main/java/com/workorder/common/vo src/main/java/com/workorder/controller` **无命中**——
     `triage_status` 只存在于实体 `WorkOrder`，`WorkOrderVO` / `WorkOrderDetailVO`（以及前端据此对齐的 `api-docs.json`）都**没有**这个字段。
     因此 `GET /api/orders`、`GET /api/orders/{id}`、`POST /api/orders` 三个响应里都读不到分诊状态。
  2. **第三种状态在数据上不存在**：全仓只有两处写 `triage_status`——提交时写 `PENDING`（缺字段）或 `DONE`（字段填全），
     以及 `updateTriageResult(...)` 成功写回时置 `DONE`。**没有任何语句写 `'FAILED'`**（分诊失败走的是重试账本，
     工单停在 `PENDING` 等下一轮）。所以列注释里的三态，实际只有两态。
- **备选项**：
  - a) 前端**猜测**：把"提交时没选类型"记在本地（store/sessionStorage），然后轮询 `type/priority/slaDeadline` 是否变化，
    没变就显示"分类中"，变了就显示具体类型；
  - b) 前端**只做能确定的部分**（放开必填 + 三态的渲染与映射），字段缺失时**不渲染任何分诊状态**，
     把"后端补一个 VO 字段"作为待批事项上报；
  - c) 前端顺手把后端 `WorkOrderVO` 加上该字段（**越界**：本轮的边界明确写着"不改后端"）。
- **选择**：**b**。理由：
  1. **a 会写进假状态**：AI 完全可能判出 `OTHER/普通`——这与兜底值**在数据上完全一样**，
     于是"没变化"被解读成"还在分类中"，界面会**永久显示分类中**（数据库里其实早就是 `DONE`）。
     这正是本项目最忌讳的一类错误（"看起来正常"的静默错误），比不显示更糟。
  2. `FAILED` 态在 a 里根本无法产生（没有数据来源），只能靠"超时"编造出一个第三种状态。
  3. **c 越界**：`CLAUDE.md` §5 明确"跨阶段的改动必须先说明理由，不许顺手做"。
- **已交付（本轮，边界之内）**：
  · `OrderCreateView.vue`：`type`/`priority` 都改为可选（`el-select` + `clearable`，placeholder「不选则由系统自动判断」），
    **提交时只带用户真的选了的键**（空串/默认 0 会被后端当成"用户已指定"，导致整条分诊被跳过）；
  · `types/order.ts`：`SubmitOrderReq.type/priority` 改可选、`WorkOrderVO.triageStatus?` 与 `TRIAGE_STATUS_MAP`（分类中/已分类/分类失败）；
  · `OrderInfoPanel.vue` / `OrderListView.vue`：「类型」格按 `triageStatus` 渲染（`v-if` 守卫，字段缺失时完全不渲染）；
  · `frontend/CLAUDE.md` §3.4 更新（旧的"同步返回建议值"描述已作废）。
  · 验证：`npm run build`（`vue-tsc -b` + `vite build`）**0 错误**。
- **~~待批（越界 2 行）~~ 追加（同日，已实施）**：经用户批准后已落地，**比预估多两处实体**——
  ① `WorkOrderVO` 加字段、但要在**两处**手写 `toVO` 里赋值（service 一份、controller 一份；
  第一次只改了 service 那份，是"真实响应体回读"抓出来的）；② 账本转 PARKED 时把工单置 `FAILED`（同事务）；
  ③ **另有第三个缺陷**（失败被洗成成功）必须一并修，否则 FAILED 仍到不了——三项的选择、代价与实测见 **D62**。
- **代价**：本轮无法完成"界面显示分类中"这一条演示动线验收（缺数据源），也无法给出 UI 截图：
  **本会话的浏览器自动化不可用**（`cua.getState()` 返回 `{"browsers":[],"errors":["Browsers: Error: unsupported Codex auth method: apikey"]}`）。
  可用证据只剩"文字时序"：本机真栈实测 `t+40ms` 提交返回（`type=OTHER/priority=0`，SLA = created+480min）→
  `t+1.7s/3.3s/4.9s` 仍 `PENDING` → `t+6.5s` 写回 `NETWORK/1`、SLA 收缩为 created+60min、库内 `triage=DONE`，
  全程应用日志 **0 条 LLM 失败**（见 `README.md` §9.2）。
- **关联文档**：`frontend/src/types/order.ts`、`frontend/src/views/orders/OrderCreateView.vue`、
  `frontend/src/components/order/OrderInfoPanel.vue`、`frontend/src/views/orders/OrderListView.vue`、
  `frontend/CLAUDE.md` §3.4、`BUSINESS-SCOPE.md` F1-4 与 §6.1 第 1 步、`ASYNC-SCHEDULING-PLAN.md` P5 进展

---

## D62 · triage_status 暴露 + FAILED 可达：**顺带修掉第三个缺陷——"失败被洗成了成功"**

- **日期**：2026-09-25
- **问题**：按批准实施两件事（① 两个 VO 暴露 `triage_status`；② 账本转 PARKED 时把工单置 FAILED）时，
  实测发现有**第三个缺陷**，不修它，②的目标根本达不到：
  `OrderTriageServiceImpl.triage()` 把**所有失败**（未配置 / 超时 / 网络不可达 / 401 / 500 / 响应体解析不了）
  catch 掉并 `return TriageResult.fallback()`（= `OTHER/普通`）。同步时代这是对的（用户正等着提交，不能因外部抖动失败），
  **异步化之后这条契约就错了**——消费端拿到的 `OTHER/0` 与"AI 真的判成其他/普通"**完全无法区分**，于是：
  · LLM 一次都没调通，工单却被写成 `triage_status='DONE'`（界面显示"已分类：其他"，**假状态**）；
  · "失败 → 重试账本 → 阶梯重投 → 停车 → FAILED"这条链**一次都走不到**（消费端只在返回值"非法"时才判失败，
    而兜底值恰好合法）——F1-4 验收里"triage 不可用/超时 → 保持 PENDING + 重试账本有记录"**实际未实现**。
  **证据（本轮实测）**：把桩改成返回 401 后提交一张缺字段的工单 → 工单 `triage_status=DONE`、
  `t_message_retry` **0 行**（本该是 PENDING + 1 行）。这正是 D60 那类"看起来正常"的错误。
- **为什么之前没被发现**：`OrderTriageConsumeTest` 用 `@MockBean` 把 triage 服务换成了 mock，并让失败 `thenThrow(...)`——
  **测试钉的是"应该抛"，生产实现却是"返回兜底值"**，mock 掩盖了契约分歧。教训：用 mock 钉契约时，
  必须至少有一条**不经 mock** 的路径把真实实现钉住（本轮已补 `OrderTriageRealFailureTest`）。
- **选择（三件一起做）**：
  1. **接口暴露**：`WorkOrderVO` 增 `triageStatus` 字段，并在 **两处** `toVO` 里赋值——
     `WorkOrderServiceImpl.toVO`（列表/详情）与 `WorkOrderController.toVO`（提交响应，**两份手写映射**）。
     `WorkOrderDetailVO` 内嵌 `WorkOrderVO`，因此详情无需再抄一份字段（抄一份反而多一个同步点）。
     **本题第一次只改了 service 那份，是"真实响应体回读"抓出来的**：`POST /api/orders` 的 body 里没有该字段，列表里却有。
  2. **FAILED 可达**：`MessageRetryService.recordFailure` 在"attempt 超限转 PARKED"的**同一事务**里调用
     `WorkOrderMapper.markTriageFailed(orderId)`（带 `AND triage_status='PENDING'` 守卫）；
     `orderId` 优先从瘦消息 payload 的 `{"orderId":N}` 取，取不到退回事件键 `order:{id}:v…`。
     只对分诊消费者生效——释放检查有兜底扫描、提交通知只影响提醒，**只有分诊没有兜底通道**。
  3. **失败不再被洗成成功**：`triage()` 改为**抛 `TriageUnavailableException`**（新增类），
     `parseResponse` 同样不再吞异常。同步时代的"返回兜底值"语义随同步路径一起作废（提交路径早已不调它）。
- **代价**：
  · 契约变更会动测试：`OrderTriageServiceTest` 里 8 个"失败返回兜底值"的用例改为 `assertThrows`（**改的是过时契约，不是为了让测试变绿**）；
  · `TriageResult.fallback()` 在生产代码里不再被调用（保留为"兜底值"的统一定义处，仍被单测引用）；
  · 分诊失败现在**真的会重试 6 次**（1m/5m/15m/1h/6h ≈ 7h21m）后才 FAILED——这是正确的代价，但意味着
    "AI 挂了"的场景下界面会先显示 7 小时"分类中"，再变"分类失败"。
- **验证（真栈实测，2026-09-25）**：
  · 成功路径：提交 t+125ms 返回（body 含 `"triageStatus":"PENDING"`）→ 列表/详情同为 PENDING →
    t+6.9s 详情变成 `type=NETWORK, priority=1, triageStatus=DONE`，`slaDeadline` 由 created+480min **收缩为 created+60min**；
  · 失败路径：桩返回 401 → 账本 1 行（`last_error=TriageUnavailableException: LLM triage 失败：401 Unauthorized`）、
    工单仍 PENDING（界面"分类中"）→ 阶梯走完 → 账本 `6/PARKED` **且**工单 `triageStatus=FAILED`（界面"分类失败"），
    日志依次出现 `[triage] LLM 调用失败…`（WARN）→ `[retry] 分诊重试已停车，工单分诊状态收口为 FAILED`（ERROR）；
  · 探针：新增 **P17**（`triage_status=PENDING` 且创建超 1 小时 = 0）→ 上述 FAILED 场景下 **P17=PASS**（PENDING 不再是假终态），
    P16d=1（有停车记录，正是需要人工介入的信号）。
  · **同事务不能只靠读代码**：用 **`@SpyBean` 故障注入**证明——`OrderTriageParkedAtomicityTest` 让真实
    `WorkOrderMapper.markTriageFailed`（事务里的**第二次写**）抛异常 → 断言**账本没有变成 PARKED**（`attempt` 仍 5）
    **且工单仍是 PENDING**，即两次写一起回滚；对照用例（不注入）在同一前置下两者一起到达终态。
    这条测试防的是"将来有人为了'让失败更可见'把其中一句挪到方法外"——注释不会报警，它会。
  · 全量测试：**206 passed / 0 failed**。
- **关联文档**：`WorkOrderVO`、`WorkOrderController.toVO`、`WorkOrderServiceImpl.toVO`、`WorkOrderMapper.markTriageFailed`、
  `MessageRetryService.recordFailure`、`OrderTriageServiceImpl.triage`、`TriageUnavailableException`、
  `sql/probes.sql`（P17）、`README.md` §9.2、`BUSINESS-SCOPE.md` F1-4、`INVARIANTS.md` I12、D61

---

## D63 · 三处调整 + 评测集落地：**prompt 保守规则降级为可选加固（不实施）**、`finish_months` 全仓不存在、系统操作显示为「系统」

- **日期**：2026-09-25
- **背景**：上一轮的结论里有一处**归因错误**需要更正——曾把一次"人工选择"误读成"AI 判错"，
  并据此提出给分诊 prompt 加"信息不足时保守"的规则。本轮逐项复核后三项处置如下。

### 1. prompt「信息不足时保守」→ **降级为可选加固，本轮不实施**

- **更正**：不再声称"信息不足时行为随机"——**没有任何可复现证据**支撑该结论（原判断来自对某行日志的误读）。
- **处置**：**不改 prompt**。若将来决定加，必须满足两条：① 报告与本文里**只能写成"加固"（hardening），不得写成"修复"**；
  ② **不得编造触发它的证据**——要么给出评测集上可复现的失败样例（见下面第 4 项），要么明确写"这是无证据的预防性加固"。
- **代价**：不加 = 信息不足的工单仍可能被模型给一个具体类型（本轮机械自检里桩就是这个形态：6 条信息不足全部被判 `NETWORK/1`）；
  加 = prompt 变长、可能让"明确案例"的召回下降，且**需要用评测集证明是净收益**（这正是评测集存在的意义）。
- **关联**：`OrderTriageServiceImpl.buildPrompt`（当前**没有**保守规则）、`scripts/triage-eval-cases.json` 的 `insufficient` 组。

### 2. `finish_months` → `finish_minutes`：**全仓（含未入库文件）0 命中，无可改动**

- **核实命令与结果**（不是"已检查、无问题"这种结论）：
  `Get-ChildItem -Recurse -File | Select-String -Pattern "finish_months"`（排除 `node_modules`/`target`/`.git`）→ **无命中**；
  仓库侧 `git grep -rn "finish_months"` → 同样无命中。正确拼写 `finish_minutes` 在 `sql/init.sql`、`t_sla_config`、H4 日志文本里都是对的。
- **结论**：**本轮没有可改的地方**。若你看到的那处在某个**未入库的日志/报告**里（"442 行的真实分诊日志"），请把它贴给我，
  我按同一处修正——**不猜、不编造文件位置**。

### 3. 系统操作显示：`operatorId = 0` → 「系统」（顺带补 `TRIAGE` 的动作名）

- **问题**：`OrderLogTimeline.vue` 写的是 `log.operatorName ?? \`用户${log.operatorId}\``。
  `operator_id = 0` 是**系统**（AI 分诊写回、超时释放），它没有对应的 `t_user` 行 → `operatorName` 为 null →
  界面显示 **"用户0"**，既像有个 ID 为 0 的用户，也埋没了"这是机器干的"这条信息。
- **修复**：新增 `operatorLabel()`：有 `operatorName` 用它；否则 `operatorId === 0` → **「系统」**，其余仍显示 `用户{id}`。
  顺带补 `ACTION_MAP.TRIAGE = 'AI 分诊修正'`——否则时间线里 AI 那一条的动作标签直接显示原始码 `TRIAGE`。
  这两处都在同一段显示逻辑里，且都指向"让系统/AI 的动作可读"，故一并处理。
- **验证**：`npm run build` 0 错误；产物回读 `dist/assets/OrderDetailView-*.js` 含 `operatorId` 与新文案，`order-*.js` 含 `AI 分诊修正`。

### 4. 评测集落地：`scripts/triage-eval-cases.json`（20 条）+ `scripts/triage-eval.py`（跑分）

- **为什么**：本轮的真实问题是"**人工指定 vs AI 判定在界面上不可辨**"，根因是**缺少测量**——
  判得准不准不能靠读一两条日志推断。评测集把这件事变成可复现的数字。
- **它测真链路**（不直接调模型）：每条只带 `title/content` 提交 → 轮询到 `triageStatus` 离开 `PENDING` →
  比对最终 `type/priority`。因此同时覆盖 prompt、消费端白名单、H4 重算与"分诊失败"分支。
- **用例设计**：14 条有标准答案（其中 4 条是**真歧义**，用 `expect_types` 数组承认两种答案都对）+ 6 条 `insufficient`；
  优先级只在文本有**明确**紧急信号（人身安全/大面积/爆管/冒烟）或明确局部轻微时才标注，其余不评（不拿主观标准当准确率）。
  `insufficient` 组单独看"是否保守"：判 `OTHER` 且 `priority=0` 记保守。
- **本次实测（**机械自检**，桩固定返回 `NETWORK/1`）**——**不是准确率**，只证明跑分脚本能正确判对/判错：
  ```
  类型准确率（不含信息不足）：5/14 = 35.7%
  优先级准确率（仅标注期望值的 8 条）：5/8 = 62.5%
  信息不足组保守率：0/6
  失败清单：10 条（N2 优先级、U1/U2/U3/D1/D2/O1/O2/E1/E4 类型）
  ```
  20 条用例耗时约 24 秒（投递间隔临时降到 1s；生产默认 5s）。
- **待做（需要真实 key）**：本机与 `deploy/.env` **都没有 LLM key**（`LLM_API_KEY` 为空），所以**真实准确率尚未测量**。
  在配了 key 的机器上一条命令即可：
  `python scripts/triage-eval.py --base-url http://127.0.0.1:9000 --timeout 150`（要求后端 `OUTBOX_DISPATCH_ENABLED=true`）。
  **脚本会打印本次产生的工单 ID 与清理 SQL**——按 D19 的规矩，写数据的东西必须说清写了什么、怎么清；
  **不要对着业务库/演示库跑**（用 `DB_NAME=wo_eval` 之类专用库）。
- **踩坑留痕**：Windows 控制台是 GBK，直接把 `✓/✗` 打进 stdout 会抛 `UnicodeEncodeError`（本轮实测），
  已改为 `sys.stdout.reconfigure(encoding="utf-8")` + ASCII 标记 `[OK]/[X]`，保证 cmd/PowerShell/Linux 都能跑。

### 5. 「人工指定 / AI 判定 在界面上区分」的评估结论 → **暂不做（见 D64）**

- 结论、三个方案与代价、触发条件另记一条：`docs/DECISIONS.md` **D64**。

- **关联文档**：`frontend/src/components/order/OrderLogTimeline.vue`、`frontend/src/types/order.ts`（`ACTION_MAP`）、
  `scripts/triage-eval.py`、`scripts/triage-eval-cases.json`、`README.md` §六（怎么跑分）、`D64`

---

## D64 · 界面区分「人工指定 / AI 判定」：**暂不做列级来源字段**（结论 + 代价 + 触发条件）

- **日期**：2026-09-25
- **问题**：类型/优先级只有**最终值**，**谁写的没有落库**。于是"用户选了 UTILITY、AI 又把它改成 NETWORK"
  与"用户没选、AI 判成 NETWORK"在列表/详情里长得一模一样——本轮一次误读就是这么发生的。
- **现状（先确认已有多少能力，别重复造）**：
  · **详情页的操作日志时间线里已经有** `TRIAGE` 记录（`operator_id=0`，remark 形如"AI 分诊修正: type OTHER→NETWORK …"），
    经过 D63 的两处小改后，它现在显示为「AI 分诊修正 / 操作人：系统」，**可读且能看出改了什么**；
  · 列表页与详情页顶部的「类型」格**看不出**来源；`triageStatus` 只回答"分诊完没完"，不回答"这个值是谁定的"。
- **三个方案与代价**：

  | 方案 | 需要加字段？ | 改动面 | 能表达什么 |
  | --- | --- | --- | --- |
  | a) 每字段来源列 `type_source` / `priority_source` ∈ {USER, AI} | **要**（1 条迁移 + 2 列） | 提交时写 USER、分诊写回时写 AI（`updateTriageResult` 的 UPDATE 里带条件区分）、VO/前端类型、类型格加标记 | **最准**：能表达"类型是 AI 判的、优先级是用户选的"这种混合 |
  | b) 单一 `triage_source` ∈ {NONE, AI, MIXED} | 要（1 列） | 比 a 少一列、少一处判断 | 只能整体说"AI 动过"，丢失每字段精度；`MIXED` 的判定逻辑反而更绕 |
  | c) 不加字段，靠已有日志（现状 + D63 的两处显示修复） | 不需要 | **已完成** | 详情页能看清"AI 改过什么"；但**列表页看不出**，且要展开详情+日志才知道 |

- **结论：暂不做（a/b），保留 c**。理由三条：
  1. **收益/代价不匹配**：a 的价值是"让用户/处理人一眼看出这个类型是机器给的"，
     而当前真正的瓶颈是**判得准不准根本没有测量**（D63 第 4 项刚补上评测集）。在准确率未知时先加来源标记，
     等于给一个还不知道好不好的判断配上更醒目的展示。
  2. **c 已经覆盖了主要场景**：真正关心"是不是 AI 改的"的人（处理人/管理员）本来就会看日志时间线；
     而列表页的读者是"扫一眼有哪些单"，来源字段在那里更多是噪音。
  3. **加字段的时机成本**：现在加 = 又一条迁移 + 两处写入 + 前端渲染，**且要跟 `triage_status` 的三态语义对齐**
     （例如 `FAILED` 时 source 该是什么）。等"是否需要"从评测与真实使用里浮出来再加，代价更低。
- **触发条件（出现任一条就做 a）**：
  · 评测集跑出"AI 判错"的稳定模式（例如某类信息不足的工单被硬判成具体类型占比高），需要向用户解释"这不是你选的"；
  · 出现真实支持场景："我没选这个类型，为什么显示这个"；
  · P5 步骤 3 之后前端成为主入口，用户不选类型成为常态（那时"AI 判定"就是绝大多数单子的来源，值得标记）。
- **若做，就做 a 而不做 b**：per-field 来源是唯一能表达"混合"的模型（用户只填了 title/content 之外还手选了优先级这类），
  而 b 的 `MIXED` 只是把 per-field 信息压扁后再猜回来。
- **代价（若做 a 的如实预估）**：1 条幂等迁移（2 列 + 注释）、提交路径写 `USER`、分诊写回按 `missingFields` 写 `AI`、
  `WorkOrderVO` 两个字段 + 前端类型、类型格与列表加标记（还要处理 `FAILED`/`PENDING` 下的显示优先级）。
- **关联文档**：`docs/DECISIONS.md` D61（三态显示）、D62（接口暴露）、D63（评测集与本次三处调整）、
  `frontend/src/components/order/OrderInfoPanel.vue`、`OrderLogTimeline.vue`、`t_work_order_log`（`TRIAGE` 记录）

---

## D65 · 按评测结果改 prompt 两处（类型边界 + 保守规则），**并承认"改后数字还没跑出来"**

- **日期**：2026-09-25
- **证据来源（必须写清，因为它不是我跑出来的）**：真实模型的一次跑分判读由用户给出——
  **类型准确率 85.7%（12/14）、信息不足组保守率 4/6**；逐条判读：**O1 = 真实模型错**（期望值不改）、
  **D2 = 边界模糊**（改 prompt，不改期望值）、**I4/I6 = 可识别的倾向**（构成保守规则的依据）。
  **本机复现不了这组数字**（见下面"待测"），所以 D65 只记录**改动与理由**，不记录改后数字。

### 一、prompt 补类型边界定义（对治 D2）

原文只写了"NETWORK(网络/断网), UTILITY(水电), DORM(宿舍/住宿), OTHER(其他)"——**边界靠字面猜**，
于是"走廊灯不亮"这种公区照明被判到别处（D2 的读法：边界模糊，不是模型错）。
现在把四类写成可判的边界（`OrderTriageServiceImpl.buildPrompt`）：

| 类型 | 边界（写进 prompt） |
| --- | --- |
| NETWORK | 网络 / WiFi / 网口 / 交换机 / 路由器 / 校园网；业务系统（选课、借还书、教务）无法访问 |
| UTILITY | 供水或供电的**中断或危险**：停水、停电、跳闸、爆管、水龙头/管道漏水、配电箱冒烟、漏电 |
| DORM | 照明（走廊灯/教室灯/应急灯）、门窗、家具（桌椅/柜子/床）、锁具（含门禁刷卡）、其他公区设施 |
| OTHER | 建议、咨询、投诉等非故障类诉求；以及信息不足以判断的情况 |

### 二、prompt 补保守规则（对治 I4/I6）

信息不足以判断时：**type 必须 OTHER、priority 必须 0，并在 `reason` 里写明依据不足、缺什么**。

**措辞纪律（按要求写清）**：这是**有依据的改进**（两条可复现失败样例 I4/I6），**不是"修复缺陷"**——
模型的行为不是错误值，而是**偏好偏乐观**（宁可猜一个具体类型）。文档与报告里都按"加固/改进"表述。

### 三、prompt 与评测集一起演进（用户指定的副作用，已落实）

边界写具体后，原先靠 `expect_types` 数组承认"真歧义"的两条变成**按规范可判**，故收窄为单值：
**O2 门禁 → `DORM`**（"锁具（含门禁刷卡）"）、**E2 宿舍 WiFi → `NETWORK`**（"网络/WiFi → NETWORK"）。
每条保留 `baseline_expect_types`（收窄前的口径）。**因此"改前基线"与"改后数字"不是同一口径**，
差值里含 O2/E2 的口径变化成分，报告里必须分开说——这正是"三组数字一起看、别只看总准确率"的一部分。

### 四、配套小改动：让"写明依据"不至于写了不生效

prompt 要求模型给 `reason`，但代码原先只解析 `type/priority`、日志也不打——那这条要求就是**写了不生效**
（没人能区分"模型判成非故障类"与"模型没看懂"）。因此：
`TriageResult` 增 `reason` 字段（**保留两参构造器**，既有调用点与单测不用改）→ `extractResult` 解析它（缺了不影响判定）
→ 消费端写回日志追加 `依据=…`。**只进应用日志，不动 `t_work_order_log.remark`**（避免列长/截断引入新风险）。
新增两个单测：给了 reason 要解析出来；没给 reason 不影响判定。

### 五、代价与待测

- **代价**：prompt 明显变长（每次调用 token 上升）；`reason` 让响应变长；写回日志多一个字段。
  另一处代价是**口径迁移**：收窄 O2/E2 之后，改前/改后必须按各自口径读（见上面第三节）。
- **待测（本轮的硬缺口）**：**本机没有 LLM key**（`$env:LLM_API_KEY` 为空、`deploy/.env` 无 LLM 键、
  常见本地模型端口无服务），所以**改后的三组数字我跑不出来**。本轮只做了**机械自检**：
  用桩（固定返回 `NETWORK/1`）跑完 20 条 → 类型 5/14、优先级 5/8、保守 0/6、失败清单 10 条，
  **与改前一致**（桩对 prompt 免疫），它证明的是"新 prompt + 跑分脚本 + 链路能跑通"，**不是准确率**，
  也**不能**与真机基线（12/14、4/6）并列。
  在配了 key 的机器上一条命令即可产出：
  `python scripts/triage-eval.py --base-url http://127.0.0.1:9000 --timeout 150`
  （前置 `OUTBOX_DISPATCH_ENABLED=true` + broker + 真模型；**用专用库**，脚本会打印工单 ID 与清理 SQL）。
- **改后要一起看的三组**：① 类型/优先级准确率；② 信息不足组保守率（预期 4/6 → 6/6）；
  ③ 失败清单（O1 是否修好、D2 是否改判 DORM）。**特别查"修好一类、坏了另一类"**：
  保守规则最容易的副作用是把"信息其实够、只是写得短"的工单也判成 OTHER（表现为准确率升、覆盖度降）。
- **关联文档**：`OrderTriageServiceImpl.buildPrompt`、`TriageResult.reason`、`OrderTriageConsumeService` 写回日志、
  `scripts/triage-eval-cases.json`（O2/E2 收窄 + `baseline_expect_types`）、`scripts/triage-eval.py`、`README.md` §6.4、D63、**D68**（改后真机数字：两遍 14/14）

---

## D66 · 超时门槛 5s → 15s + 评测口径修正（超时与判错分开、**不缩分母**）；**改后真数字仍未跑出**

- **日期**：2026-09-26

### 一、`llm.api.timeout`：5000 → 15000（依据来自实测分布，不是感觉）

- **依据**：真机评测各条端到端耗时 **2.0–9.1s**，模型调用本身占 **2–5s** → **5s 门槛落在分布中部**，
  长输入（如 E4 那条长文本）必然偶发失败。
- **连接/读取是否都覆盖**：是。`OrderTriageServiceImpl` 构造器把**同一个** `timeoutMs` 同时喂给
  `factory.setConnectTimeout(...)` 与 `factory.setReadTimeout(...)`（`:56-57`），
  所以这一个配置项同时管住"连不上"与"读不到"；`SimpleClientHttpRequestFactory` 没有第三个"整请求超时"旋钮。
- **代价（按用户要求写清，并补两条我实测发现的）**：
  1. **单次失败被感知得更晚**：最坏 ≈ 连接 15s + 读取 15s **≈ 30s** 才降级（两个都挂满时）；
  2. **启动自检**复用同一个 `RestTemplate` → 最坏多等 10s（**不阻止启动**，只拖慢启动）；
  3. **新增（本轮发现）**：消费端调模型是在 `consumeOnce` 的**事务里**（会占住一条数据库连接），
     超时从 5s 抬到 15s 意味着单条消息最多占连接 15s（最坏 30s）。当前 `listener.concurrency=1`、池上限 20，
     占比 ≤5% 可接受；**但若将来调大消费并发，这条必须先重算**（把 LLM 调用挪出事务，或给消费端单独配更短的超时）。
     > 已被 D67 取代：LLM 调用自 2026-09-26 起在事务外。"占连接 5s→15s"与"调大并发前要重算连接"均不再适用，新约束见 D67。
  - 可接受的理由：配合"**失败进重试账本**（1m/5m/15m/1h/6h）+ **信息不足保守降级**"，晚一点失败不会造成错误数据。

### 二、评测脚本：等待上限 90s → 150s，并把"两种没通过"分开计数

- **等待上限**：首次重试退避是 **1 分钟**，90s 窗口下任何一次 LLM 失败都会被记成"未判定"，
  读者分不清"最终失败"与"还在重试"。150s ≥ 提交 + 首次失败 + 1 分钟退避 + 重投 + 二次调用。
- **三档计数**（本轮新增，输出里分开）：
  | 档 | 含义 |
  | --- | --- |
  | `判错` | 分诊完成且有结论，但与期望不符 |
  | `超时未完成` | 等待上限内 `triageStatus` 始终 `PENDING` |
  | `分诊失败` | `triageStatus='FAILED'`（阶梯走完、账本 PARKED，见 D62） |
- **对外口径（关键修正）**：`超时未完成` 与 `分诊失败` **都按失败计、且保留在分母里**（不缩分母）。
  改前脚本遇到超时会 `continue`，**不进分母**——那会让"跑不出来的用例"从分母里消失，数字更好看但不可比。
  信息不足组同理：未判定按"不保守"计，分母保持整组 6。

### 三、本轮实际跑了什么、没跑什么（避免把机械验证当成准确率）

- **没跑**：改 prompt + 改 timeout 后的**真机准确率**。本机 **没有 LLM key**（`$env:LLM_API_KEY` 为空、
  `deploy/.env` 只有 MySQL/RabbitMQ、无本地模型服务）——所以**行 A（改前基线）之后的新数字仍然待测**，
  本轮不填。对外数字必须用"**超时/失败计为失败、不缩分母**"的口径，例如 `13/14 = 92.9%`，并注明分母。
- **跑了（口径验证，非准确率）**：两轮针对性验证，证明新计数语义真的生效：
  | 轮 | 条件 | 结果 |
  | --- | --- | --- |
  | A | **不启动 broker**（必然全部超时）+ `--timeout 1` | 类型 **0/14 = 0.0%**（不缩分母）、信息不足保守 **0/6**、**判错 0 / 超时未完成 20 / 分诊失败 0** |
  | B | broker 正常 + 新 prompt + `timeout=15000` + 默认等待 150s（桩固定返回 NETWORK/1） | 类型 5/14、优先级 5/8、保守 0/6、**判错 10 / 超时未完成 0 / 分诊失败 0** |
  两轮对照说明：**超时被单独计入且不缩分母**（A），**判错与超时不会互相冒充**（A vs B），
  且新 prompt 在新超时配置下链路仍然跑得通（B 的 20 条全部产出结论）。**B 的数字是桩的，不是准确率。**
- **副作用清理（D19）**：两轮验证都跑在专用库 `wo_eval` 上，跑完按脚本打印的 SQL 清理（`orders 20→0`、
  `outbox 40→0`、`logs 40→0`），随后 `DROP DATABASE wo_eval`；临时 broker 容器已删、无残留进程。

### 四、要看的三个点（拿到 key 后一次跑完）

```bash
python scripts/triage-eval.py --base-url http://127.0.0.1:9000          # 默认等待上限 150s
```
1. **E4 是否通过**：通过 → 说明之前是**超时机制**而非 prompt 回归；仍超时 → 单独查（它是长文本，最吃 15s 上限）。
2. **失败清单里还有没有"超时未完成"**：有 → 先看 app 日志的 `[triage] LLM 调用失败：…` 与
   `t_message_retry`（是否在阶梯重试），不要直接归因到 prompt。
3. **三组一起看**：类型/优先级准确率、信息不足组保守率（预期从 4/6 升到 6/6）、失败清单——
   特别查"修好一类、坏了另一类"（保守规则最容易把"写得短但信息够"的工单也判成 OTHER）。

- **分母要写清（两行不可直接比大小）**：
  · **行 A（改前基线）**：真机 12/14 = **85.7%**，分母 14 = 非信息不足用例，**旧期望口径**（O2/E2 双值）；
  · **改后（待测）**：x/14，分母同为 14，但**期望已收窄**（O2=DORM、E2=NETWORK），且**超时/失败计为失败**。
- **关联文档**：`src/main/resources/application.yml`（`llm.api.timeout` 及其代价注释）、
  `OrderTriageServiceImpl`（`setConnectTimeout`/`setReadTimeout`）、`scripts/triage-eval.py`（三档计数 + 默认 150s）、
  `README.md` §6.4、D62（FAILED 可达）、D65（prompt 两处改动）、**D68**（改后真机数字：两遍 14/14）

---

## D67 · 把 LLM 调用移出事务（结构性修复）：三段式 + **"重复调一次 LLM"的窗口** + 一条度量方法学的反转

- **日期**：2026-09-26
- **问题（改动前的病）**：`OrderTriageConsumeService` 在 `consumeOnce` 的**事务内**调 LLM。
  LLM 调用要 5–15s（最坏 30s），于是**一条消息占住一条数据库连接**整个调用期间；
  并且 `listener.concurrency` 一调大就等量吃连接 —— **并发上限被连接池锁死**（concurrency=1 时吞吐 ≈0.2 单/秒）。
  这恰恰是本项目当初批评"同步调 LLM"的那条理由，只是搬到了消费端。
- **选择：三段式**（`OrderTriageConsumeService.consume`）
  | 段 | 位置 | 内容 |
  | --- | --- | --- |
  | ① | **事务外** | 准入预检（读一次工单，自动提交，语句结束即归还连接）→ **调 LLM** → 校验结果可用性 |
  | ② | **事务内** | `consumeOnce` —— 去重 INSERT + 写回 + SLA 重算(H4) + 修正日志 |
  | ③ | **事务外** | 失败时写重试账本（`MessageRetryService` 自己 `REQUIRES_NEW`） |
  **P4 的两条规则一个字没动**：去重记录仍与业务写**同事务**；重试记录仍在业务事务**之外**。
  预检是**省 token 的优化，不是权威守卫**——权威守卫仍是事务内那条
  `UPDATE ... WHERE triage_status='PENDING'`（预检与事务之间用户仍可能改动工单）。
- **代价（必须写清）：多了两个"重复调一次 LLM"的窗口**
  1. **调用完成 → 事务提交之间崩溃/被杀**：这条消息没有去重记录，MQ 重投时会**再调一次 LLM**（多花 token）；
  2. **同一条事件被并发重复投递**：两个消费者可能各调一次，然后一个插去重记录、另一个撞唯一键被跳过。
  **为什么可接受**：写回有状态守卫，重复调用**不会产生错误结果**，最坏是多花一次 token；
  而"连接被 LLM 占住导致整条链路吞吐锁死"是更严重的结构性问题。要连这点浪费也消掉，
  只能回到"先写去重记录再调 LLM"，那要求去重记录**先于**业务写独立提交 —— 正是 P4 明令禁止的方向。
  窗口写在三处：`OrderTriageConsumeService` 类注释「六」、本条目、`scripts/tx-probe.ps1` 头注释。

### 实测（可执行判据，不是读代码）

| 场景 | 桩延迟 | 并发 | 工单数 | 总耗时 | 排空期 `innodb_trx` min/median/max | 样本 ≥2 个未提交事务 |
| --- | --- | --- | --- | --- | --- | --- |
| **修复前** | 10s | 3 | 6 | 20.9s | **0 / 3 / 4** | **92/94** |
| **修复后** | 10s | 3 | 6 | 21.3s | **0 / 0 / 0** | **0/93** |
| 修复前 | 5s | 5 | 20 | 21.0s | （未采样） | — |
| 修复后 | 5s | 5 | 20 | 21.2s | （未采样） | — |
| 修复前 | 5s | 1 | 20 | **101.3s** | （未采样） | — |

- **"LLM 调用期间不持有 DB 连接"**：**已证明**——同条件（10s 桩、并发 3）下，
  未提交事务数中位数从 **3 → 0**，"≥2 个未提交事务"的样本占比从 **92/94 → 0/93**。
- **"并发上限被打开"**：把并发从 1 提到 5，20 单从 **101.3s 降到 21.0s**（LLM 是唯一瓶颈）。
  这次修复的意义是：**这个提升不再以连接占用为代价**——修复前 concurrency=5 需要 5 条连接被持续占用
  （这正是"调大并发就吃连接"的实证），修复后为 0。
- **P4 两条规则仍成立**：全量 `mvn test` **208 passed / 0 failed**，含
  `OrderTriageRealFailureTest`（LLM 不可用 → 去重 **0 行** + 账本 **1 行**）、
  `OrderTriageParkedMarksFailedTest`（PARKED + FAILED 同事务）、`ConsumeRecordIdempotencyTest`。

### 度量方法学的反转（本轮最值得记的一条）

**原以为**："LLM 期间是否持有连接"看 MySQL 的 **`Threads_connected` 峰值**就够了（用户给的判据也是这个）。
**实测发现**：它**判定不了**——`Threads_connected` 统计的是**打开的会话**，而 Hikari 池一旦涨上去
（`idle-timeout=300s`）就不会马上缩，于是修复前后都停在 8–11，**看不出差别**
（修复前@5 峰值 11、修复后@5 峰值 10，几乎一样；提交期的短事务也会把峰值顶到 6–7）。
**真正等价的指标是"未提交事务数"**：`SELECT COUNT(*) FROM information_schema.innodb_trx` ——
"持有连接"在数据库侧的表现就是"**有一个未提交事务正持有它**"，而 LLM 调用在事务里时这个事务一直是开着的。
该指标修复前 median=3、修复后 median=0，**一眼可判**。
这条方法学已写进 `scripts/tx-probe.ps1` 的注释（探针同时输出两个指标，避免后来者再被峰值骗一次）。

### 残留（本轮不动的）

- **H4 b-1 的 `alertImmediately` 仍在事务内**（Redis `SETNX` + 按角色插入站内信）。
  本轮范围只动 LLM；它是"事务里还有外部/批量副作用"的下一处，量级远小（SYS_ADMIN 人数个位数），
  但要挪就得连"Redis 写不参与 DB 事务"一起想清楚——**登记为后续可优化点，不在本轮**。
- `NOT_RETRYABLE`（缺 `x-event-id`）分支行为不变。
- **探针工具**：`scripts/tx-probe.ps1` 已入库（一次性验证脚本转正），它同时输出
  `Threads_connected` 与 `innodb_trx` 两套指标 —— 前者用于回应用户的原始判据，后者用于真正的判定。

- **关联文档**：`OrderTriageConsumeService`（类注释五/六）、`ConsumeRecordService.consumeOnce`（P4 规则）、
  `scripts/tx-probe.ps1`、`README.md` §6.5、D52/D53（P4 两条规则的来源）、D66（超时 15s 与连接占用的关系）

### 收口（2026-09-26）：把 D67 的后果从"仍在说 LLM 在事务里"的 4 处文档里清掉

改动本身（`OrderTriageConsumeService` / `tx-probe.ps1`）在上一个提交里已完成；本小节只记录**文本收口**，
便于回读校验（逐条给出文件与判据）：

| # | 位置 | 改动 |
| --- | --- | --- |
| 1 | `src/main/resources/application.yml`（`llm.api.timeout` 的代价注释 ③） | **删掉**"消费端调模型是在 consumeOnce 的事务里…单条消息最多占连接 15s"整段，替换为：(a) LLM 调用已在**事务外**（D67），单条消息占连接的是**两次短事务**（毫秒级），**不随 timeout 放大**；(b) **新约束**：调大 `workorder.outbox.listener.concurrency` 前要算的是 **LLM 侧并发**（供应商限流、超时叠加）与 **broker 未确认消息堆积**，**不再是连接池**。①② 两条（最坏 ≈30s 才降级 / 启动自检最坏多等 10s）**原文保留** |
| 2 | `docs/DECISIONS.md` D66 §一.3 | **不改原文**，紧随其后加引用块：`> 已被 D67 取代：…` |
| 3 | `ASYNC-SCHEDULING-PLAN.md`（P5 门槛调整那段）与 `README.md`（§6.4 的两项门槛说明） | 各加**同一句**引用块标注 |
| 4 | `README.md` §九"怎么读这组数字" | 加澄清：那里的 **21 是"顶在池上限不动"＝池被打满**；§6.5/D67 说"判定不了"的是**池有余量时 8–11 那种峰值**——**两者不矛盾** |
| 5 | `scripts/triage-eval.py` | ① 清理 SQL **增两行**（`t_consume_record` / `t_message_retry` 按 `event_id REGEXP '^order:(ids):'`），文件头写清"**删除范围必须覆盖所有按 `event_id` 存的表**"及其后果（失败用例的重试账本行会被重投任务反复投递）；② `login()` 加**最多 3 次重试、间隔 2s**，文件头注明理由（容器刚重建时 docker 端口代理**先接后断** → `ConnectionResetError`，不是应用没起）；③ 新增 **`--reverse`**（反转用例顺序，供"第二遍反向顺序"对照） |

**为什么这算 D67 的一部分而不是新决策**：这 5 处都是 D67 的**后果**——不改它们，文档会继续教人"LLM 在事务里、
要按连接池算并发"（P2 真去调并发时会照着算错），评测脚本也会因为清理不彻底而**留下被反复重投的账本行**。
**判据（回读校验）**：`application.yml` 里那句"占连接 15s"已不存在、且出现"新约束…不再是连接池"；
三处引用块文本一致；`triage-eval.py` 含两行 `event_id REGEXP` 与 `--reverse`。

---

## D68 · 分诊评测：**改后真机数字（两遍 14/14）** + 上一轮那两条超时的病根由账本坐实

- **日期**：2026-09-26
- **数据来源（必须写清）**：**服务器真机 + 真模型**，正向/反向两遍（`python scripts/triage-eval.py` 与 `--reverse`）。
  **本机无 LLM key**（`$env:LLM_API_KEY` 为空、`deploy/.env` 只有 MySQL/RabbitMQ）
  → **因此本机无法独立重跑这次评测**；
  **但两份原始输出已随附录 A/B 入档**（来源、字节数与行数均已核对），
  → **因此这批数字可以被逐条比对复核**。
  **"不能重跑"与"已入档可核"是两件事，不要混成一句**：前者说的是本机能力，后者说的是凭证状态。

### 一、两遍结果（对外口径：超时/失败计为失败、**不缩分母**）

| 轮 | 顺序 | 类型准确率 | 优先级准确率 | 信息不足组保守率 | 三档计数（判错/超时未完成/分诊失败） | 失败清单 |
| --- | --- | --- | --- | --- | --- | --- |
| 第一遍 | 正向 | **14/14 = 100%** | **8/8 = 100%** | **6/6** | **0 / 0 / 0** | **0 条** |
| 第二遍 | `--reverse` | **14/14 = 100%** | **8/8 = 100%** | **6/6** | **0 / 0 / 0** | **0 条** |

- **分母说明**：类型分母 14 = 全部非信息不足用例（其中 O2/E2 已收窄为单值）；优先级分母 8 = 标注了期望值的用例；
  信息不足组分母固定 6（未判定/失败按"不保守"计）。
- **改前基线**（D65 §一，2026-09-25 真机）：类型 **12/14 = 85.7%**、保守 **4/6**；
  两轮的分母同为 14，但**期望口径不同**（改前 O2/E2 是双值数组，改后已收窄）——见下面的"口径三条"。

### 二、上一轮那两条超时的病根：**由账本坐实**

改前跑分里有两条被记为失败/未判定（E4、I6），当时的两种猜测是"超时机制"与"prompt 回归"。本轮在账本里查到：

| 事件 / 工单 | 账本证据 | 判读 |
| --- | --- | --- |
| `order:902`（对应 `v0:ORDER_TRIAGE`） | `attempt=5`，`last_error` = **`I/O error on POST …`** | 是**超时/IO 侧**的病根，不是 prompt 判错 |
| `order:888` | `attempt=2` → 状态 **`SUCCEEDED`** | 第一次失败后**由重试自愈**（"失败进账本 + 阶梯重投"这条兜底真的生效了） |

> **已确认（2026-09-26 01:42，`--default-character-set=utf8mb4` 查询）**：`order:888` = **I6**、`order:902` = **E4**。三条独立证据：\
> ① 顺序映射：行 B 的 id 清单首项是 889，第 14 条即 E4 → 902；行 A 是紧邻其前的 20 条（869–888），末项 = 888，
> 与"一轮 20 条、末项即末条用例（I6）"一致；\
> ② 标题核对：`888 = 空调`（2 字符，I6 用例原文）、`902 = 关于南区食堂二楼某处设施的问题反馈`（17 字符，E4 用例原文）；\
> ③ 时间吻合：902 的 `created_at = 2026-09-25 23:27:18`，正落在行 B 跑到第 14 条 E4 的窗口内（E4 耗掉 90s 等待上限）。\
> 结论：行 A 的 I6 与行 B 的 E4 两次"超时未完成"都是 **`TriageUnavailableException … I/O error on POST request`**（读超时），
> 与 D66 的归因（5s 门槛落在分布中部）一致，**不是 prompt 回归**。

### 三、口径三条（这份数字能说到什么程度）

1. **收窄依据**：O2（门禁）与 E2（宿舍 WiFi）从"双值数组"收窄为单值，依据是 D65 给 prompt 补的四类边界定义
   （"锁具（含门禁刷卡）→ DORM"、"网络/WiFi → NETWORK"）。**收窄本身不是为了让数字好看**，而是"边界写清后它们从真歧义变成按规范可判"。
2. **两遍一致 ≠ 泛化**：正/反向两遍各 20 条、共 40 次调用，覆盖的是**这 20 条用例**；
   它证明"结果稳定、不受顺序影响"，**不等于**"模型对所有工单都判得准"。要扩大结论只能扩用例集。
3. **E4 的余量薄，但兜底在账本**：改后最长一条 14.1s，距 15s 读超时只剩 **≈2s**。
   也就是说"长文本偶发超时"这件事**没有消失，只是从常态回到尾部**；真发生时由
   **重试账本（1m/5m/15m/1h/6h）+ 状态守卫**兜住（`order:888` 就是活例），不会产生错误结果。

### 四、正面反证：**短文本没有被保守规则误伤**

保守规则最容易的副作用是"把信息其实够、只是写得短的工单也判成 OTHER"。
本轮的两条典型短文本用例**都判对**：**N2**（"办公室网口坏了 / 工位网口插上没反应"→ NETWORK/普通，**优先级也判对**）、
**U2**（"卫生间水龙头漏水 / 一直滴水"→ UTILITY/普通）。
——"修好一类、坏了另一类"这件本轮特意要看的事，在这 20 条上没有发生。

### 五、清理与残留

- **本轮 40 id × 6 表全部归零**：按 `scripts/triage-eval.py` 打印的清理 SQL 执行
  （含新增的两行 `event_id REGEXP` 覆盖 `t_consume_record` / `t_message_retry`），删后各表计数为 0。
- **附录同时是"删除范围凭证"**：附录 A/B 自带 **40 个 id + 6 张表的清理 SQL**（脚本每次跑完自动打印），
  所以"删了哪些"有据可查——这补上了上一轮"删除前逐表计数没留成"的缺口。
  **但要如实写明**：**删前的逐表计数未留档**；本轮的判据是"**id 清单（附录）＋ 删后六表归零**"，
  **不是**"删前/删后逐表对照"。别把这一点说成"计数已留档"。
- **跑后总账**（服务器库整体计数，委托方提供，非仅本轮）：**`orders 542` / `retry_rows 2` / `outbox 283` / `consume_rows 282`**。
- 其中 **`retry_rows = 2` 正是 `888` 与 `902` 两行账本**——即上面"保留作自愈实证、不删"的那两条；
  它同时说明账本里**只剩这两条**（其余失败要么已收口、要么已被清理）。
- **历史残留（登记为 P7 收尾项，本轮不清）**：
  · 工单 id 区间 **849–868**、**889–908**（早期评测/验证留下的）；
  · **400 张压测单**（服务器批压测遗留）。
  · **例外：`888` 与 `902` 明确保留，不删**——它们是"失败 → 账本 → 重试自愈"的**实测凭证**，
    删掉就只剩文档里的一句话了。
  > **⚠ 措辞收紧（2026-09-27）**：上面这句对**两条**都叫"自愈实证"，但当时只有一条闭环——
  > **`888` 已闭环**（`attempt=2 → SUCCEEDED`，工单 `DONE / OTHER-0`）：它是**自愈实证**；
  > **`902` 尚未闭环**（PENDING / attempt=5，第 6 次重投排在 09-26 06:49），结局只有两种
  > （`SUCCEEDED` + 工单 `DONE`；或 `PARKED` + 工单 `FAILED`）——**在结果落地前不要把 `902` 也写成"自愈实证"**。
  > 取值 SQL 与登记表见 `deploy/CLEANUP-BEFORE-DEMO.md` §8。**两条账本都保留不删**这一点不变。
  > **✅ 升级（2026-09-27 晚，结果已落地）**：`902` 的结局是 **A 自愈成立**——
  > 账本 `SUCCEEDED / attempt=5`、工单 `triage_status=DONE`、`type=UTILITY`、`priority=1`
  > （原文见 `deploy/CLEANUP-BEFORE-DEMO.md` §9① 的"账本"行与 §8）。
  > 所以上面那句"**888 与 902 是实测凭证**"**现在成立**，但要说准成：
  > **两条独立实证**——`888`（2 字符，信息不足组）与 `902`（17 字符长文本，E4）**输入长度差一个数量级**，
  > 都走完了"读超时 → 落账本 → 阶梯重投 → 成功"同一路径；而 **`PARKED` 分支：有单测覆盖、真机未触发**
  > （`OrderTriageParkedMarksFailedTest` / `OrderTriageParkedAtomicityTest` 钉住"停车 → 工单置 FAILED → 同一事务"，
  > 但真机上 `attempt=5` 的第 6 次重投即成功，从没走到过 PARKED）——所以"超上限停车 + 人工重放"的**正确性有测试、
  > 真机路径未走**，重放 SQL 仍只在文档里。
  > **两条账本继续保留不删**（同 D68 的边界）。
  > **`888`（I6）已闭环**：`attempt=2 → SUCCEEDED`，工单 `DONE / OTHER/0`（信息不足组的保守结论）——
  > **"5s 读超时 → 阶梯重投 → 自愈"的实证**。\
  > **`902`（E4）当时状态（查询时刻 2026-09-26 01:42:28）**：`status=PENDING`、`attempt=5`、
  > `next_retry_at=2026-09-26 06:49:04`、`last_error=TriageUnavailableException: LLM triage … I/O error on POST request
  > for "https://…"`（读超时，非模型判错）。第 6 次重投尚未执行；**`SUCCEEDED` 与 `PARKED` 对"兜底是否生效"
  > 的结论方向相反**，故按复查时刻的实际结果补记。两条账本**保留不删**（作自愈/停车实证）。

### 附录说明（两段原始输出的共同背景）

1. **容器内 `mysql` 客户端默认 latin1**：查**中文列**必须加 `--default-character-set=utf8mb4`，
   否则中文显示成 `?`——上一轮那句 `?????????????????` 就是这么来的，**不是数据损坏、也不是字符集坏掉**。
   （推论：凡是把库里的中文粘进文档，都要先确认这条；否则会把"显示问题"当成"数据问题"去排查。）
2. `===== eval-passN.txt =====` 是 `echo` 打出来的**分隔行，不属于文件内容**——附录**不补造**它，
   文件身份由每段开头的"来源"行承担。
3. 粘贴要求：**整段照录**，不重排、不删行、不改标点（它是"经审视的准确率"的唯一原始凭证）。

### 附录 A · `~/eval-pass1.txt` 原始输出

**来源**：服务器 `/root/eval-pass1.txt`，经 `scp` 落到本机 `loadtest-out/eval-pass1.txt`
（gitignored，**插完即删**）；本附录由该文件**逐行照录**，已核对 **bytes=3310 / 41 行 / LF / 无 CR / UTF-8**。

**已核对（不是防御性说明）**：**文件首行就是 `登录成功；用例 20 条（正向顺序）；等待上限 150s/条`**——
也就是说文件里**没有** `===== eval-pass1.txt =====` 这一行：那是用户 `echo` 打出来的**分隔行、不属于文件内容**，
因此本附录**不补造**它（若将来有人看到"少了一行"，看这里）。

```
登录成功；用例 20 条（正向顺序）；等待上限 150s/条

用例 结果       实得 type/priority     评判                           耗时
------------------------------------------------------------------------------
N1 判定       NETWORK/1            类型[OK] 优先级[OK]               7.2s
N2 判定       NETWORK/0            类型[OK] 优先级[OK]               4.1s
N3 判定       NETWORK/1            类型[OK] 优先级[OK]               5.1s
U1 判定       UTILITY/1            类型[OK] 优先级[OK]               5.1s
U2 判定       UTILITY/0            类型[OK] 优先级[OK]               5.1s
U3 判定       UTILITY/1            类型[OK] 优先级[OK]               5.1s
D1 判定       DORM/0               类型[OK] 优先级[OK]               5.1s
D2 判定       DORM/0               类型[OK]                       5.1s
O1 判定       OTHER/0              类型[OK]                       5.1s
O2 判定       DORM/0               类型[OK]                       5.1s
E1 判定       UTILITY/1            类型[OK] 优先级[OK]               5.1s
E2 判定       NETWORK/0            类型[OK]                       8.1s
E3 判定       NETWORK/0            类型[OK]                       7.1s
E4 判定       UTILITY/1            类型[OK]                       14.1s
I1 信息不足     OTHER/0              保守                           5.0s
I2 信息不足     OTHER/0              保守                           5.0s
I3 信息不足     OTHER/0              保守                           5.0s
I4 信息不足     OTHER/0              保守                           5.0s
I5 信息不足     OTHER/0              保守                           5.0s
I6 信息不足     OTHER/0              保守                           6.0s

== 汇总 ==
类型准确率（**对外口径**：超时/失败计入分母，不缩分母；不含信息不足组）：14/14 = 100.0%
优先级准确率（仅标注了期望值的那几条）：8/8 = 100.0%
信息不足组的保守率：6/6（未判定/失败按“不保守”计，分母保持整组）
三档计数：判错 0 条 / 超时未完成 0 条 / 分诊失败 0 条
失败清单：0 条

== 本次产生的工单（跑完请清理，切勿对着业务库跑）==
id 清单：949,950,951,952,953,954,955,956,957,958,959,960,961,962,963,964,965,966,967,968
清理 SQL（**覆盖所有按 event_id 存的表**，见文件头；先删子表再删主表）：
  DELETE FROM t_consume_record WHERE event_id REGEXP '^order:(949,950,951,952,953,954,955,956,957,958,959,960,961,962,963,964,965,966,967,968):';
  DELETE FROM t_message_retry  WHERE event_id REGEXP '^order:(949,950,951,952,953,954,955,956,957,958,959,960,961,962,963,964,965,966,967,968):';
  DELETE FROM t_event_outbox  WHERE aggregate_id IN (949,950,951,952,953,954,955,956,957,958,959,960,961,962,963,964,965,966,967,968);
  DELETE FROM t_work_order_log WHERE order_id    IN (949,950,951,952,953,954,955,956,957,958,959,960,961,962,963,964,965,966,967,968);
  DELETE FROM t_notification   WHERE ref_id      IN (949,950,951,952,953,954,955,956,957,958,959,960,961,962,963,964,965,966,967,968);
  DELETE FROM t_work_order     WHERE id          IN (949,950,951,952,953,954,955,956,957,958,959,960,961,962,963,964,965,966,967,968);

> **摘要（与附录 A 逐条核对一致）**：类型 14/14、优先级 8/8、信息不足组保守率 6/6、
> 三档计数 0/0/0、失败清单 0 条，总耗时 4.0–14.1s（最长 E4 = 14.1s）。

### 附录 B · `~/eval-pass2.txt`（`--reverse`）原始输出

**来源**：服务器 `/root/eval-pass2.txt`，经 `scp` 落到本机 `loadtest-out/eval-pass2.txt`
（gitignored，**插完即删**）；本附录由该文件**逐行照录**，已核对 **bytes=3311 / 41 行 / LF / 无 CR / UTF-8**。

**已核对**：**文件首行就是 `登录成功；用例 20 条（反向顺序）；等待上限 150s/条`**，同样没有
`===== eval-pass2.txt =====` 那行（`echo` 的分隔行，不补造）。

```
登录成功；用例 20 条（反向顺序）；等待上限 150s/条

用例 结果       实得 type/priority     评判                           耗时
------------------------------------------------------------------------------
I6 信息不足     OTHER/0              保守                           5.0s
I5 信息不足     OTHER/0              保守                           4.0s
I4 信息不足     OTHER/0              保守                           5.0s
I3 信息不足     OTHER/0              保守                           5.0s
I2 信息不足     OTHER/0              保守                           5.0s
I1 信息不足     OTHER/0              保守                           5.0s
E4 判定       UTILITY/1            类型[OK]                       10.1s
E3 判定       NETWORK/0            类型[OK]                       6.0s
E2 判定       NETWORK/1            类型[OK]                       10.1s
E1 判定       UTILITY/1            类型[OK] 优先级[OK]               4.0s
O2 判定       DORM/0               类型[OK]                       5.0s
O1 判定       OTHER/0              类型[OK]                       4.0s
D2 判定       DORM/0               类型[OK]                       6.0s
D1 判定       DORM/0               类型[OK] 优先级[OK]               4.0s
U3 判定       UTILITY/1            类型[OK] 优先级[OK]               6.0s
U2 判定       UTILITY/0            类型[OK] 优先级[OK]               5.0s
U1 判定       UTILITY/1            类型[OK] 优先级[OK]               4.0s
N3 判定       NETWORK/1            类型[OK] 优先级[OK]               5.0s
N2 判定       NETWORK/0            类型[OK] 优先级[OK]               5.0s
N1 判定       NETWORK/1            类型[OK] 优先级[OK]               6.0s

== 汇总 ==
类型准确率（**对外口径**：超时/失败计入分母，不缩分母；不含信息不足组）：14/14 = 100.0%
优先级准确率（仅标注了期望值的那几条）：8/8 = 100.0%
信息不足组的保守率：6/6（未判定/失败按“不保守”计，分母保持整组）
三档计数：判错 0 条 / 超时未完成 0 条 / 分诊失败 0 条
失败清单：0 条

== 本次产生的工单（跑完请清理，切勿对着业务库跑）==
id 清单：969,970,971,972,973,974,975,976,977,978,979,980,981,982,983,984,985,986,987,988
清理 SQL（**覆盖所有按 event_id 存的表**，见文件头；先删子表再删主表）：
  DELETE FROM t_consume_record WHERE event_id REGEXP '^order:(969,970,971,972,973,974,975,976,977,978,979,980,981,982,983,984,985,986,987,988):';
  DELETE FROM t_message_retry  WHERE event_id REGEXP '^order:(969,970,971,972,973,974,975,976,977,978,979,980,981,982,983,984,985,986,987,988):';
  DELETE FROM t_event_outbox  WHERE aggregate_id IN (969,970,971,972,973,974,975,976,977,978,979,980,981,982,983,984,985,986,987,988);
  DELETE FROM t_work_order_log WHERE order_id    IN (969,970,971,972,973,974,975,976,977,978,979,980,981,982,983,984,985,986,987,988);
  DELETE FROM t_notification   WHERE ref_id      IN (969,970,971,972,973,974,975,976,977,978,979,980,981,982,983,984,985,986,987,988);
  DELETE FROM t_work_order     WHERE id          IN (969,970,971,972,973,974,975,976,977,978,979,980,981,982,983,984,985,986,987,988);

> **摘要（与附录 B 逐条核对一致）**：与第一遍完全一致——类型 14/14、优先级 8/8、保守 6/6、失败清单 0 条。

- **关联文档**：`scripts/triage-eval-cases.json`（用例与收窄口径）、`scripts/triage-eval.py`（三档计数 + `--reverse`）、
  `README.md` §6.4、D65（prompt 两处改动与改前基线）、D66（timeout 15s 与等待上限 150s）、D67（LLM 移出事务）

## D69 · 双跑幂等：受控叠窗实验（本地 `@Scheduled` 与调度中心 `@XxlJob` 同时抢同一张单）

- **日期**：2026-09-26
- **前置提交**：`fc0d956`（两个 handler 落地、本地兜底保留）、`6776427`（控制台配置表 §9）
- **问题**：P2 让两条触发路径**并行**（进程内兜底 + 调度中心）。方案 §P2 写的是"靠乐观锁/SETNX 吸收重复"，
  但在此之前**只有"0 候选"的并行日志**——那只能证明两路都在跑，**证明不了"业务效果只发生一次"**。
- **选择**：在真库（临时库 `wo_p2_2b2`）上做一次受控双跑；**把"自然时序下没撞上守卫"的失败段一并留档**，
  再用行锁人为叠窗取得决定性证据。
- **本条还覆盖（2026-09-27 真机补充）**：双跑并行的两条**额外代价**——兜底会**掩盖**主路径错配、
  以及 `job handler [x] not found` 的**两成因**（见文末两节）。三件事同属"两路并行"这一个决策的账。

### 判据（两层缺一条都不能下结论）

| 层 | 证据 | 单靠它缺什么 |
| --- | --- | --- |
| 机制 | `information_schema.innodb_trx` 里**两条 `LOCK WAIT`**（同一行、同一秒） | 只说明"两路都到了带守卫的 UPDATE"，不说明结果对不对 |
| 业务 | `t_work_order.status/version`、`t_work_order_log` 行数、`t_notification` 行数 | 只说明结果对，**不能**说明真的并发过（可能只是先后串行） |

环境（四段共用）：临时库 `wo_p2_2b2` 导 `sql/init.sql`；后端 `DB_NAME=wo_p2_2b2`、`XXL_JOB_EXECUTOR_ENABLED=true`、
`OUTBOX_DISPATCH_ENABLED=false`；`xxl.job.executor.logpath` 指到工作区内；`logging.level.com.workorder.scheduler=debug`
（守卫/幂等跳过的日志本来就是 DEBUG）。本机 admin **没有**启动，`/run` 直接打执行器 9999（返回 `{"code":200}` 只当"触发被受理"）。

### A 段 · 单路 sanity：local 兜底确实能释放

造 1 条逾期未接单（id=1，NETWORK/0，`updated_at` 1 天前，`accept_minutes=30`）：

```
15:05:44.179 [scheduling-1] [release-scan] 触发来源=local 开始扫描（单轮上限 200）
15:05:44.241 [scheduling-1] 超时释放成功: orderId=1, orderNo=WO-P2B2-A-LOCAL
15:05:44.244 [scheduling-1] [release-scan] 触发来源=local 本轮释放 1 条（候选 1 跳过 0 出错 0 缺配置 0）
```

DB：`status=RELEASED`、`version=1`、`assignee_id=NULL`；`t_work_order_log` **恰 1 行**
（`RELEASE: ACCEPTED->RELEASED`，`operator_id=0`＝系统，`remark=系统超时自动释放`）。

### B 段 · 自然时序：xxl 早 0.22s，local 只看到"候选 0"——**没撞上守卫**（如实记录）

再造 1 条同样形态的单（id=2），在本地节拍前 0.45s 起连打执行器 9999 的 `/run`（26 次，全部 `{"code":200}`）：

```
15:06:43.923 [Thread-7]     [release-scan] 触发来源=xxl 开始扫描（单轮上限 200）
15:06:43.954 [Thread-7]     超时释放成功: orderId=2, orderNo=WO-P2B2-B-DOUBLE
15:06:43.957 [Thread-7]     [release-scan] 触发来源=xxl 本轮释放 1 条（候选 1 跳过 0 出错 0 缺配置 0）
15:06:44.175 [scheduling-1] [release-scan] 触发来源=local 开始扫描（单轮上限 200）
15:06:44.186 [scheduling-1] [release-scan] 触发来源=local 本轮释放 0 条（候选 0 跳过 0 出错 0 缺配置 0）
```

**结论只到这里**：两路都在跑（"两路来源都在日志里"这条判据满足）、效果只发生一次；
但 xxl 在 local 起跑前就提交完了，**local 的候选查询已经查不到它** → 这一段**没有**经过状态守卫，
**不能**用它宣称"守卫吸收得了并发"。守卫的证据只能看 C 段。

### C 段 · 受控叠窗（决定性证据）

造 1 条（id=4）。本地节拍前 2s 用**独立会话** `SELECT ... FOR UPDATE` 锁住该行、持锁 9s；节拍时刻再打一次 `/run`。
持锁期间：

```
=== innodb_trx BEFORE tick (15:09:43.501) —— 只有持锁会话 ===
1801167  RUNNING    1
=== innodb_trx DURING block (15:09:45.179) ===
1801167  RUNNING    1   2026-09-26 15:09:42   ← 持锁会话
1801169  LOCK WAIT  1   2026-09-26 15:09:44   ← xxl 那一轮
1801168  LOCK WAIT  1   2026-09-26 15:09:44   ← local 那一轮
```

两条 `LOCK WAIT` 说明**两路都把同一张单当候选、都走到了带守卫的 `UPDATE`**。放锁之后：

```
15:09:51.304 [scheduling-1] 超时释放成功: orderId=4, orderNo=WO-P2B2-D-RACE2
15:09:51.309 [scheduling-1] [release-scan] 触发来源=local 本轮释放 1 条（候选 1 跳过 0 出错 0 缺配置 0）
15:09:51.312 [Thread-35]    DEBUG 超时释放跳过（状态守卫未命中，工单状态已变）: orderId=4, orderNo=WO-P2B2-D-RACE2
15:09:51.316 [Thread-35]    [release-scan] 触发来源=xxl 本轮释放 0 条（候选 1 跳过 1 出错 0 缺配置 0）
```

DB（A/B/C 三段的 4 张单）：**每张恰好 1 行 `RELEASE` 日志、`version=1`、`status=RELEASED`**。

**边界（必须一起读）**：这是**人为叠窗**，它证明的是"**两路同时持有同一候选时，状态守卫吸收得住重复**"；
**不代表生产里两路必然同时到达**——B 段就是反例。生产里的重叠概率取决于 admin 周期与兜底节拍的重合度。

### D 段 · SLA 侧：两路同窗口只多 1 条站内信

造 1 条已过 `sla_deadline` 且未完结的单（id=5），等本地 300s 节拍，在节拍窗口同时打 xxl：

```
15:14:44.201 [scheduling-1] [sla-scan] 触发来源=local 开始扫描（单轮上限 200）
15:14:44.212 [scheduling-1] SLA扫描: 发现1条超时工单
15:14:44.415 [Thread-37]    [sla-scan] 触发来源=xxl 开始扫描（单轮上限 200）
15:14:44.425 [Thread-37]    SLA扫描: 发现1条超时工单
15:14:44.950 [Thread-37]    DEBUG SLA通知已发送过，跳过重复通知: orderId=5
15:14:44.950 [Thread-37]    [sla-scan] 触发来源=xxl 本轮通知 0 条（候选 1 跳过 1 失败 0）
15:14:45.020 [scheduling-1] [sla-scan] 触发来源=local 本轮通知 1 条（候选 1 跳过 0 失败 0）
15:14:48.495 [Thread-38]    [sla-scan] 触发来源=xxl 开始扫描；跳过重复通知: orderId=5；本轮通知 0 条（候选 1 跳过 1 失败 0）
```

DB：`t_notification` 全程**只有 1 行**（`user_id=1`，标题 `工单 WO-P2B2-E-SLA SLA 超时`；`ref_type/ref_id/event_id` 均为 NULL）。

- **自查（措辞）**：预期文案是"已有告警记录（24h 内）"，**代码实际是** `SLA通知已发送过，跳过重复通知`
  （DEBUG；键 `sla_notified:5`，`TTL=86352s≈24h`）。**语义相同、措辞不同——没有改代码去迎合措辞。**
- 本机跑的是 `MockMessagePublishServiceImpl`（`t_event_outbox` 全程 0 行），所以 SLA 侧的落点在 `t_notification`，**不涉及 broker**。

### 清理（照 D19：先按最宽口径统计，再删）

```
DROP 前逐表：t_work_order=5  t_work_order_log=4  t_notification=1  t_event_outbox=0
             t_consume_record=0  t_message_retry=0  t_user=1  t_role=4  t_sla_config=8
两个口径：log_by_order_no_like_WO_P2B2=4   与   log_by_order_id_join=4   （一致，无孤儿）
DROP DATABASE wo_p2_2b2 → 只剩 work_order / work_order_test / xxl_job
后端进程停止（9000/9999 均不通）；Redis 只删本次建的 sla_notified:5（其余键全是既有登录态与 order:seq:*）；loadtest-out/ 已删
```

### 代价与未覆盖（别把它读成"双跑已全面验证"）

1. **B 段暴露的事实**：自然时序下两路**不一定**同时到达，重复触发常常被"候选已经变了"自然吸收；
   所以"守卫"是兜底，不是常态路径。真实重合度要在真机 admin 建任务后测（属 P7 的组合故障演练）。
2. 本轮只覆盖两种守卫：release 的**状态守卫**、SLA 的 **Redis SETNX**。
   **没有**覆盖 outbox 的抢先 UPDATE 与 triage 的消费去重（它们各自有独立测试，见 D49/D67）。
3. 行锁实验是**人为加宽窗口**，不是自然竞态；它给出的判据是"吸收得住重复"，不是"重叠概率是多少"。

- **关联文档**：`deploy/UPGRADE-P2.md` §9（控制台字段与四项危险区配置）、`ASYNC-SCHEDULING-PLAN.md` §P2、
  D46/D62（兜底扫描的权威通道地位）、提交 `fc0d956` 与 `6776427`

### 双跑并行的额外代价（2026-09-27 真机）：兜底会**掩盖**主路径的错配

方案 §P2 的风险点原估是"两套调度并行会产生重复触发（靠状态守卫吸收，**代价是日志噪音**）"。
真机实测说明**代价不止日志噪音**：

- 把 SLA 任务的 `executor_handler` 填成 `releaseTimeoutScan`（成因见下一节）之后：**触发照样返回 200、后端零异常、业务零告警**——
  被触发的是 release 扫描，它按自己的语义正常跑完；而 SLA 那条链路的真实状态是"**调度中心这边一次都没触发过**"。
- **能发现它的只有两条路**：① 读回 `xxl_job_info.executor_handler` 与注解值逐字对照；
  ② 按**来源标记**分开的日志——`[sla-scan] 触发来源=xxl …` **始终不出现**，而 `[sla-scan] 触发来源=local …` 照常出现。
  这正是"来源标记不是装饰"的第二个用途（第一个用途是证明双跑）。
- **危险恰恰在于"结果是对的"**：本地 `@Scheduled` 兜底会把业务做对（SLA 照样告警、超时照样释放），
  于是错配在业务侧**完全看不见**。它只在两处咬人：**频率退化**（兜底 300s/60s，调度中心本可以更密或错峰）
  与**多实例语义**（本该"一个实例串行跑"的任务，实际变成"每个实例各自兜底跑"）。
- **换个场景就是事故**：一旦真把 `@Scheduled` 下线（P2 那处"可靠性净倒退"的彻底版），
  "配置错配 + 兜底掩盖"的组合会直接变成**功能静默失效**。所以 plan §P2 把
  "**停 admin 时 `触发来源=local` 必须仍出现**"定为完成判据之一。

### 附：`job handler [x] not found` 的两成因（本轮两次都踩到）

| 成因 | 判据（怎么分辨） | 修法 |
| --- | --- | --- |
| **代码没部署**（执行器里根本没有这个 handler） | 执行器启动日志**没有** `xxl-job register jobhandler success, name:…` 那两行 | 重新部署后端，再查那两行 |
| **任务配置的 handler 名写错**（含"张冠李戴"：把 `releaseTimeoutScan` 填到 SLA 任务上） | 启动日志**有**两行注册；读回 `executor_handler` 与注解值**逐字**不一致 | 改成注解值（唯一真源：`ReleaseTimeoutScheduler:80` / `SlaEscalationScheduler:93`） |

**同一个 `code:500, msg: job handler [x] not found` 文本对应两种病根**，所以顺序被固定为：
**① 先部署 → ② 查两行注册日志 → ③ 再建任务 → ④ 读回 `executor_handler` → ⑤ 点一次执行看 `xxl_job_log` 的 `handle_msg`**。
跳过 ② 会让"漏部署"伪装成"配置写错"，跳过 ④ 会让"配置写错"伪装成"环境/网络问题"。
本轮**先踩前者（漏部署）、补部署后又踩后者（handler 张冠李戴）——两次的报错长得一模一样**。

> **✅ 留痕状态（2026-09-27 收口，不再列为本条目的待补项）**：**日志侧凭证已入档**——D72 附录 B 段就是这条实验的原始记录
> （handler 改成 `archiveJobTYPO` 后：`handle_code=0`、`alarm_status=2`、`handle_msg=NULL`，且 `trigger_status` 始终为 1）。
> `/run` 接口那侧的 HTTP 响应原文（`code:500, msg: job handler [x] not found`）**由附录 B 的日志侧等效覆盖**：
> 两者说的是同一件事的两种视角（接口侧返回 `ReturnT.FAIL_CODE` 及其 msg；执行器侧记 `handle_code=0` + `handle_msg=NULL`），
> **而运维判据只看后者**（`xxl_job_log`）——所以接口响应原文不再是缺口，**不再作为待补项**。
> 判读口径与固定动作写进 `deploy/UPGRADE-P2.md` §9.1（两成因表）与 `deploy/DEPLOY-RUNBOOK.md` 的"常见失败"表。

## D70 · P6 归档删除：**不引入 id 水位**（反转 §5.6 的"水位线 + 区间均分"）+ 白名单 + 分片不改变删除范围

- **日期**：2026-09-27　**范围**：P6 步骤 1（按保留期分批删除三张表），不含归档搬运与报表
- **问题**：需要按期清掉三张"只增不减"的表（`t_consume_record` 30 天 / `t_message_retry` 30 天 /
  `t_event_outbox` 的 **SENT** 行 7 天）。方案 §5.6 原本规定用"**水位线 + 区间均分**"做分片，
  理由是"扩容时 `id % shardTotal` 会让同一行落到不同分片 → 重复归档或永久遗漏"。

### 一、改成什么（三个取舍）

| 取舍 | 选择 | 理由 |
| --- | --- | --- |
| 分片方式 | **`MOD(id, shardTotal) = shardIndex` 叠加在时间谓词上**（每轮重新筛）；**不用水位线** | 见下"二、为什么水位线在删除语义下反而更危险" |
| 删除范围 | **白名单**（`ArchiveTarget` 枚举：三张表 / outbox 仅 SENT 行） | 参数写错表名在**解析阶段**就失败；业务表根本不在枚举里，删不到 |
| 安全边界 | 每批一条 `DELETE`（**一批一事务**，自动提交）、`LIMIT batchSize`、预算 `maxBatches` 用满即正常返回 | 没有水位线 → 没有"记了没删 / 删了没记"的中间态，**随时可中断** |

### 二、为什么"水位线 + 区间均分"在删除语义下反而更危险（反转留痕）

- **原以为**（§5.6）：没有水位线就会漏删；取模分片在扩容时会错乱。
- **后来发现**：这两条对**删除**都不成立——
  ① 删除的谓词是**状态驱动**的：`锚点列 < cutoff`。每个分片**每一轮都重新筛**自己那一份，
     **这一轮没删到的行，下一轮照样会被筛到**（数据自己就是状态），所以"扩容改落点"不会造成永久遗漏；
  ② 反而是**水位线会制造漏删**：一旦某轮"水位推进了、但删除失败或被中断"，那段区间**永久不会被再筛**，
     而且**没有任何地方会报错**（正是本项目最贵的"静默失效"）。
  ③ §5.6 那套论证的真正适用场景是**归档搬运**（读一次、写一次，必须区间不重不漏）——本步不做搬运，
     所以在 plan §5.1/§5.6 都加了限定注，**原文保留**。
- **代价（如实写）**：`MOD` 用不上索引，单分片要扫过约 `shardTotal` 倍于它删除行数的候选行；
  换来的是"不重不漏 + 随时中断 + 没有中间态"。删除是 IO 密集任务，按 §2 的调度建议它在凌晨低峰跑。

### 三、本机实测（专用库 `wo_p6a`，一次性，跑完已 DROP，按 D19 留了删除前计数）

**① 功能与保留期**（`sql/init.sql` + `sql/hotfix-p6-archive.sql`；参数 `tables=consume_record,message_retry,outbox_sent;retentionDays=30;batchSize=1000;maxBatches=20`）：

```
[archive] consume_record 第 1/2/3 批各删除 1000 行；第 4 批删除 0 行
[archive] message_retry  第 1 批删除 500 行
[archive] outbox_sent    第 1 批删除 100 行
[archive] shard=0/1 cutoff=2026-08-28T00:49:00 本轮删除 3600 行（consume_record=3000, message_retry=500, outbox_sent=100）；已删完（本轮无剩余）
handleCode=200, handleMsg = 同上摘要
```

DB 事实：40 天前的行**全部归零**；30 天内的行**一行未动**（consume 100 / retry 50 / outbox SENT 30）；
**outbox 的 50 行 PENDING 一行未动**（`status='SENT'` 守卫生效）。`t_archive_log` 三行，`outcome=DONE`。

**② 预算生效（不是失败）**：造 3000 行过期数据 → `maxBatches=1`：

```
[archive] consume_record 第 1 批删除 1000 行
[archive] shard=0/1 本轮删除 1000 行（consume_record=1000(未完)）；预算用完，未完下一轮继续
handleCode=200（**不是** handleFail）；t_archive_log.outcome=BUDGET_EXHAUSTED
```

**③ 幂等重跑**：接着跑（`maxBatches=20`）→ 删除 2000 行；**再跑一轮 → `本轮删除 0 行`，`deleted_rows=0`**。

**④ 分片不重不漏**：

```
单分片全量：3000 行 → 本轮删除 3000 行
三分片    ：shard 0/3 → 1000；shard 1/3 → 1000；shard 2/3 → 1000；合计 3000
剩余过期行：0
```

`t_archive_log` 对应行：`(shard_index, shard_total, deleted_rows)` = `(0,3,1000) (1,3,1000) (2,3,1000)`，与基线 3000 一一对上。

### 四、方法学发现：**"加了索引"不等于"EXPLAIN 走索引"——判据要带口径**（本步最容易误判的地方）

三条 DELETE 的谓词与索引：

| 表 | 谓词 | 交付的索引 | 稳态形状下的 EXPLAIN |
| --- | --- | --- | --- |
| `t_consume_record` | `consumed_at < cutoff AND MOD(id,k)=j` | 原有 `idx_consumed_at` | `type=range, key=idx_consumed_at` |
| `t_message_retry` | `created_at < cutoff AND MOD(id,k)=j` | **新增** `idx_created_at` | `type=range, key=idx_created_at` |
| `t_event_outbox` | `status='SENT' AND sent_at < cutoff AND MOD(id,k)=j` | **新增** `idx_status_sent_at` | `type=range, key=idx_status_sent_at, ref=const,const` |

**但同一批索引在另外两种数据形状下会给出"看起来失败"的结果**（都实测过）：

1. **几百行的小表 / 空表**：`t_message_retry`（550 行）与 `t_event_outbox`（180 行）上 EXPLAIN 给
   `type=ALL, possible_keys=idx_..., key=NULL` —— **优化器认为全表扫更便宜，不是索引没建上**。
2. **"几乎全过期"的形状**：把 outbox 造成 2 万行里 2 万行都过期时，优化器**放着新索引不用**，
   选了旧的 `idx_dispatch` 的 `status` 前缀（`key_len=66`）——因为 `status` 只有两个取值，
   估算退化成一刀切 50%，两条路径代价同档，它挑了已有的那条。
   改造成**稳态形状**（2 万行里约 10% 过期）后，它选中 `idx_status_sent_at`（`key_len=72, ref=const,const`）。
3. 顺带实测：`EXPLAIN DELETE ... FORCE INDEX (...)` 在 MySQL 8 里**是语法错误**，DELETE 不能靠 hint 自证，
   只能用"造对形状"的方式验证。

> **所以"确认走索引"这条判据必须写成**：在**有代表性行数 + 稳态分布**的表上跑 EXPLAIN，认 `key=` 那一列。
> 这与 D24/D66 的"口径"教训同族：**判据本身要带前提，否则会把优化器的正常选择读成缺陷。**

### 五、清理

```
DROP 前计数（唯一出处，跑完即 DROP DATABASE wo_p6a）：
  t_consume_record=100  t_message_retry=18050  t_event_outbox=18080（其中 PENDING=50）  t_archive_log=12  t_job_watermark=0
进程已停（9000/9999 均不通）；loadtest-out/ 已删；测试期间未启动任何容器
```

- **关联**：`sql/hotfix-p6-archive.sql`（DDL 唯一出处）、`deploy/UPGRADE-P6.md`（服务器操作清单）、
  `ASYNC-SCHEDULING-PLAN.md` §5.1/§5.6（反转注）、`docs/PENDING-RESTORE.md`（本步未改任何应然值，无需登记）

### 六、补记（2026-09-27 同日晚）：保留期下限改成 **per-table**（原先是一个全局值）

**改了什么**：`retentionDays` 的下限从"全局 7 天"改成"**所列表里最严的那个下限**"——
`ArchiveTarget` 上每张表带自己的下限：`t_consume_record` **30**、`t_message_retry` **30**、
`t_event_outbox` 的 SENT 行 **7**；`ArchiveParams.effectiveMinRetentionDays(targets)` 取最大值。

**为什么不是全局一个数**：每张表的保留期口径本来就不同（消费去重/重试账本是 30 天、outbox SENT 是 7 天），
而"这一轮动哪些表"是参数决定的。取最大值 = **只要表在列表里，就不许用比它自己口径更短的保留期去删它**。

**行为变化（要记住的一条）**：`tables=consume_record;retentionDays=29` 现在**会被拒绝**（此前 1 天也放行）。
下限等于口径本身，因为删除不可逆、短于口径删掉的就不是"过期的"而是"还在窗口里的"。
把 `outbox_sent` 与 `consume_record` 放同一轮时下限被抬到 30（outbox 也按 30 天删）——**只会更保守**；
要按 7 天清 outbox 就单独跑 `tables=outbox_sent;retentionDays=7`。
测试库要造"过期"数据请**手工把时间戳挪到过去**，不要靠调小这个参数。

**落点**：`ArchiveTarget.minRetentionDays()`（每表定义）、`ArchiveParams.effectiveMinRetentionDays`（校验）、
`deploy/UPGRADE-P6.md` §2.1（运维说明）、`ArchiveParamsTest`（含 29/6/7/30 四个边界与"多表取最严"）。

**它在运维上的直接后果（2026-09-27 服务器已验证，见 D72 附录 ①）**：下限按表算 ⇒ **一条参数不可能同时
满足 30 与 7** ⇒ 服务器上 `archiveJob` **被建成了两个任务**：
`归档（库表）` 03:30 跑 `tables=consume_record,message_retry;retentionDays=30`、
`归档（outbox）` 03:45 跑 `tables=outbox_sent;retentionDays=7`（错开 15 分钟，避免两轮同时吃 IO）。
两者的 `cutoff` 在 `t_archive_log` 里能直接看出来：库表那两行是 `2026-08-28`（30 天前）、
outbox 那两行是 `2026-09-20`（7 天前）——**这就是"下限按表"在生产里的样子**。

## D71 · P6 日报汇总：**今天不算** + **水位与结果同事务** + 口径写在 SQL 注释里（附"同事务"的注入实证）

- **日期**：2026-09-27　**范围**：P6 步骤 2（`@XxlJob("dailyReportJob")`），不含分片（步骤 3）
- **问题**：需要一张"每个自然日一行"的日报给业务/面试看，且必须满足三件事：
  数字**可复核**（口径写得出来）、重跑**不改数**（幂等）、崩了能**自愈**（不会永久缺一天）。

### 一、四条取舍

| 取舍 | 选择 | 理由 |
| --- | --- | --- |
| 算到哪天 | **只算到昨天**（今天一律不算） | 今天的数据还在写，现在算出来的数字下一分钟就变（口径漂移）；而且水位一旦推到今天，"明天再算今天"这条路就被堵死了。**补数模式同样拒绝含今天的区间** |
| 水位与结果 | **同一个事务**（`DailyReportWriter`，`REQUIRES_NEW`，**先写结果、后推水位**） | 反过来（先推水位再算）崩在中间 = 那天**永久漏算**且没人会再算它；先算再推 → 崩了整笔回滚，下一轮重算一遍（幂等，安全） |
| 事务粒度 | **一天一个事务**（独立 bean 的 `REQUIRES_NEW`） | 一天的失败不影响前面已提交的天；水位停在最后成功的那天。类内自调用不走代理，所以必须是独立 bean（同 P4 `MessageRetryService` 的理由） |
| 补数 | `from`/`to` 照算照写，但**不动水位** | 补数会往回算或往前跳，两种都会破坏水位的单调性 |

### 二、指标口径（写进 `sql/hotfix-p6-report.sql` 表注释 + `mapper/DailyReportMapper.xml` 的注释，两处成对维护）

| 列 | 口径 |
| --- | --- |
| `created_count` | 当日新增，按 `t_work_order.created_at` 归属 |
| `completed_count` | 当日完结，按 `t_work_order_log(new_status='COMPLETED').created_at` 归属（工单表没有 `completed_at`） |
| `avg_accept_minutes` | `AVG(接单事件时刻 − 创建时刻)`，**分母 = 当日发生接单事件的单数**；当日无接单事件 = `NULL`（不是 0，0 会被读成"秒接"） |
| `avg_finish_minutes` | 同上，分母 = 当日完结的单数 |
| `overdue_count` | **时点口径**：该日 23:59:59 结束时已过 `sla_deadline` 且仍未完结的单数。⚠ **同一张长期逾期单会在多天各计一次**——这是每日快照，不是"当日新增逾期"（本机实测里单 2 就在 9-24/9-25/9-26 各计了一次） |
| `triage_done_count` / `triage_failed_count` | 当日新增中 `triage_status` 为 `DONE`/`FAILED` 的数量。**按创建日归属**——表里没有分诊完成时刻，只有状态值 |

> **`overdue_count` 时点口径的例证（2026-09-27 服务器实测，附原始值）**：整治后库里只剩 `888`/`902` 两张单，
> 都在 **09-25** 创建（`23:23:24` / `23:27:06`），而它们的 `sla_deadline` **都落在 09-26**
> （`2026-09-26 07:23:24` / `2026-09-26 00:27:06`）。所以重算后的日报把 `overdue=2` 记在 **09-26** 而不是 09-25：
> 09-25 结束时它们还没到期（`overdue=0`）、到 09-26 结束时才逾期（`overdue=2`）。
> **同一个时点口径，两天两个数都对——这不是推理，是上面两个 `sla_deadline` 直接印证出来的**
> （原文见 `deploy/CLEANUP-BEFORE-DEMO.md` §9.3.1）。

**时区口径**：所有日界由应用侧按 `LocalDate`(+08) 算好，以 `[dayStart, dayEnd)` 传给 SQL；
**SQL 里不出现 `CURDATE()/NOW()` 当日界**——"哪一天"只能有一个来源（应用侧那一天），否则 UTC 与 +08 会混进来。
（本机实测：CLI 会话 `@@session.time_zone=SYSTEM`、`NOW()=2026-09-27 01:05`、`UTC_TIMESTAMP()=2026-09-26 17:05`，即 +08，与应用侧一致。）

### 三、自愈：水位那天没有行 → 从那天重算

正常模式的起点是"水位 + 1 天"，但开跑前会查一次**水位那天在 `t_daily_report` 里到底有没有行**；
没有就从水位那天重算。**为什么需要它**：纯"水位+1"在遇到"水位推进了但结果行不见了"时，
那一天会**永远缺**（起点已经越过它）——这正是"同事务"之外要补的第二道保险。

**它的边界（如实写）**：自愈只补**缺失的行**；"行在但值被改错"要走**补数模式**（`from=to=那天`）重算。
实测证据：给 9-25 补了一条真实业务数据后，正常模式跑出 `0 天`（不重算历史），补数 `from=to=2026-09-25` 才把该行刷成新值，且**水位不变**。

### 四、本机实测（专用库 `wo_p6b`，跑完已 DROP，按 D19 留了删除前计数）

**① 数值手算对照**（3 天数据集，工单 5 张 + 日志 7 条）：任务写出的行与手写 SQL（字面日期、独立于任务代码）**逐列一致**：

```
report_date  created  completed  avg_accept  avg_finish  overdue  triage_done  triage_failed
2026-09-24      2         1        30.00       90.00        1         2            0
2026-09-25      1         0         5.01        NULL        1         0            1
2026-09-26      2         2         5.00      810.00        2         2            0
```

**② 跨天边界**：`2026-09-24 23:59:59` 的单落在 9-24、`2026-09-25 00:00:00` 的单落在 9-25；
顺带把"接单事件在次日 00:00:00"的效果也测进去了——9-25 的 `avg_accept=5.01` 正是
`(1 秒 + 10 分钟)/2` 的结果（0.0167 与 10 的平均，保留两位）。

**③ 不含今天**：`SELECT COUNT(*) FROM t_daily_report WHERE report_date = CURDATE()` → **0**。

**④ 幂等**：(a) 正常模式连跑两次 → 第二次 `本轮汇总 0 天`，两次 `SELECT` 输出逐列一致；
(b) 补数模式 `from=2026-09-24;to=2026-09-26`（**真的重算 3 天**）→ 输出仍逐列一致，水位不变。

**⑤ 水位**：首次跑到 `2026-09-26`（= 昨天），再跑一次不动；补数模式跑完仍为 `2026-09-26`。

**⑥ 崩溃安全（两种损坏都可自愈）**：删掉 9-26 的结果行（水位仍指 9-26）→ 再跑 → 日志打
`自愈：水位日 2026-09-26 没有结果行，从该日重算` → 该行恢复且数值与原值逐列一致。

**⑦ 同事务的直接实证（本步最强的一条，手工注入）**：给 `t_job_watermark` 建一个
`BEFORE UPDATE ... SIGNAL SQLSTATE '45000'` 的触发器让**水位写失败**，仍然删掉 9-26 的结果行再跑：

```
handleCode=500，handleMsg = ... SQLState[45000] injected failure: watermark write fails
失败后：rows_after=3（与失败前相同）、row_0926_exists=0  ← **结果行跟着水位一起回滚了**
```

随后删掉触发器、再跑一次 → 自愈恢复，数值一致、水位不变。**这条比"读代码说它们在同一个 @Transactional 里"强**：
它证明了失败时两者一起消失，而恢复只靠重算。

### 五、代价与未覆盖

1. **分片没做**：本步 `shardTotal` 参数只接受并记进列里（>1 时日志显式提醒"本步未实现分片"）；
   `t_daily_report_part` 已建表但不写——留给步骤 3。
2. **首次运行只算昨天**（不自动回填历史）：要历史得用 `from`/`to`。这是刻意的，避免"第一次跑就把两年的数据全算一遍"。
3. **`avg_*` 是"当日发生的事件"口径**（不是"当日创建的单的接单时长"）：这样重算任意历史日的结果是确定的，
   不依赖"后来有没有人接单"。两者在跨天场景下数字不同，选哪个已经写在列注释里。
4. **`overdue_count` 会重复计入长期逾期单**（时点口径的必然结果），已写进列注释与本文档，避免被当成"当日新增逾期"。
5. **真机 admin 里的任务还没建**：控制台配置（CRON 04:30、与归档 03:30 错峰）见 `deploy/UPGRADE-P6.md` §5。

- **关联**：`sql/hotfix-p6-report.sql`（DDL + 口径注释）、`src/main/resources/mapper/DailyReportMapper.xml`（口径注释第二处）、
  `deploy/UPGRADE-P6.md` §5、`ASYNC-SCHEDULING-PLAN.md` §P6、D70（归档任务；两者都必须错峰跑）

### 六、收口（2026-09-27 同日晚）：日报加一层分片——**写 part → 齐了就收尾**

**结构变化**（其余逻辑一律不动：只算到昨天、一天一事务、水位与结果同事务、补数不动水位、水位日缺行自愈）：

```
每个分片：算 MOD(id, shardTotal)=shardIndex 那份 → UPSERT 自己的 t_daily_report_part 行（带 shard_total）
        → 看该日"齐了没"：
            不齐 → 打一行「等待其它分片（已有 k/N）」并正常返回（不是失败）
            齐了 → 收尾（同一事务）：删该日 part 行（= 认领）→ 主表整天重算 → 推水位
```

**偏离规格一处，理由在下面（必须知道）**：规格写的是"**只有 `shardIndex == 0` 尝试收尾**"，
实现改成了"**谁发现齐了谁收尾**"。三条理由：
1. **规格给的乱序判据要求它**：其判据是"先跑 shard 2 → 等待（1/3）；再跑 shard 0 → 等待（2/3）；**跑 shard 1 → 收尾完成**"。
   按"只有 shard 0 收尾"，跑完 shard 1 之后不会有人收尾（必须等 shard 0 再跑一轮），那条判据无法成立。
2. **固定 shard 0 有单点**：分片 0 那台执行器不在线/没注册，其余分片**永远等不到收尾**。
   而"谁齐谁收尾"在真实运行（N 个执行器同时触发）下**一轮就收敛**（最后跑完的那个当场收尾）。
3. **并发收尾有代价但可控**：两个分片可能同时看到"齐了"，用**删 part 行**这个原子动作裁决
   （删到 ≥ N 行才算认领成功，输家删到 0 行 → 抛 `PartClaimLostException` → 回滚 → 记为"已被其它分片收尾"，**不算失败**）。
   ⚠ 这条**放大了审计口径**：分片版**不是**"有且仅有一个写入者"，而是"写入者是幂等的、且最多 N 个里有一个真写"。
   主表写入、删 part、推水位三者在同一事务里，重复执行结果相同（`INSERT ... ON DUPLICATE KEY UPDATE`），所以安全。

**齐备判据用"分片下标覆盖"而不是"行数"**（改了规格里"COUNT(*) == shardTotal"的说法，同样是实测逼出来的）：
`COUNT(DISTINCT shard_index) == N` **且**所有行的 `shard_total` 一致且等于 N。
理由是 part 表主键 `(report_date, shard_index)`：同一分片只会有一行，而"行数"会被两类东西抬高——
上一轮换了分片数的遗留行、以及**并发收尾窗口里败方补写的那一行**（本轮实测出现过 1 行残留）。
用下标覆盖之后，残留行不再阻塞任何事：该日下次被重算时对应分片 UPSERT 覆盖它，齐备判据照样成立，收尾时一并删掉
（**已实测**：先造残留行，再把水位退回、删主表行，重跑三个分片 → 该日照样收尾成功、`part=0`、数值正确）。

**本机实测（专用库 `wo_p6c`，一次性，跑完已 DROP；数据与 D71 第五节完全相同，便于逐列对比）**：

| 工单 | 判据 | 结果 |
| --- | --- | --- |
| **回归** | `shardTotal=1` 跑同一批数据，`t_daily_report` 与改造前（单机全量）**逐列一致** | 09-24 `2/1/30.00/90.00/1/2/0`、09-25 `2/1/16.67/90.00/1/0/2`、09-26 `2/2/5.00/810.00/2/2/0` —— **与改造前完全相同**；`shard_total=1`；`part=0` |
| **分片一致** | `shardTotal=3` 手工跑 0/1/2 后逐列对比 | 同上三行完全相同；`shard_total=3`；`part=0` |
| **乱序收敛** | 先 shard 2 → 等（1/3）、主表无该日行、水位不动；再 shard 0 → 等（2/3）；再 shard 1 → **当场收尾** | 日志原文：`date=2026-09-24 等待其它分片（已有 1/3）` ×3 → `等待其它分片（已有 2/3）` ×3 → `收尾完成（水位已推进，分片 1/3）` ×3；水位 `2026-09-23 → 2026-09-26` |
| **重复收尾** | 收尾后再跑 shard 0 | `本轮收尾 0 天、等待 0 天`（水位已在昨天 → 无待算日期）；行数/part/水位全部不变 |
| **并发收尾** | 先写 0/2，再**同时**打两次 shard 1 | 一个：`收尾完成` ×3；另一个：`等待（1/3）` → `已被其它分片收尾（本分片跳过）` ×2，`handleCode=200`（**不是失败**）；主表数值正确、`shard_total=3`、水位正确 |

- **关联**：`t_daily_report_part`（主键 `(report_date, shard_index)` + `shard_total` 列）、`DailyReportWriter.finalizeDay`（认领+写主表+推水位同事务）、`DailyReportJob.canFinalize`（纯函数，4 条单测）

## D72 · P6 收口 + 三条 xxl-job 平台语义（**新建默认停止** / 失败不停任务 + `alarm_status` / 漏跑迁移无人报警）

- **日期**：2026-09-27　**前置**：D70（归档清理）、D71（日报 + 分片）
- **范围**：P6 的收口（把"验过什么、谁验的"写清）+ 三条**平台本身**的语义——它们不是我们的代码，
  但**决定了运维动作的顺序**，踩一次就知道代价。

### 一、P6 收口（判据分层）

| 交付物 | 本机验证 | 服务器验证 |
| --- | --- | --- |
| `archiveJob`（按保留期分批删除三张表） | ✅ 完整原文（D70 §三）：3600 行一轮删净、预算用尽 `BUDGET_EXHAUSTED`、重跑 0 行、三分片之和 == 单分片、EXPLAIN 走索引 | ✅ `xxl_job_log` 行 49/50：`handle_code=200` + 业务摘要（`本轮删除 0 行…已删完`，演示库最老数据 09-25 故为 0）；`t_archive_log` 6 行（两轮 × 三目标）。原文见本文附录 ②④ |
| `dailyReportJob`（日报，含分片） | ✅ 完整原文（D71 §四/§六）：逐列与手写 SQL 一致、不含今天、幂等、水位、补数、自愈、**触发器注入证明同事务**、分片回归/乱序/并发 | ✅ `xxl_job_log` 行 48/54：`handle_code=200`；`t_daily_report` 09-26 行落地（`created=40 / overdue=542 / avg_*=NULL`）；`trigger_status=1`。原文见本文附录 ②③ |
| DDL（`sql/hotfix-p6-archive.sql` / `sql/hotfix-p6-report.sql`） | ✅ 幂等重跑、缺列/缺索引补列补索引 | ⚠ **执行输出仍未取得（附录 ⑤，保持待补）**；但"表已存在"可由 `t_archive_log` / `t_daily_report` 有行**间接**证明 |
| **未做**：`t_work_order` / `t_work_order_log` 的**归档搬运** | — | — |

**服务器上的任务形态（附录 ① 原文）**：`xxl_job_info` 共 **6 行 = 5 个自有任务 + 1 个平台示例任务**——

| id | 任务 | 调度类型 | 配置 | handler | 参数 | `trigger_status` |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 测试任务1（**平台自带**） | CRON | `0 0 0 * * ? *` | demoJobHandler | — | **0（停止）** |
| 2 | 超时释放扫描 | FIX_RATE | 60 | releaseTimeoutScan | — | 1 |
| 3 | SLA升级扫描 | FIX_RATE | 300 | slaEscalationScan | — | 1 |
| 4 | 归档（库表） | CRON | `0 30 3 * * ?` | archiveJob | `tables=consume_record,message_retry;retentionDays=30;…` | 1 |
| 5 | 归档（outbox） | CRON | `0 45 3 * * ?` | archiveJob | `tables=outbox_sent;retentionDays=7;…` | 1 |
| 6 | 日报 | CRON | `0 30 4 * * ?` | dailyReportJob | — | 1 |

**注意 4/5 两行**：`archiveJob` **被两个任务复用**，分别用 30 天（库表）与 7 天（outbox）保留期、
错开 15 分钟跑。这不是冗余，而是**per-table 保留期下限的直接后果**（D70 §六）——
下限按表算，就不可能用一条参数同时满足 30 与 7，于是"一个 handler + 两个任务"成了正确形态。

### 二、平台语义①：**新建任务默认是"停止"，不点"启动"就永远不跑** —— **原先的推断被实验否掉**

- **原以为**：在控制台把任务建好、把"调度类型"填成 `Cron触发`、保存——它就会按 CRON 开始跑。
  （推断来源：任务列表里能看到调度配置，"看起来"已经在调度。）
- **后来发现（实验否掉）**：新建任务的 `trigger_status` 是 **0 = 停止**，
  **必须手动点"启动"** 才会被调度。`xxl_job_info.trigger_status` 的 DDL 默认值就是 `'0'`（引
  `sql/xxl-job/tables_xxl_job.sql`：`trigger_status tinyint(4) NOT NULL DEFAULT '0' COMMENT '调度状态：0-停止，1-运行'`）。
- **实测样本（附录 ①）**：服务器上 6 行里，平台自带的 `测试任务1` 至今是 **`trigger_status=0`**——
  它就是"建了但没启动"的活样本；我们自有的 5 个任务全是 1。
- **顺带钉死一个文案**：`xxl_job_log.trigger_msg` 的原文是 **`任务触发类型：Cron触发`**（附录 ② 的行 46），
  所以控制台/日志里的"**Cron触发**"是**显示名**，而库里 `schedule_type` 存的是 **`CRON`**——
  写文档/写 SQL 时两者都要能对上（README §6.6 的任务表就是这么写的）。
- **代价**：建完不启动 = **一行日志都没有**（不是失败、不是告警，是"根本没跑"）。
  这类静默最难查：控制台任务在、配置对、执行器也在注册，就是没有 `xxl_job_log` 行。
- **判据**：`SELECT id,job_desc,trigger_status FROM xxl_job_info;` → 期望自有任务都是 **1**。
  **这也是把"任务启动状态"写进 P7 演示前清单的原因**（见 `ASYNC-SCHEDULING-PLAN.md` §P7）。

**调度中心驱动的证据（2026-09-27 新增：`t_archive_log` 行数增长）**

附录 ② 给的 `trigger_msg`（`任务触发类型：Cron触发`）证明的是"**这一次是调度触发的**"，
但那是**单次**证据。本次清库时的删前统计给出了**跨轮**证据：

```
删前：t_archive_log = 22 行   （D72 附录 ④ 那次读数时是 6 行）
```

`t_archive_log` 只在 `archiveJob` 收尾时写（每（表 × 分片 × 轮次）一行），而清库时没有人在手工触发它
⇒ **那 16 行的增量只能来自 03:30 / 03:45 的夜间 CRON**。所以：
· **CRON 型任务真的按点在跑**（此前只有 `FIX_RATE` 型任务的 `xxl_job_log` 证据，CRON 这一档是空白）；
· 与语义①合起来看才是完整的：**"任务建好"不够（`trigger_status` 可能是 0），"跑过一次"也不够
（可能只是人工点的）——要看"**没人管的时候它自己跑了没有**"**；
· ⚠ **逐轮明细未贴**（只给了行数）：22 = 6 行 + 16 行增量，**这 16 行对应哪几轮/哪几个目标无法从行数反推**
（`archiveJob` 被两个任务复用：库表那轮写 2 行/目标×2、outbox 那轮写 1 行，合计 3 行/夜 —— 16 与 3 不成整数倍，
所以**更说明不能靠行数反推**）。

**✅ 升级（2026-09-27 晚，明细已到手）**：`t_archive_log` **22 行，含 03:30 与 03:45 的 CRON 落点**——
`ran_at` 出现两组每日落点：**03:30 附近**（`归档（库表）` 那轮，参数 `tables=consume_record,message_retry`）与
**03:45 附近**（`归档（outbox）` 那轮，参数 `tables=outbox_sent`），与两个任务的 CRON（`0 30 3 * * ?` / `0 45 3 * * ?`）**逐点对得上**。

**row 20/21 是同一轮的两行，不是重复写**：留痕粒度是"每（表 × 分片 × 轮次）一行"，
而 03:30 那轮有**两个目标**（`consume_record` + `message_retry`）⇒ 同一个 `ran_at` 下**本来就该有两行**；
03:45 那轮只有 1 个目标 ⇒ 一行。**所以"每晚 3 行"是正常的**（2 + 1），
用行数反推"跑了几晚"时**除数要用 3**。（03:30/03:45 两个时间点本身来自上一段对账，明细原文见
`deploy/CLEANUP-BEFORE-DEMO.md` §9.4②。）

### 三、平台语义②：**失败不会把任务停掉**；而且**"失败"在日志里有两种指纹、不能只看一种**

- **失败不停任务（实测）**：附录 B 的失败实验里，把 handler 改成 `archiveJobTYPO` 之后
  行 70/75 两条都是失败记录，而 `trigger_status` **始终为 1** → **失败不会把任务停掉，下一轮照跑**。
  ⇒ 失败会**反复发生**，不会"自己停下来等人"。
- **告警状态在 `xxl_job_log.alarm_status`**（引 DDL 注释）：`0-默认、1-无需告警、2-告警成功、3-告警失败`。
- **⚠ 修正（上一轮我写错了，这里按实测改）**：上一轮我写的是"没配报警邮箱时告警是静默的，
  `alarm_status` 停在'无需告警'那一档"。**实测否掉了这个说法**——附录里两类失败记录的
  `alarm_status` 都是 **2（告警成功）**：
  · 漏跑迁移的三条 500（行 7/8/9）：`alarm_status=2`；
  · handler 名写错的两条（行 70/75）：`alarm_status=2`。
  **"告警成功"只表示平台的告警流程执行成功，不表示有人收到通知**。
  **收件人取值随时可查**（把这一列加进巡检 SQL ③ 即可：`SELECT id, job_desc, alarm_email FROM xxl_job_info;`；
  平台示例任务在 seed 里就是**空串**）——但**结论与它无关**：
  即便配了收件人，"告警成功"也只说明投递动作执行了，**不能当"有人看过了"的凭证**（所以依据要改准、结论不变）：
  **不能拿 `alarm_status=2` 当"有人看过了"的凭证**——失败仍然是"没人主动告诉你"，只能靠巡检。
- **失败在日志里有两种指纹（实测，很容易只看一种）**：
  | 指纹 | `handle_code` | `handle_msg` | 典型场景 |
  | --- | --- | --- | --- |
  | A | **500** | 有 SQL/异常原文（如 `Table 'work_order.t_archive_log' doesn't exist`） | 代码跑起来了、**库或数据有问题**（漏跑迁移） |
  | B | **0** | **NULL** | **handler 根本没找到**（漏 rebuild / 任务配置里 handler 名写错）——触发成功（`trigger_code=200`）但**一次都没执行** |
  ⇒ 巡检 SQL 必须同时看 `handle_code <> 200` **和** `handle_msg IS NULL`：只看"非 200"会漏掉 B（0 也是非 200，但 `= 500` 的过滤会漏），只看 `handle_msg` 会漏掉 A 里的 NULL。
- 因此本项目的判断口径是：**任务的健康看 `xxl_job_log`，不看控制台的颜色**（同族教训见 D24：HTTP 200 ≠ 业务成功）。

### 四、平台语义③：**漏跑迁移没有人会报警**

- **实测原文（附录 ② 的行 7/8/9）**：`handle_code=500`、`alarm_status=2`，
  `handle_msg` = `### Error updating/querying database … Table 'work_order.t_archive_log' doesn't exist`
  （另一条是 `t_job_watermark`）。这三条就是"迁移补齐前"的真实记录。
- 因为语义②：**没有任何主动通知**（`alarm_status=2` 只代表"平台告警流程成功"，**不代表有人收到**）；
  线索只剩 `xxl_job_log.handle_msg` 里那段 SQL 报错——或者**重启一次看 `SchemaStartupCheck`**
  （P7 步骤 1 新增，它会直接点名该跑哪支脚本，见 `deploy/DEPLOY-RUNBOOK.md` §4/§7）。
- **结论（写进运维动作顺序）**：`init.sql → hotfix-*.sql → 重启后端 → 建/启动任务 → 巡检 SQL`。
  **"迁移有没有跑"只能主动查**，不能指望报警——这就是下一条巡检 SQL 存在的理由。

### 五、巡检 SQL（README 运维章节同步）

```sql
-- ① 任务清单 + 启停状态：trigger_status=0 就是"绿色配置但根本不跑"
SELECT id, job_desc, trigger_status FROM xxl_job.xxl_job_info;

-- ② 每个任务的执行量 + 最后一次调度时间：能发现"停摆"（MAX(trigger_time) 长期不动）与"长期失败"
SELECT job_id, COUNT(*) AS n, MAX(trigger_time) FROM xxl_job.xxl_job_log GROUP BY job_id;

-- ③ 追失败细节（②发现异常后用它定位）
SELECT id, job_id, trigger_code, handle_code, alarm_status, trigger_time, LEFT(handle_msg, 300)
  FROM xxl_job.xxl_job_log WHERE handle_code <> 200 ORDER BY id DESC LIMIT 20;
```

### 六、代价与未覆盖

1. **不配报警邮箱 = 放弃平台告警**：本项目暂时只用巡检 SQL；接邮件/Webhook 属 P7 的监控覆盖项（§P7 ⑤）。
2. **`trigger_status` 是"任务级"开关，不是"业务开关"**：它管"调度中心要不要触发"，
   与我们的 `workorder.outbox.dispatch.enabled`（管消费/投递）是两层，别混（P2 那处"开关默认值"教训）。
3. 三条语义的**行为**是在服务器上实测得到的；本机能复核的是**静态依据**（建表脚本里的默认值与 `alarm_status` 注释），
   所以本节把两者分开写：**"平台行为：服务器实测" vs "默认值/枚举：可被建表脚本复核"**。

- **关联**：`deploy/UPGRADE-P2.md` §9（两个扫描任务的控制台配置）、`deploy/UPGRADE-P6.md` §5（两个 P6 任务）、
  `README.md` §六 5.6（巡检 SQL 与任务清单）、`ASYNC-SCHEDULING-PLAN.md` §P6/§P7、D70/D71

### 附录 A · 服务器实测原文（2026-09-27，由委托方从 SSH 输出转贴，**未经改写**）

> **来源**：委托方在服务器上手工执行后转贴的四段输出。转贴说明见每段的标题。
> ⚠ 第 ⑤ 段（`hotfix-p6-*.sql` 的执行输出）**未取得**——本附录**不补造**它，D72 §一 与
> `ASYNC-SCHEDULING-PLAN.md` §P6 的对应位置**维持"待补"**。

**① `xxl_job_info`（6 行：5 个自有任务 + 1 个平台示例任务；`archiveJob` 被两个任务复用）**

```
1  测试任务1        CRON      0 0 0 * * ? *   demoJobHandler                       trigger_status=0
2  超时释放扫描      FIX_RATE  60              releaseTimeoutScan                   trigger_status=1
3  SLA升级扫描      FIX_RATE  300             slaEscalationScan                    trigger_status=1
4  归档（库表）      CRON      0 30 3 * * ?    archiveJob  tables=consume_record,message_retry;retentionDays=30;batchSize=1000;maxBatches=20   trigger_status=1
5  归档（outbox）    CRON      0 45 3 * * ?    archiveJob  tables=outbox_sent;retentionDays=7;batchSize=1000;maxBatches=20                 trigger_status=1
6  日报             CRON      0 30 4 * * ?    dailyReportJob                                                          trigger_status=1
```

**② `xxl_job_log`（节选；`trigger_msg` 里的"任务触发类型：Cron触发"就是"调度触发"的原文凭证）**

```
46  2  任务触发类型：Cron触发   200  0  [release-scan] 触发来源=xxl 本轮释放 0 条（候选 0 跳过 0 出错 0 缺配置 0）
47  2  任务触发类型：Cron触发   200  0  同上
48  6  任务触发类型：Cron触发   200  0  [daily-report]（正常模式）shardTotal=1 本轮汇总 1 天（2026-09-26..2026-09-26）；水位 null → 2026-09-26
49  4  任务触发类型：Cron触发   200  0  [archive] shard=0/1 cutoff=2026-08-28T02:45:00 本轮删除 0 行（consume_record=0, message_retry=0）；已删完
50  5  任务触发类型：Cron触发   200  0  [archive] shard=0/1 cutoff=2026-09-20T02:45:00 本轮删除 0 行（outbox_sent=0）；已删完
54  6  任务触发类型：Cron触发   200  0  [daily-report]（正常模式）shardTotal=1 本轮汇总 0 天；没有需要汇总的日期（水位=2026-09-26，只算到昨天）
```

列序：`id / job_id / trigger_msg / handle_code / alarm_status / handle_msg`。
另有 **行 7/8/9 三条"迁移补齐前的 500 失败"**：

```
### Error updating/querying database … Table 'work_order.t_archive_log' / t_job_watermark doesn't exist；alarm_status=2
```

**B 段（失败语义实验）**：把 handler 改成 `archiveJobTYPO` 之后，

```
70/75 两条：handle_code=0、alarm_status=2、handle_msg=NULL，而 trigger_status 始终为 1
```

**③ `t_daily_report` 演示库那行**

列序：`report_date, created, completed, avg_accept, avg_finish, overdue, triage_done, triage_failed, generated_at, shard_total`

```
2026-09-26   40   0   NULL   NULL   542   40   0   2026-09-27 02:45:00   1
```

**④ `t_archive_log`（6 行，两轮 × 三目标；演示库最老数据 09-25 故全部删 0 行）**

```
6  archive:outbox_sent     0/1  cutoff=2026-09-20 02:46:00  deleted=0  DONE
5  archive:message_retry   0/1  cutoff=2026-08-28 02:46:00  deleted=0  DONE
4  archive:consume_record  0/1  cutoff=2026-08-28 02:46:00  deleted=0  DONE
3  archive:message_retry   0/1  cutoff=2026-08-28 02:45:00  deleted=0  DONE
2  archive:outbox_sent     0/1  cutoff=2026-09-20 02:45:00  deleted=0  DONE
1  archive:consume_record  0/1  cutoff=2026-08-28 02:45:00  deleted=0  DONE
```

**⑤ `hotfix-p6-*.sql` 的服务器执行输出：未取得**（不编造，维持"待补"）

**⑥ 停 admin 期间**本地兜底的节拍（`docker compose stop xxl-job-admin` 之后；期间**无任何** `触发来源=xxl`）

```
2026-09-27T00:37:15.443+08:00  INFO 1 --- [   scheduling-1] c.w.scheduler.ReleaseTimeoutScheduler : [release-scan] 触发来源=local 开始扫描（单轮上限 200）
2026-09-27T00:37:15.445+08:00  INFO 1 --- [   scheduling-1] ... [release-scan] 触发来源=local 本轮释放 0 条（候选 0 跳过 0 出错 0 缺配置 0）
2026-09-27T00:38:15.443+08:00  INFO 1 --- [   scheduling-1] ... 触发来源=local 开始扫描
2026-09-27T00:39:15.443+08:00  INFO 1 --- [   scheduling-1] ... 触发来源=local 开始扫描
2026-09-27T00:40:15.443+08:00  INFO 1 --- [   scheduling-1] ... 触发来源=local 开始扫描
```

**判读（这是 P2 那处"可靠性净倒退"是否被堵住的直接证据）**：
· 窗口内**四个节拍、三次间隔全是精确 60.000s**（`.443 → .443 → .443 → .443`）⇒ 本地兜底**不依赖 admin**，而且**停 admin 不拖慢节拍**
（若本地 `@Scheduled` 与调度中心共用线程/被远端拖住，这里会看到抖动或缺口）；
· 窗口内**没有一行 `触发来源=xxl`** ⇒ 这条证据与"xxl 那条路"是**互斥**的，不存在"以为是兜底其实是调度中心"的混淆；
· 与语义①/② 的关系：**只要 `trigger_status=1` 的任务真的在跑，就看得到 xxl；admin 一停，就只剩 local**——
两条通道的存在与切换**都能在日志里指出来**（这正是"来源标记"要解决的问题）。

**⑦ 6 容器 `MemAvailable` 读数时的 `free -m` 原文**（取数时刻＝admin 启动后约 20 秒、整栈未热身）

```
               total        used        free      shared  buff/cache   available
Mem:            3723        1972         185          35        1891        1750
Swap:           1987           1        1986
```

**判读**：`available 1750` 与 §1.6.8 表里的 **MemAvailable 1750 MiB** 是同一次取数（原文已补进该节）；
`Mem total 3723` 印证"标称 4G 实际可用 3.7G 上下"；**宿主机 swap 只用了 1 MiB** ⇒ 整栈没在换页
（容器 swap 已禁用，这是另一层旁证）。

## D73 · 种子数据"**双编码**"：同一个默认字符集的**另一面**（这次坏在「写」）+ 两处口径更正

- **日期**：2026-09-27　**现象**：页面上的**角色名 / 权限名乱码**，但**后端行为完全不受影响**
  （权限判断、关联查询都照常——所以**没有任何探针会报警**，只能靠人眼看页面）。
- **性质**：不是新根因，而是**同一个"客户端默认字符集"的第二个面**——D68 附录说明 §1 那条坏在**读**，
  这次坏在**写**。

### 一、同一根因的两面（读 vs 写）

| 面 | 触发场景 | 观测量 | 表现 | 已有记录 |
| --- | --- | --- | --- | --- |
| **读** | 查询/导出时（容器内 `mysql` 客户端**不带**参数） | `character_set_results` | 中文**显示成 `?`** ——**数据本身是好的**，只是回显按 latin1 解 | D68 附录说明 §1；`deploy/UPGRADE-P2.md` §2 已提醒"中文列一定要带 `--default-character-set=utf8mb4`" |
| **写** | **导入种子脚本**时（`mysql < sql/xxx.sql` **不带**参数） | **`character_set_client`** | **表里真的存了"双编码"的字节** → 页面渲染乱码；**数据是坏的** | **本条（D73）** |

**为什么"写"这一面更隐蔽**：读错了，加个参数就恢复；写错了，**改参数也救不回来**——
那批字节已经以一种"合法但错误"的方式存进库了（MySQL 认为它存的就是 utf8mb4，校验不会报错）。

### 二、根因：客户端默认字符集**由 locale 决定**

`mysql` 客户端的默认字符集不是"跟着服务端走"，而是**按 locale 推断**：
`LANG=C` / `POSIX`（以及未设 locale 的最小镜像）→ 默认 **latin1**；`C.UTF-8` / `en_US.UTF-8` → 默认 utf8 系。
所以同一台机器上"有时乱、有时不乱"完全可能。
**结论**：**别依赖 locale**——导入/查询一律显式带 `--default-character-set=utf8mb4`
（`deploy/DEPLOY-RUNBOOK.md` §2 的 `mysqlq()` 函数已经带了 ✔；**手工敲 `mysql < xxx.sql` 时最容易漏**）。

### 三、判据（分清"显示坏"与"数据坏"）——**写法留档**

```bash
# ① 观察入口：一律在**带参数**的客户端下看。此处仍乱码 = **数据坏**；此处正常 = 之前只是"显示坏"
mysql -h127.0.0.1 -P3306 -uroot -p --default-character-set=utf8mb4 work_order \
  -e "SELECT id, role_code, role_name FROM t_role;"

# ② 根因观测量：客户端字符集（期望 utf8mb4；显示 latin1 就是它把字节读/写错了）
mysql -h127.0.0.1 -P3306 -uroot -p --default-character-set=utf8mb4 work_order \
  -e "SHOW VARIABLES LIKE 'character_set_client';"
```

**③ 量化判据（本条更正后的口径，推导见下一节）**：**字符数涨约 3 倍**、**字节/字符比从 3 降到 ≈2**。

### 四、两处口径更正

1. **"双编码会变成 ≈6 字节/字符"——不对**。正确推导：
   · 一个汉字在 utf8mb4 里是 **3 字节**（且这 3 个字节都 ≥ 0x80）；
   · **双编码 = 把这 3 个字节各自当成一个 latin1 字符、再按 utf8mb4 存一遍** ⇒ 每个字节变成一个
     **2 字节**的 UTF-8 字符（U+0080–U+07FF 都是 2 字节）⇒ **一个汉字从「1 字符 / 3 字节」变成「3 字符 / 6 字节」**；
   · 所以判据是：**字符数涨约 3 倍**，**字节/字符比从 3.0 降到 ≈2.0**
     （纯中文理论值正好 **2.0**；**实测 ≈2.1** 是因为文本里混了少量 ASCII/标点——ASCII 部分不涨，会把比值往 3 拉）。
   · ⚠ 按"6 字节/字符"去扫会**一个都扫不到**（库里没有 6 字节/字符的行）。
2. **仓库里没有这个错说法**（本轮 `git grep -n "6 字节|字节/字符|双编码"` = **0 命中**，已查），
   它只出现在对话里；本条把**正确口径**落档，避免下一个人按错倍数去排查。

### 五、修法：**定向 `UPDATE` 即可**（不要把"重导"当首选）

**✅ 实测（2026-09-27）**：按 `role_code` / `perm_code` 定向 `UPDATE`，**4 条角色 + 15 条权限一次清零**——
这两个数字与 `sql/init.sql` 的**种子行数完全一致**（`t_role` 4 行、`t_permission` 15 行，可 `SELECT COUNT(*)` 复核），
也就是说"受影响的恰好就是种子行"。

**为什么定向 `UPDATE` 就够（不必重建/重导）**：

| 写入路径 | 连接字符集 | 会不会被客户端 locale 带偏 |
| --- | --- | --- |
| **种子导入**（`mysql < sql/init.sql`，命令行客户端） | 由客户端 locale 推断（`LANG=C` → latin1） | **会** ⇒ 这条路径是**唯一**的污染来源 |
| 业务运行期（应用经 JDBC 写入） | 连接串里固定 `characterEncoding=utf8` / `connectionCollation=utf8mb4_unicode_ci` | **不会** |

⇒ 受影响的行 = **种子行**，**范围已知且很小**，所以"缺什么补什么"的定向 `UPDATE` 是首选：
**修复值逐字取自 `sql/init.sql` 的种子原文**（唯一真源），按 `role_code` / `perm_code` 定位。
已落成可重跑脚本：**`sql/hotfix-seed-encoding-repair.sql`**（19 条 `UPDATE` + 判据；健康库上运行等于写回同样的值，无副作用）。

**什么时候才考虑重建/重导**：污染范围**未知或很大**时（例如怀疑业务行也被写坏、或整库数据都过了错误的客户端）。
那时先用 D73 §四的量化判据（**字符数 ≈3 倍 / 字节·字符比 ≈2**）扫一遍确定范围，再决定范围与做法。

**❌ `ALTER TABLE ... CONVERT TO CHARACTER SET utf8mb4` 对双编码无效**：它改的是"列的字符集声明"，
而双编码是**字节语义**已经错了（MySQL 认为存的本来就是 utf8mb4，校验不会报错）。

> **更正（上一轮我说过头的）**：上一版本节写的是"受影响的行**必须重导/重写**"——**过强**。
> 正确说法：**能用定向 `UPDATE` 就别重导**（重导会连带覆盖库里其它改动，代价更大）；重导只在"范围未知或很大"时才考虑。

**判据（修完立刻验）**：
1. **在带 `--default-character-set=utf8mb4` 的客户端下**看 `t_role` / `t_permission`：中文正常**且与 `sql/init.sql` 的种子原文逐字相等**（不是"看着像"）；
2. 需要更硬的证据就比 `HEX(role_name)`（与预期 utf8mb4 编码逐字节对照）；
3. **范围复核**：`t_role` = 4 行、`t_permission` = 15 行（"只有种子行受影响"的判据）。

### 六、为什么它值得单独记一条

- **后端逻辑不受影响** ⇒ 所有探针、所有 `handle_code`、所有业务异常都是绿的，**只有"人看一眼页面"能发现**。
  这正是 `deploy/DEMO-SCRIPT.md` §0.2 要求"演示前走一遍完整页面走查"的直接理由——**这次的双编码问题就是那一步抓到的**。
- 与 D68 §1 是**同根不同面**：两处放在一起讲，才不会出现"以为加个参数就万事大吉"的误解。

- **关联**：D68（附录说明 §1：容器内 mysql 客户端默认 latin1 = **读**的那一面）、
  `deploy/UPGRADE-P2.md` §2（中文列必须带 `--default-character-set=utf8mb4`）、
  `deploy/DEPLOY-RUNBOOK.md` §2（迁移一律走 `mysqlq()`）、`deploy/DEMO-SCRIPT.md` §0.2（页面走查）

## D74 · 调查助手：首版问题类型与"完成"的判据由后端按事实覆盖度判定（工具集随之定稿）

- **日期**：2026-10-05（S0 收口）
- **问题**：模型的报告写到什么程度算"调查完成"？谁说了算？
- **备选项**：① 模型自报 `COMPLETED`；② 后端按"必需的证据事实是否齐全"判定；③ 按报告字数/结构完整度判定
- **选择**：② **后端按事实覆盖度判定**。首版只声明三类问题（`ORDER_STATUS` / `TIMEOUT_REASON` / `REASSIGN_HISTORY`）+ 一类兜底（`UNSUPPORTED`），
  每类规定**必需事实 / 允许未知项 / 建议前提**（清单见 `docs/AGENT-PLAN.md` §3.1）。
- **理由**：① 与③ 都把"模型说它做完了"当成结论——本项目已经吃过同一类亏（`INVARIANTS.md` I12：triage 返回兜底值会把"LLM 没调通"写成 `DONE`），
  "外部服务不可用时不得伪造结论"是同一条原则。判据必须落在**证据的 `fact` 字段**上：模型写十句"已确认处理中"却没引用 `order.status` 的证据，仍判未覆盖。
  声明"只支持三类"本身也是结论：**不支持的问题必须显式输出 `UNSUPPORTED`**，而不是硬凑一个答案。
- **代价**：首版覆盖面窄，委托方问的问题大概率落在"不支持"里（这是刻意的保守，换取不编造）；
  必需事实清单是手工维护的，S2 接真实工具后每加一类都要同步改这张表，漏改会让新类型永远判"未完成"。
- **关联**：`docs/AGENT-PLAN.md` §3.1、§9 第 1 项；`INVARIANTS.md` I12

## D75 · 调查助手：`finish_report` 只交证据/建议编号，预算单列，校验失败重试一次

- **日期**：2026-10-05（S0 收口）
- **问题**：模型怎么结束调查？它交的东西谁来验？验不过怎么办？
- **备选项**：报告内容 ① 模型自由文本 + 结构化草稿；② 只交 `problemType` + 证据编号 + 建议编号，正文由后端渲染。
  校验失败 ① 直接判失败；② 重试一次；③ 无限重试直到通过
- **选择**：②（只交编号）+ 校验失败**重试一次**，二次不过判 `FAILED(REPORT_INVALID)` 且**不产出报告**
- **理由**：只要允许模型交自由文本，"哪些话有证据支撑"就无法机械校验，事实编号会退化成装饰；
  只交编号后，**每个结论都能被追到一条真实存在的证据**。`finish_report` **不注册进工具表**——
  它不是第五个业务查询工具，模型不能"查询"它（`docs/AGENT-PLAN.md` §3.2）。
  选"重试一次"而不是"直接判失败"：缺一个事实编号是最常见的失手，一次重试的成本（一次模型往返）远低于整轮作废；
  选"有限次"而不是"无限"：无限重试等于把预算闸门交给模型，且反复失败会让"调查中"永久化。
- **代价**：报告可读性依赖后端渲染；重试会把同一份上下文再发一次模型（多一次外部调用与 token）；
  报告校验失败被算作**失败**而不是"部分完成"——首版不做局部修补（与 D76 / §5 同源）。
- **修订（2026-10-06，只读复核轮）**：校验缺口原先用 **system 消息**回传，而那条 assistant 消息带
  `tool_calls:[call_finish]`、没有配对的 tool 消息——真供应商会直接 400（本机的桩当时不校验，所以没暴露）。
  现改为**以 `finish_report` 的 tool 应答回传**，并给桩加了"每个 tool_call 必须有 tool 应答"的常驻校验。
  **选择本身（重试一次）不变，变的是回传通道**。见 `docs/AGENT-PLAN.md` 附 C.2 第 1 条。
- **关联**：`docs/AGENT-PLAN.md` §3.2、§3.4（终止口径单列）、§9 第 2 项

## D76 · 调查助手：预算按"工具调用 + 模型轮次"计数，token 不计数

- **日期**：2026-10-05（S0 收口）
- **问题**：一次调查允许花多少？
- **备选项**：① 只限并发；② 工具调用次数；③ 模型轮次；④ token；⑤ 组合
- **选择**：⑤ 组合——**工具 ≤ 12 次 / 模型轮次 ≤ 8 轮 / 墙钟 ≤ 60s**，任一触顶即进终态；**token 不计**
- **理由**：并发 1 只能防积压，防不住"一次调查里连续调 50 次"（`docs/AGENT-PLAN.md` §4.3 已指出）。
  工具调用次数直接对应外部成本与延迟，是主口径；轮次上限防"只说话不调工具"的空转（这类回合不消耗工具预算，
  只靠工具口径拦不住）。**token 不计的理由是"拿不到就不假装有"**：不同供应商的 `usage` 字段有无与口径不一致，
  拿不到的字段写成上限会变成恒不触发的假保护。60s 是 §4.1 的初值（待 S4 按断开/重启实测量准）。
- **代价**：**首版没有 token 级成本闸门**，一个"少调用、长上下文"的滥用模式只能靠 60s 与轮次间接约束；
  计数是**进程内计数，重启即丢**，不是分布式配额——多实例部署时各自的额度独立，这个缺口由 S4 的用户频率限制承担。
- **修订（2026-10-06，只读复核轮）**："工具 12 / 轮次 8 / 墙钟 60s"里，**单轮的模型读取超时原先只受
  模型配置的 30s 约束**，于是 8 轮最坏 240s，60s 形同虚设。现按 `min(剩余预算, 模型读取超时)` 收敛每轮超时
  （连接超时不超过它），被预算收敛的那一轮读超时直接判 `RUN_BUDGET_EXCEEDED`。
  **上限值不变，变的是"单轮时间必须服从剩余预算"**。见 `docs/AGENT-PLAN.md` §4.1 与附 C.2 第 4 条。
- **关联**：`docs/AGENT-PLAN.md` §3.4、§4.1、§4.3、§9 第 4 项

## D77 · 调查助手：外发字段级白名单 + 统一脱敏 + 日志不落原文

- **日期**：2026-10-05（S0 收口）
- **问题**：哪些数据可以发给模型？错误日志里能留什么？
- **备选项**：① 不筛，工具结果整体外发；② 字段级白名单 + 文本脱敏；③ 只给模型编号、正文留在后端
- **选择**：②。白名单：`order_no` / `type` / `priority` / `status` / `created_at` / `sla_deadline` /
  **脱敏后**的 `title`、`content`、日志动作与时间、处理人脱敏显示名。
  禁止外发：`username`、`name` 原值、`phone`、`email`、任何主键 ID、密码哈希、Sa-Token 令牌、未脱敏原文。
  脱敏规则（外发与日志共用）：手机号 `138****5678`、邮箱 `a***@example.com`、身份证 `110101********1234`；
  **姓名只在结构化字段上掩码**（保留首字 + `*`，如 `张伟` → `张*`；复姓只留首字，首版不识别）。
  日志只允许落长度/字节数、证据与建议编号、截断 200 字符且已脱敏的摘要。
- **理由**：① 漏掉了"**用户问题本身**也可能含手机号、姓名"这一条——`AGENT-PLAN.md` §2.1 明确要求把它纳入清单，
  而用户问题正是 `title`/`content`，不脱敏就等于把个人信息直接送出去。③ 虽然最安全，但会让模型无法读懂问题描述，
  首版直接不可行（"不支持的问题"会变成"所有问题"）。② 是唯一既可用又能把红线画清楚的选项。
- **代价（两条，都实测过）**：① 脱敏是**文本正则**，不是字段级强隔离——`content` 里口语化的个人信息
  （"一千三八零零"、拼音、昵称）拦不住；且脱敏后文本损失定位信息（掩码掉的手机号无法用于对账）。
  ② 自由文本**不做姓名脱敏**：中文姓名没有可判定的词边界，`[一-龥]{1,3}` + 句读的正则实测在 4 句常见文案上
  命中 6 个普通词（`已处理`、`实情况`…）却漏掉了真名 `张伟`——既伤内容又不保安全，所以只对结构化姓名字段做掩码。
  首版保留这两个缺口，换取"标准手机号/邮箱不直接出网"。
- **关联**：`docs/AGENT-PLAN.md` §4.4、§2.1、§9 第 5 项；落地点 `SensitiveDataRedactor`

## D78 · 调查助手：复核轮四条风险的裁决（白名单 / 工具上下文 / 取消延迟上界 / 建议前提分级）

- **日期**：2026-10-06
- **问题**：S1 只读复核登记了 4 条风险（`docs/AGENT-PLAN.md` §11），委托方本轮一次性裁决——
  哪些现在做、哪些排到后面、各自接受什么代价？
- **备选项**：① 全部暂不裁决；② 全部立即实施；③ 按"是否改协议、是否依赖真实数据"分批裁决
- **选择**：③ 分批。
  1. **回填 assistant 消息只留字段白名单**（`role` / `content` / `tool_calls`；`content` 恒存在，
     tool-call 轮为 `null`；`tool_calls` 内只留 `id` / `type` / `function.name` / `function.arguments`）：
     **本片实施**。
  2. **工具调用上下文**：采纳方向，形状定为 `AgentTool.execute(ToolContext, JsonNode)`，**S2 第一片**实施。
  3. **取消**：采纳，**S4** 实施；取消延迟上界 = 当前轮读超时；验收必须含"连接断开 + 名额归还"的实证。
  4. **建议前提分级**：采纳，但**只做禁止项**——`order.assignee` 未知或 `order.accept_events` 为空时
     `suggestionIds` 不得含 `CONTACT_ASSIGNEE`；**不做**"必须建议 X"；`TIMEOUT_REASON` 的
     "`sla_deadline` 已过期"要做，但**必须用可注入的 `Clock`**；失败码**复用 `REPORT_INVALID`**，不新开。
- **理由**：分批判据是"这一条要不要改协议 / 要不要真实数据"。
  ① 改的是**我们发出去的报文形状**，只涉及 S1 自己的代码、不依赖任何外部输入，现在做最便宜；
  ② 是接口签名，S2 的第一个真实工具就会撞上——现在定形状、S2 实施，免得 S1 凭空长出一个没有调用者的参数；
  更重要的一条：**S4 的调查跑在消费/调度线程上，那个线程没有 Sa-Token 会话，工具自读登录态根本跑不起来**，
  所以"显式传入调用者身份"不是防御性设计而是可行性前提；
  ③ 只有到 S4（异步接口与名额）才有意义，但**上界必须先写进契约**——否则 S4 会以为 `cancel` 能立刻生效；
  ④ 需要真实 SLA 数据与可控时间源，属 S2。
- **代价**：
  - ① 供应商若靠某个扩展字段维持多轮上下文，会丢；本机无真 key，**无证据**，真 key 复测时一并验。
  - ② 所有工具实现都要改签名；`ToolContext` 一旦定下即**跨片协议**，加字段要走 D 条目。
  - ③ 用户取消后最坏仍要等一个读超时（该值已被剩余预算收敛）；要缩短只能换可中断 IO。
  - ④ 禁止项会拦掉"未分配但确实该联系某人"的场景，首版接受（编造处理人的代价更高）；
    引入 `Clock` 多一个注入点，S2 需明确其**时区口径**（与 §4.1 同源）。
- **关联**：`docs/AGENT-PLAN.md` §11（裁决）、§4.2（取消延迟上界）、§3.1（建议前提分级）、附 D；D74–D77

> **追加引用块（2026-10-06 真供应商实测后补，D78 原文不删）**
>
> - **原以为**：assistant 消息回填只保留白名单字段（`role` / `content` / `tool_calls`）更安全——
>   "白名单只会**减少**出网字段，方向上是更安全"，代价只是"某家供应商若靠扩展字段维持上下文会丢"（当时无证据）。
> - **后来发现（真 key 实测，`deepseek-flash` + thinking 模式）**：**正好相反**。
>   带 `reasoning_content` 回填 → **200**；白名单把它删掉 → **400**，原文
>   `The reasoning_content in the thinking mode must be passed back to the API.`。
>   也就是说：白名单不是"更安全"，而是让**每轮多步调查在第二轮必然 400**——它把一个**能跑通**的协议改坏了。
> - **因此改为**：默认**保真回填**供应商返回的 assistant message，只移除**经过实测**会引发 400 的字段
>   （`HttpAgentModel.ECHO_DENYLIST`，**当前空集**）；`ModelTurn.echoMessage` 的语义改为"保真回填用的消息"。
>   **白名单这条裁决作废，其余三条（工具调用上下文 / 取消 / 建议前提分级）不受影响**；
>   §10 的"报告校验失败要用配对 `toolMessage` 回填"**没有被推翻**（那是缺口清单的配对问题，不是字段多少的问题）。
> - **代价（修正后）**：原样回填会带上供应商的扩展字段——**这正是当前模型要求的**；
>   新风险是"换一家供应商可能收到它不认识的字段"，处置是**按模型重跑**
>   [`scripts/agent-provider-probe.ps1`](../scripts/agent-provider-probe.ps1) 再加 denylist，
>   **不按供应商推广结论**。
> - **与本次修正同批的还有**：§6.1 的"对外表述边界"把"真供应商兼容性未验证"改成**已实测**（含模型名与复跑脚本）。

## D79 · 调查助手：授权口径（ToolContext 的取值时点 / 判定方 / 拒绝方式 / 硬约束）

- **日期**：2026-10-06
- **问题**：`ToolContext` 在 S2 会成为工具**唯一**的身份来源（工具被禁止读 Sa-Token / `RequestContextHolder`，D78），
  那这四个字段**谁在什么时候填、填错了谁拦**？
- **备选项**：取值时点 ① 执行时现取（消费 / 调度线程从会话取）；② 受理时快照；③ 执行时按 `investigationId` 回查库。
  判定方 ① agent 侧自己判 `isAdmin()`；② 受理层用与业务接口同一套权限判定。
  拒绝方式 ① 新开失败码 `INVALID_TOOL_CONTEXT`；② 构造期不变量
- **选择**：② + ② + ②
  - **取值时点**：`ToolContext` 在**受理调查的 Web 请求**里**一次性快照、之后不可变**；执行侧不重新取值。
  - **判定方**：`canSeeAllDepts` 由**受理层**用与业务接口**同一套**权限判定得出（与"管理员可见全部"同源）；
    agent 侧**不得**另写 `isAdmin()` 之类的本地判断。
  - **拒绝方式**：**构造期不变量**（`IllegalArgumentException`），**不新开失败码**；紧凑构造器拒绝
    `null` / 空白 / 缺部门（标识字段首版按不透明字符串处理，类型属实现细节，不变量不得放宽）。
  - **硬约束**：**禁止 `canSeeAllDepts=false` 与 `callerDeptId` 为空同时出现**——否则"按部门过滤"会退化成
    "没有过滤条件"（= 越权）；**要么有部门，要么显式允许跨部门**。
- **理由**：备选①（执行时现取）不是"更好或更差"，而是**根本做不到**——S4 的调查跑在消费 / 调度线程，
  那里没有 Sa-Token 会话也没有请求上下文（D78 同一条理由）；备选③（回查库）把一次授权判定变成一次执行期查询，
  而且回查时权限可能已经变过——那不是"更实时"，而是**没有受理依据**。
  判定方必须与业务接口同源：两套独立判定迟早漂移，漂移方向总是"一边放宽"。
  拒绝方式选构造期而不是新失败码：**越权的形状要在对象造出来之前就消失**，
  这比"造出来、跑到某处再判失败"强一个数量级；而且"拒绝 null / 空白 / 缺部门"本身就是参数不合法，
  复用 `IllegalArgumentException` 的语义最直白（新开失败码要一路加进状态机与文案，收益为零）。
- **代价**：
  ① **快照会过时**：受理之后调岗 / 撤权，本次调查仍按旧权限跑完。**这正是 §3.3 的 `PERMISSION_REVOKED`（S4）
     覆盖的场景**；S2 只承诺"受理时正确"，不承诺"执行期间实时"——该边界同写在 §3.3 与 §11-2，
     防止被读成"已经实时"。
  ② 授权正确性**全部压到受理层那一次判定**上（工具侧没有第二道校验）：受理层判错，工具照单全收。
     这是"单一真源"的必然代价，S2 必须验"受理层判定与业务接口同源"。
  ③ 标识字段的类型（字符串 / 数值）被降级为**实现细节**：契约只钉不变量，不钉类型；代价是 S2 定类型时
     要想清与 `t_user.id` / 部门的对应关系（写错会在日志与审计串联上暴露）。
- **关联**：`docs/AGENT-PLAN.md` §11-2（形状与授权口径）、§3.3（`PERMISSION_REVOKED` 旁的承诺边界句）、D78

> **已被 2026-10-06 的 §1 收口取代（C2）**：`canSeeAllDepts` 字段**已从代码与契约中删除**。
> §1 第二题裁定"统一只调查本部门用户提交的工单，`ESCALATED_ADMIN` 升级状态不扩大可见范围"，
> 因此 `ToolContext` 只保留 `investigationId` / `callerUserId` / `callerDeptId` 三个字段，
> `callerDeptId` 是**唯一的范围载体**且构造期必须非空。
> 本条目里"判定方＝受理层判定 `canSeeAllDepts`"与"禁止 `canSeeAllDepts=false` 与 `callerDeptId` 为空同时出现"
> 这两条**不再适用**（后者的问题已被"缺部门即构造失败"吞掉）。原文不删，留作当时的推理记录；新口径见 D81。

## D80 · 日志可见性 = 工单可见性（同源），不新增日志权限码

- **日期**：2026-10-06
- **问题**：`GET /api/orders/{id}/logs` 是越权缺口——任何登录用户都能读任意工单的日志（含操作人姓名与备注）。
  修法选哪一种？
- **备选项**：① 给端点加一个新的权限码（如 `order:log:view`）+ 角色绑定；② 复用详情的**数据级**可见性规则
  （角色 / 归属 / 部门 / 升级单），新增带调用者身份的日志入口；③ 端点不动，只在日志 SQL 里加归属条件
- **选择**：② —— 新增 `WorkOrderService.getOrderLogs(orderId, currentUserId)`，内部走与 `getOrderDetail`
  **同一个** `requireVisibleOrder(...)`；控制器读会话身份后委托调用。
- **理由**：
  ① 权限码回答的是"能不能用这个功能"（功能级），而这里要挡的是"能不能看**这一张**单"（数据级）：
  `t_permission` 现有的 `order:*` / `order:accept` / `order:assign` / … 里没有任何日志相关码，
  新造一个码也答不了数据级问题，还要给 4 个角色逐个绑——绑错就是"全挡"或"全放"。
  ③（只在 SQL 里加条件）做不到：这条规则依赖角色、部门、升级单状态，不是一条 `WHERE` 能表达的；
  而且它会与 `getOrderDetail` 形成**两段雷同代码**，将来必然漂移。
  ② 把两处判定收进**同一个方法**，让"同源"是结构事实而不是巧合——改一处即两处生效，缺口不会再悄悄回来。
- **代价**：
  ① `getOrderLogs` 比原来多两次查询（工单 + 角色），与 `getOrderDetail` 同量级；
  ② 拒绝对外表现为 `BizException(FORBIDDEN)` → 沿用 `GlobalExceptionHandler` 的既有约定
  （HTTP 200 + body `code=403`），因此**判据必须落在业务 code 上**，不能看 HTTP 状态码；
  ③ `WorkOrderLogService.queryLogs(orderId)` 仍然不带身份，**只能在已通过可见性校验的调用链里使用**
  （接口 javadoc 已注明）。这道约束是"约定"而非强制——要强制就得把身份下沉到日志服务，
  会牵动 `WorkOrderServiceImpl` 与既有测试的调用点，首版不做，留作后续可选项。
- **关联**：`docs/AGENT-PLAN.md` §2.3 第 3 项（已修复）；`WorkOrderServiceImpl.requireVisibleOrder`；用例 `OrderLogVisibilityTest`

## D81 · §1 补录后的契约收口：C1（不给原因）／C2（删跨部门字段）／C3（预算标"待实测"）

- **日期**：2026-10-06
- **问题**：§1 八项业务选择补录（来源 `GRILL-DECISIONS.md` 等四份，见 `docs/AGENT-PLAN.md` §1）后，
  与现行契约暴露三处冲突（C1/C2/C3），怎么收口？
- **备选项**：
  - C1：① 保留 `TIMEOUT_REASON` 只改措辞；② 改类型名与判据、**事实键不动**；③ 连事实键一起重命名
  - C2：① 保留 `canSeeAllDepts` 但约定恒 `false`；② 保留字段并在执行边界拒绝 `true`；③ **删字段**，只留部门
  - C3：① 采用 `AGENT-DESIGN.md` 的更紧值；② **保留 §3.4 现值并标"待实测"**；③ 两套并存
- **选择**：C1 ②、C2 ③、C3 ②
- **理由**：
  - **C1**：§1 第四题的约束落在**输出与完成判据**上——"不给未经证实的原因假设"。类型名本身证明不了行为违规，
    所以既不能只改名字了事（①），也不必连事实键一起改（③：事实键回答"证据是什么"，与是否给原因无关）。
    ② 的实质是**把约束写进描述与前提**：`TIMEOUT_SITUATION` 只交付已核实事实、证据缺口与核实建议；
    `sla_deadline` 未登记或未过期时不得断言"已超时"。
  - **C2**："靠调用方传 `false`"（①）是**约定不是边界**，而 §1 之后根本不存在"跨部门"这个口子，
    留着字段只会让人以为将来能开。删字段（③）让"缺部门"在**构造期**就失败——"没有过滤条件"这个越权形状
    **造不出来**，与 D79 的构造期不变量同一思路。② 虽能拦住 `true`，但仍留着一个语义上不该存在的开关。
  - **C3**：设计稿的更紧值（动态工具 ≤4 次、响应 64 KiB）**更小不天然更合理**，收紧前必须证明
    **合法调查路径仍能跑完**；而目前唯一有测试证据的一组是 §3.4 的现值（38 条 agent 用例覆盖）。
    故取 ②：现值生效并标"待实测"，设计稿那组登记为"待验证候选"。
- **代价**：
  - C1：枚举常量改名会改变模型可输出的 `problemType` 取值，旧值不再合法（S1 无历史数据，代价为零）；
    且"不给原因"目前**没有机器判据**——报告只有编号、没有自由文本，所以它靠"没有承载原因的位置"
    加描述与前提约束；S2 接真实工具时要补一条用例把它钉住。
  - C2：`ToolContext` 四字段 → 三字段，构造器签名变化，调用点与用例必须同步改
    （本轮已改：agent 用例 39 → 38）；将来 §1 若允许跨部门，必须走 D 条目显式扩宽，不能顺手加 flag。
  - C3：**参数仍未实测**——本轮只是把"哪一组生效"定死并标注清楚；S2/S3 必须实测后决定收紧或放宽，
    否则这组值会以"已定稿"的面目被当成校准过的参数。
- **关联**：`docs/AGENT-PLAN.md` §1.1（三条裁决）、§3.1（C1）、§11-2（C2）、§3.4（C3）；D76（预算口径）、D78（终止协议）、D79（授权口径，部分被取代）

## D82 · §3.1 按数据能力对齐：NULL 的 SLA 是已知事实 / alert_count 移出必需 / order.exists=false 收缩必需事实

- **日期**：2026-10-06
- **问题**：`docs/S2-TOOL-DATA-MAP.md`（v2）把事实来源逐条落到了 `文件:行` 之后，暴露出三处"契约与数据能力不符"，
  要不要改、怎么改？
- **备选项**：
  - ① NULL 的 `sla_deadline`：a) 保持"给值 + 标未知"（现状，自相矛盾）；b) **只给值、不标未知**；c) 保持 NULL 不返回该事实
  - ② `order.alert_count`：a) 保持必需；b) **移出必需、保留在允许未知**；c) 从契约里彻底删除
  - ③ `order.exists=false`：a) 保持"必需事实不变"（永远判未完成）；b) **必需事实收缩为 `{order.exists}`**；c) 单独开一类"工单不存在"
- **选择**：① b、② b、③ b。
- **理由**：
  - ① **NULL 在数据上是"无 SLA 截止"这个已知事实**（映射表空值语义：`t_work_order.sla_deadline` 允许 NULL，
    且提交链路会按 `t_sla_config` 兜底计算）——与 `order.accept_events` 为空走 `emptyFacts` 是同一口径。
    同时给值又标未知是自相矛盾：`unknown` 不在 `TIMEOUT_SITUATION` 的允许未知里 → 该事实被引用就会撞
    validateReport 的"该类型不允许未知"分支 → **"无 SLA 的单"永远判未完成**（本轮用红用例实测）。
  - ② `alert_count` 在库里**没有列**（映射表 §1 已证），只能靠 `t_notification.title` 近似 → **不配当必需事实**；
    但**必须保留在允许未知**：工具为了不编造会返回显式未知（`ToolContext` 口径下的唯一诚实的做法），
    若同时从允许未知里删掉，这份显式未知一旦被引用就会撞同一条分支——两种合法做法（返回 / 不返回）都会被判死。
  - ③ `{order.exists=false}` 时其余必需事实**在物理上不存在**（没有状态、没有处理人）→ 保持原必需集等于
    让"工单不存在"这类问题**永远无法完成**；而"工单不存在"本身就是一个明确结论。收缩实现为
    **按证据的 `fact` + `value` 判定**（不新增字段、不改协议），只在 `validateReport` 一处生效。
- **代价**：
  - ① 代价：把 NULL 当已知事实，意味着**NULL 与"真的没有配置"无法再区分**（I4/D10 记过 NULL 也可能是兜底失败的历史残留）；
    首版接受，靠 I4 的启动自检 + 兜底配置把这种残留压到最低。
  - ② 代价：`TIMEOUT_SITUATION` 的质量下限被放宽了一格——一份"只交付 exists/status/sla"的报告也算完成，
    即使它没有任何告警依据；换来的是"工具无法提供的事实不再是完成的硬门槛"。补真实计数源（或从契约删该事实）仍是待办。
  - ③ 代价：`order.exists=false` 的判定依赖**证据的字符串值 `"false"`**（工具约定），若将来工具改用布尔或其他表示，
    这条规则要同步（当前由 `OrderFactsTool` 与 `StubOrderSnapshotTool` 两处共同保证）。
- **关联**：`docs/AGENT-PLAN.md` §3.1（表格 + 条件必需事实 + 空值语义两条）、§11-4（按数据能力对齐的两处）、
  §1.2（原件归档）；`docs/S2-TOOL-DATA-MAP.md` §1/§3；`OrderFactsTool` / `InvestigationAgent.validateReport`；D81（C1/C2/C3，原文不动）

## D83 · "空值 = 已知事实；未知只表示查不到"（口径不对称修正 + allowedUnknown 清理）

- **日期**：2026-10-06
- **问题**：同样的"没有值"，工具在三处给了三种语义（`assignee_id=NULL` 标 unknown、`sla_deadline=NULL` 只给值、
  `accept_events` 为空走 `emptyFacts`），于是 §3.1 的 allowedUnknown 与实际可达的未知**不对称**：
  未分配的单在 `TIMEOUT_SITUATION` 下**引用 assignee 证据就判 `REPORT_INVALID`**（而"为什么没人接"正是该类型的招牌场景）；
  另 `ORDER_STATUS` 的 `order.sla_deadline` 已是**死条目**（工具永不标它未知）。
- **备选项**：① 逐个类型补 allowedUnknown（治标：仍允许"已知值 + 未知"同时出现）；② **统一口径**——空值一律"已知值"
  或 `emptyFacts`，`unknown` 只表示"查不到 / 无来源"，并据此清理 allowedUnknown；③ 反过来把空值一律标 unknown
  （会被允许未知矩阵反复拦住，每加一个类型要补两次）
- **选择**：②
- **理由**：①③ 都是"让判据追着实现跑"——每加一个事实就要在三个类型里各判断一次；② 让**语义由数据形态决定**
  （列里有值 / 列为空 / 记录不存在，各有明确呈现），于是 allowedUnknown 只需保留**真有未知来源**的事实
  （`assignee` 有 id 查不到用户、`alert_count` 无来源）。这是同一个混淆在本项目的**第三次显形**
  （前两次：triage 的"读超时"被当成"判成其他"、`accept_events` 为空被写成未知）——统一口径比逐处补丁更省。
- **后果（本轮落地）**：
  - `OrderFactsTool`：`assignee_id=NULL` → 值"未分配"、**不标 unknown**；`assignee_id` 有值但 `t_user` 无该行 →
    值"已分配（显示名查不到：t_user 无该行）" + **标 unknown**；同时删掉原先的占位假值"已分配（显示名不可得）"
    ——它把"查不到"伪装成"查到了"；
  - `AgentProblemType`：`ORDER_STATUS.allowedUnknownFacts` 删除 `order.sla_deadline`（死条目）；
    `TIMEOUT_SITUATION.allowedUnknownFacts` 加入 `order.assignee`；`order.alert_count` 两处保留；
  - §3.1 表格同步 + 新增通则"一个事实不能同时是已知值与未知"。
- **代价**：
  - ① "没有值"在不同事实上的**呈现**仍不统一（"未分配" / "无 SLA" / `emptyFacts` 三种形态），统一的只是**判据**；
    读契约的人要表格与通则一起看。
  - ② 删掉 `ORDER_STATUS` 的 sla_deadline 未知条目后，若将来某个工具**真的**查不到 SLA（如外部数据源故障），
    会撞"该类型不允许未知"——那时应在**确有来源**的前提下重新加回，而不是现在预留着当摆设。
  - ③ "有 id 查不到用户"是新认定的**真正未知**：要求链路上不要静默吞掉 `t_user` 缺失；当前选择**显式标未知**
    而不抛异常，代价是报告里会出现"查不到显示名"这类表述。
- **关联**：`docs/AGENT-PLAN.md` §3.1（两行 allowedUnknown + 通则）；`OrderFactsTool`（`maskedDisplayNameOrNull` / `renderOperator`）；
  D82（同族的空值语义对齐）、D77（外发白名单：只给脱敏显示名）、D68（未分配 ≠ 有处理人）
## D84 · 重复调用收敛：本轮快照缓存 + 非法批次不执行 + NO_PROGRESS 以「连续两轮」为门槛

- **日期**：2026-10-06
- **问题**：模型重复调同一工具同一参数时，循环会**再执行一次、再登记一套证据编号**——预算照计但没有任何收敛；
  设计稿要求的快照缓存与 `NO_PROGRESS` 终止都没实现。重复到什么程度该终止？
- **备选项**：缓存范围 ① 不缓存（现状）；② **本轮快照缓存**（键 = 工具名 + 规范化参数，仅存活于本次调查）；③ 跨调查缓存/落库。
  终止门槛 ① 一轮内重复即终止；② **连续两轮零新证据**；③ 连续三轮零新证据。
- **选择**：② + ②。
- **理由**：
  - 缓存选②：设计稿 L133 只要求"本轮快照"；跨调查缓存会引出"数据变了但缓存还在"的新鲜度问题（§5 尚未实现），本轮不做。
    **规范化**（对象键序不影响同一性、数组顺序保留）是"语义相同即同一调用"的唯一可执行判据。
  - 门槛选②（"两轮"）：设计稿只写"连续重复且无新证据"，没钉死轮数。选"两轮"= **给模型一次自我纠正的机会**
    （第一轮零新证据可能只是它先去看了一眼缓存过的东西），而"一轮内重复即终止"会把合法的"先探一下再决定"误杀；
    选"三轮"则多花一轮预算在明显没有推进的调查上。
- **代价**：
  - ① 缓存命中**仍计工具预算**（§3.4）——重复调用会白烧预算，只是不再重复执行工具；**这是刻意的**：否则"重复调用"就成了绕过预算的免费通道。
  - ② "两轮"意味着**三连重复至少多花一轮**（第三轮才被 NO_PROGRESS 收尾）；换取的是一轮内的自我纠正机会。
  - ③ 非法批次（同 `callId` 参数不一致）**整轮不执行**——其中"看起来合法"的调用也被牺牲，这是设计稿 L137 的要求
    （宁可整轮丢弃，也不执行一个可能错位的调用）；代价是模型必须重发整轮。
  - ④ 缓存只在进程内存里、只属于本次调查：**重启即丢**（与 §3.4 的进程内计数同一性质），不做分布式缓存。
- **关联**：`docs/AGENT-PLAN.md` §3.3（NO_PROGRESS 原因码）、§3.4（缓存命中仍计预算）、§3.2（证据编号）；
  `docs/agent-design/AGENT-DESIGN.md` L133/L137；`InvestigationAgent`（snapshotCache / hasConflictingCallIds / staleRounds）；
  用例 `RepeatedCallConvergenceTest`（5 条）

## D85 · 部门范围的**准入条件**同源：`resolveDepartmentScope` 单一真源（DEPT_ADMIN only；SYS_ADMIN 待裁决）

- **日期**：2026-10-06
- **问题**：受理层构造 `ToolContext` 时只调用 `WorkOrderService.callerDeptId(userId)`——它只看
  `user.getDeptId()`、**不看角色**。于是**任何有部门的普通用户**都能让助手读到**本部门所有人的工单**；
  而在列表接口里同一用户只能看到自己（`applyRoleFilters` 的 `!hasFilter` 兜底）。D79 要求"判定方同源"，
  当时只共用了解析函数、**没共用准入条件**。
- **备选项**：① 受理层再写一段角色判断（复制一份准入）；② **抽一个共享判定
  `resolveDepartmentScope(userId)`，由 `applyRoleFilters` 与受理层共同调用**；③ 去掉受理层的部门范围，
  工具只按 `callerUserId` 过滤（放弃部门范围）。
- **选择**：②
- **理由**：
  - ① 正是 D79 禁止的"两处判定"——复制品迟早漂移，而漂移方向总是放宽（本次缺陷就是实例）。
  - ③ 会让"部门主管看本部门"（§1 第一题）这条能力消失，属改需求。
  - 准入规则按 §1 第一题（助手面向部门主管）：`DEPT_ADMIN` 且部门非空 → `DEPARTMENT(deptId)`；
    其它角色（含 `SYS_ADMIN`）→ `NONE`。**`SYS_ADMIN` 是否可用登记为待裁决**，本轮不放行。
- **代价**：
  - ① `resolveDepartmentScope(userId)` 需要角色，会多一次 `getRoleCodes` 查询；列表路径走
    `resolveDepartmentScope(roles, userId)` 重载复用已解析的 roles，避免重复查（受理层走 `userId` 版）。
  - ② **`SYS_ADMIN` 本轮用不了助手**——这是刻意的保守：§1 第一题只写"面向部门主管"，超管可用与否没有依据；
    放行要显式裁决，不放行只是少一个入口，不是缺陷。
  - ③ 列表语义**保持逐行等价**（仍保留 `SYS_ADMIN` 绕过与 `!hasFilter` 兜底）——本次提取是为了单一真源，
    不是改列表行为；等价性由 `AgentInvestigationWiringTest` 的两条列表回归断言（普通用户只见自己 /
    `SYS_ADMIN` 仍绕过）钉住，另有 `listOrders` 的绕过分支（L491-493）为**未改动**代码作内容层证据。
- **关联**：`docs/AGENT-PLAN.md` §11-2（准入条件）；D79（授权口径）；`WorkOrderServiceImpl.resolveDepartmentScope`；
  `AgentInvestigationService`（`NONE` → `FAILED / FORBIDDEN`）；用例 `AgentInvestigationWiringTest`
  （普通提交人 / `SYS_ADMIN` 越权 + 两条列表回归）

## D86 · 状态模型对齐：`FORBIDDEN` 入表 + 新增 `INCOMPLETE` 终态 + `STATE_CHANGED` 取代 `STALE_EVIDENCE`

- **日期**：2026-10-06
- **问题**：两个缺口。① `FORBIDDEN` **真产生却没入 §3.3 表**——受理层非主管拒绝
  （`AgentInvestigationService`）与两个工具的跨部门拒绝（`OrderFactsTool` / `DeptComparisonTool`）都产出它，
  表里没有 = "产生了但查不到"。② 执行状态**缺 `INCOMPLETE`**：设计稿 L182 明写三值
  COMPLETED / INCOMPLETE / FAILED，`AGENT-LEARNING-EVAL.md` L278 也要求"只能给经复核的部分事实并标 INCOMPLETE"；
  而"运行中状态变化"只登记了一个 `STALE_EVIDENCE`，对外矩阵（§5.1 槽24）用的却是 `STATE_CHANGED`。
- **备选项**：① `FORBIDDEN` 换成表内的码（如复用 `PERMISSION_REVOKED`）而不是入表；
  ② 为"部分事实"新增第二种报告形态（"部分报告"）；③ 保留 `FAILED(STALE_EVIDENCE)` 不动。
- **选择**：`FORBIDDEN` **入表**（不换码）；新增 **`INCOMPLETE`** 终态（沿用 `report == null` + 原因码不变量）；
  **`STATE_CHANGED` 取代 `STALE_EVIDENCE`**（对外口径以矩阵为准），旧名保留一行并标注。
- **理由**：
  - ① 换码会丢信息：`FORBIDDEN`（调用者不在数据范围）与 `PERMISSION_REVOKED`（受理后被撤权）是两回事。
    且这与当初删 `NOT_SUPPORTED`（**永远不产生**）是同一问题的两面——**产生了却查不到**一样会让下一个人误判。
  - ② 新增"部分报告"会绕过 §3.2 的不变量（"失败不得被伪装成正常报告"）：只要有第二形态，
    就总能找到一条把未完成洗成正常结果的路径。**已核实的事实走 `evidence` 就够了**。
  - ③ 矩阵 §5.1 槽24 是**对外口径**（评测任务集已落档），**表向矩阵看齐，不是矩阵向表看齐**。
- **代价**：
  - ① **新增终态会影响所有 switch / 枚举遍历点**：`AgentStatus` 多一个值，任何穷尽 `switch` 都要补分支。
    当前实现对 `AgentStatus` 没有穷尽 `switch`（本次编译期无破坏），但这是以后每加一个码都要付的成本。
  - ② `FORBIDDEN` 入表后，§3.3 的"首版全集"**不再是闭集**——措辞已同步改为"首版可用集合；**不是闭集**"。
  - ③ `INCOMPLETE` 目前**没有生产者**：能产生它的是 S4 的状态变化 / 预算中止路径。
    本片只落"契约 + 渲染能力"（`AgentReportRenderer.renderIncomplete`），受理层接线留到 S4。
  - ④ 表里多留一行旧名条目（`STALE_EVIDENCE`）——换来的是"旧码可查、不假装它从没出现过"。
- **关联**：`docs/AGENT-PLAN.md` §3.2（不可绕过，加 `INCOMPLETE`）、§3.3（状态表 + 原因码表）；
  `docs/agent-design/AGENT-DESIGN.md` L182；`docs/agent-design/AGENT-LEARNING-EVAL.md` L278 与 §5.1 槽24；
  `AgentStatus` / `AgentRunResult.incomplete` / `AgentReportRenderer.renderIncomplete`；
  用例 `AgentStateModelContractTest`（3 条）

## D87 · 两个只读工具补齐（`read_earlier_events` / `read_sla_context`）+ `sla.scan_applicable` 进入 TIMEOUT_SITUATION 的允许未知

- **日期**：2026-10-06
- **问题**：设计稿 L84–L87 定了四个工具，实现里只有两个（`get_order_facts` / `query_dept_peer_orders`）。
  后果正好卡在评测的两槽上：① §5.1 槽 06（"最近日志截断，关键事件在早期页"）**没有物理前提**——分页工具不存在；
  ② 槽 07（"区分当前规则、存储事实与历史未知"）**没有"当前规则值"这个来源**。两槽因此在冻结集里只能标"待定"。
- **备选项**：① 继续把两槽留在"待定"；② **补两个工具**（严格照设计稿的输入与约束）+ 补两槽的期望；
  ③ 把两槽从矩阵里删掉（不可——矩阵是对外口径，只能补不能删）。
- **选择**：②
- **理由**：槽位是**对外承诺的覆盖槽**（§5.1），不是可选项；两个工具的输入 / 约束在设计稿里已经写死
  （L85：`orderRef + cursor`、一页 ≤20、游标绑定本轮 + 工单 + 边界；L87：存储截止点 / 查询时刻 / 是否过点 /
  扫描状态是否适用 / 当前规则值，只读、不重算、不升级、不告警），照做即可，不需要再裁一次。
- **代价**：
  - ① **游标是本模块新引入的状态**：绑定到 `investigationId + 工单 + 边界`（8 位十六进制摘要），
    所以**游标只在本次调查内有效**——跨调查复用被拒（这正是 L85 要的），代价是"用户重发一次调查"就翻不了上一轮的页。
  - ② `sla.scan_applicable` 现在是**真未知**（库里没有扫描状态列）。把它列进 `TIMEOUT_SITUATION` 的
    `allowedUnknownFacts`，只解决"引用该未知不被判报告非法"，**不代表我们有扫描状态**；
    将来若补了该列，这条要按"有来源的已知值"重写。
  - ③ 工具从 2 个变 4 个 → 模型可见的 `tools` 数组变长、决策空间变大（S6 对照时两边工具集必须一致）。
  - ④ 日志行渲染抽成 `LogLines`（`OrderFactsTool` 与 `read_earlier_events` 共用）：多一个包内类，
    换来"同一事实键在不同工具里同形状"。
- **关联**：`docs/AGENT-PLAN.md` §3.2（工具清单）、§3.1（TIMEOUT_SITUATION 的允许未知）；
  `docs/agent-design/AGENT-DESIGN.md` L85 / L87；`docs/agent-eval/README.md` §6 / §9（成熟度 16 → 18）；
  `ReadEarlierEventsTool` / `ReadSlaContextTool` / `LogLines`；
  用例 `ReadEarlierEventsToolTest`（5 条）/ `ReadSlaContextToolTest`（5 条）

## D88 · 关系查询按设计稿收敛：**一个工具 + `relation`**、两条关系锚不同、状态集合与 `NOT_APPLICABLE`、页大小与"不报总数"

- **日期**：2026-10-06
- **问题**：设计稿 L86 把"对照查询"定义成**一张表的一行**（`find_related_orders`：`relation` + 可选 cursor），
  L91 / L92 定了两种关系；而实现只做了第一种，且口径比设计**更宽**：
  状态集合是"去掉终态"（会把 `ESCALATED_ADMIN` 算进"进行中"）、无处理人返回 **0**、
  一页 20 条且**还报总数**（L94 明写"不为了展示总数扫描全库"）。
- **备选项**：
  ① **一个工具 + `relation` 参数**（照设计稿的一张表）；
  ② 拆成两个工具（各自一个关系）；
  ③ 保持现状（只做第一种关系、口径不动）。
- **选择**：①
- **理由**：
  - ② 会让 §3.2 的工具数与设计稿的工具表（4 行）对不上——"表向设计看齐"是本轮的前提，不是偏好；
    且两条关系**共用同一套锚定与授权**（锚定主单、部门范围、取页出页），拆开等于把同一段逻辑复制两份。
  - ③ 的问题不是"少一个功能"，而是**口径比设计宽**：更宽的集合会把不该算的单算进"对照"，
    而报告读起来仍是"本部门可见范围内 N 张"——**读者看不出差别**。
  - 三处修正都有可直接引用的依据：状态集合 = L91（恰好 `ACCEPTED` / `IN_PROGRESS`）；
    无处理人 = L91（`NOT_APPLICABLE`）；页大小与"不报总数" = L86（最多 10 条）+ L94（多取 1 条判 hasMore、不扫全库）。
  - `RECENT_DAYS = 30` 取 L92 的**初始建议**，实现里是具名常量并标"待实测"；L92 同时明写它**不是相似语义检索**。
- **代价**：
  - ① **两条关系的"锚"不同，而授权载体只有部门范围一个**：关系①锚**处理人**、关系②锚**提交人**。
    这带来一个真实风险——如果关系②不自己保证"只查主单提交人的单"，`relation` 参数就会退化成
    **"按任意人查"的后门**（部门范围内任意人的工单都能被列出来）。处置：锚点只从**主单**取
    （`start.getSubmitterId()` / `start.getAssigneeId()`），入参只有 `orderNo` + `relation`，
    多传 `assigneeId` / `userId` / `deptId` / SQL 片段**一律无效**（有用例钉住"多传参数结果不变"）。
  - ② 页大小 20 → 10 改变了"本页"的含义；旧的"报总数"写法一并删掉（否则仍是"扫全库"）。
    代价是**报告里再也不能说"总共 N 张"**，只能说"本页 N 张、有没有更多"。
  - ③ **这三处修正，开发集测不出来**：12 条开发集的夹具里，每条主单最多只有 1 张对照单、
    没有 `ESCALATED_ADMIN` 的对照单、也没有"无处理人还去查对照"的用例——所以 v3 → v4 的 dev 数字
    **完全没变**（12/12、越权 0、工具调用 16）。**结论：12 条开发集不足以覆盖关系层的边界**，
    这些边界只能靠新增的单元用例覆盖（`DeptComparisonToolTest` 6 → 11 条），
    **不能**用"dev 数字没掉"当作"改对了"的证据。
  - ④ 关系②的时间窗口依赖**可注入时钟**（constructor 传 `Clock`）：代价是多一个构造参数，
    换来"近 30 天"可复现（否则该关系不可测）。
- **关联**：`docs/AGENT-PLAN.md` §3.2（工具清单 + "查不到类事实必须同步进 `allowedUnknownFacts`"的检查项）；
  `docs/agent-design/AGENT-DESIGN.md` L86 / L91 / L92 / L94；
  `docs/agent-eval/baseline-dev-results-v4.md`（dev 数字未变的原因写在这里）；
  `docs/agent-eval/README.md` §6 / §9（成熟度 18 → 19）；
  `DeptComparisonTool`（`RELATION_*` / `MAX_RELATED_ORDERS=10` / `RECENT_DAYS=30` / `NOT_APPLICABLE`）；
  用例 `DeptComparisonToolTest`（11 条）

## D89 · 超时链按真供应商实测校准（样本 6 次调查 / 17 次模型调用）

- **日期**：2026-10-06
- **问题**：`docs/AGENT-PLAN.md` §4.1 的四档（运行 60s / Servlet 65s / 代理 75s / 前端 80s）与
  `llm.api.timeout=15000`（`application.yml:148`，**分诊链路**的旋钮）都标着"待测初值"；
  而端到端实测已经出现过 18.9s，前端 Axios 却是 15s（`frontend/src/utils/request.ts:22`）。**够不够？怎么改？**
- **样本**（真供应商 `deepseek-flash` + 本机库；同一套夹具；两种问题类型各 3 次；**6 次调查 / 17 次模型调用**；
  原始记录 [`docs/agent-eval/timeout-calibration-20261006.md`](agent-eval/timeout-calibration-20261006.md)）：
  - **单次模型调用**：n=17，min **1149** / median **2614** / max **26099** ms
  - **端到端**：n=6，min **3853** / median **10961** / max **29597** ms
  - 每次调查的模型调用数：n=6，min 2 / median 2 / max 4；**6 次全部 `COMPLETED`、无 4xx/5xx**
- **选择**：
  1. 调查运行预算 **60s 不变**（端到端 max 29.6s ≈ 2.0× 余量）；
  2. agent 侧单轮读超时（`AgentLimits.modelReadTimeout`）**30s → 45s**；
  3. `llm.api.timeout`（**分诊链路**）**本轮不改**，登记"待分诊链路自测"；
  4. Servlet **65s（新配）**；Nginx 代理 **60s → 75s**；前端调查接口 **15000ms → 90s**。
  **以上四条本轮只定参数，不改代码/配置**（落地另起一轮；落地前 §4.1 已显式标注"参数已定、实现未改"，
  避免以文档为准的人读到与代码不一致的值）。
- **理由**：
  - **45s**：单次 max 26.1s 距 30s 只剩 3.9s（≈15%）；45s ≈ max×1.7。且它**仍被"本轮剩余预算"收敛**，
    所以放大它**不会**放大整次调查的墙钟——放大的是"单次调用不被误杀"的机会。
  - **代理必须改**：现值 `proxy_read_timeout 60s`（`deploy/nginx.conf:26`）**≤ 运行预算 60s**——
    跑到预算上限的调查会被代理**先**切断（服务端还在跑、客户端已经断了）。这是**包络本身写错**，不是余量问题。
  - **前端必须改**：端到端 max 29.6s 已是现值 15s 的**约 2 倍**；同步接口下前端必然先断（用户看到失败，服务端仍在跑）。
  - **`llm.api.timeout` 不动**：这 6 条样本来自**调查**链路，直接搬到**分诊**链路，就是拿一个工作负载的数字
    去定另一个的闸门——本项目反复踩的那类坑（D68/D69 一族）。分诊要用自己的样本再定。
- **被否决的方案**：
  - ① **本轮就把前端改成异步 + 轮询**：需要任务状态存储 + 名额/取消语义，全在 S4；现在没有那两样东西的实证，
    改法更贵且**没法验证**。选"最小改动把包络做对"，异步留给 S4。
  - ② **按 max 定值**（例如把运行预算抬到 90s）：6 条样本的 max **不是分布上界**，按 max 定只会把"最坏等待"一起放大。
  - ③ **按中位数缩小运行预算**（中位仅 11s）：那会把长尾调查直接判失败，与"样本小、不构成分布结论"直接矛盾。
- **代价与适用边界**：
  - 代价：单轮读超时放大 → **单次失败被感知得更晚**；§4.2 的"取消延迟上界 = 当前轮读超时"同步变成 **≤45s**；
    前端 90s 意味着最坏要等约 90s，且**客户端断开不等于服务端停止**（取消属 S4）。
  - 边界：结论只对 **`deepseek-flash` + 本机库 + 这套夹具**成立；**不是生产延迟数字**；**样本 6/17 条，不是分布结论**；
    换模型 / 换供应商必须重跑采样与探测（路径见 §6.1）。
- **关联**：`docs/AGENT-PLAN.md` §4.1（校准表）/ §4.2（取消上界同步）/ §6.1；
  `docs/agent-eval/timeout-calibration-20261006.md`；`frontend/src/utils/request.ts:22`；
  `deploy/nginx.conf:26`；`src/main/resources/application.yml:148`；`AgentLimits.s1Defaults`

> **追加引用块（2026-10-06，落地轮；原文不删）**：上一条里那句"**以上四条本轮只定参数，不改代码/配置**"
> 说的是**上一轮**（只测与定参数）的状态。本轮把能落地的落地了，逐条如下：
>
> | 档 | 落地情况 | 落点 |
> | --- | --- | --- |
> | 运行预算 60s | ✅ 已落地 | `AgentLimits.s1Defaults`（本来即 60s，只补依据注释） |
> | 单轮读超时 45s | ✅ 已落地 | `AgentLimits.s1Defaults`：30 → **45** |
> | Servlet 档 | ⊘ **不适用（无落点）** | 同步实现下 Tomcat **不切断进行中的响应**；该档只在 `Callable`/`DeferredResult` 下由 `spring.mvc.async.request-timeout` 生效——**不为填表配不生效的值**，S4 做异步时再定 |
> | 代理 75s | ✅ 已落地 | `deploy/nginx.conf` 新增 `location /api/agent/`（75s）；`/api/` 的 60s **未动** |
> | 前端 90s | ⏳ **待 UI** | 前端还没有调用点；有 UI 时按"只给调查接口单独配"落地 |
> | 前端 90s | ✅ **已落地（2026-10-07 更正；上一行原文保留）** | 落地轮：`frontend/src/api/agent.ts` **只给调查接口**配 90s（`AGENT_INVESTIGATION_TIMEOUT_MS`），全局 `frontend/src/utils/request.ts:22` 的 15000 **未动**；页面 `/agent/investigation` 仅 `DEPT_ADMIN` 可见/可达。**上一行"待 UI"作废**；包络理由（60s 预算先于 75s 代理）见 **D103**。**走查**：该页面已于 2026-10-07 **服务器真机逐站走查通过**（A 组 4 条 + B 组十站；见 `ASYNC-SCHEDULING-PLAN.md` §P7 结果区·第四轮与 **D105**） |
>
> 同一轮还落地了**同步调查接口** `POST /api/agent/investigations`（默认关；契约见 `AGENT-PLAN` §3.2），
> 并顺带修掉一处**相关缺陷**：`GlobalExceptionHandler` 的 catch-all 把"无匹配 handler"吞成 `200 + code=500`，
> 现单独映射为 **404**（否则"开关默认关"表现为"服务器内部错误"）。
> 本轮**未做**异步、任务状态存储与取消（S4 下一片）；holdout 仍一次未跑。

## D90 · 最终短读取复核（L213）：逐字段比对、`version` 不参与、复核成本暂不计入预算

- **日期**：2026-10-06
- **问题**：D86 把**状态模型**对齐了（新增 `INCOMPLETE` 终态、`STATE_CHANGED` 取代 `STALE_EVIDENCE`），
  但**生产者**一直没有——"关键证据在运行中变化"这件事没有任何代码会产生。设计稿 L213 要求：
  返回 `COMPLETED` 之前做一次最终短读取，核对主单关键字段、引用对照单、日志/页面指纹，
  **不能只核一个 version**。怎么落地既忠实又不过度？
- **选择**：
  1. 抽一个**共享协作者** `FinalReview`，agent 与基线**共用同一个实例**（生产由 `AgentConfiguration` 注入同一 bean）；
  2. 主单指纹 = **用同一个工具重跑** `get_order_facts`，与证据里登记的 fact 逐字段比对
     （渲染口径因此天然一致，不新增第二套格式化）；
  3. 对照单 = 重查证据里引用的单号是否仍在调用者部门可见范围内；
  4. 不一致 → `INCOMPLETE(STATE_CHANGED)`：`report == null`、`evidence` 保留、
     受理层用 `renderIncomplete(...)` 渲染（顶部"调查未完成（STATE_CHANGED）"，不带【结论】/【下一步核实建议】）；
  5. **`version` 不参与判定**（既不作为触发条件、也不作为"没变"的依据）。
- **理由**：
  - **共享协作者**：与 `EvidenceLedger` / `AgentReportValidator` 同一模式——两处各写一套比对，
    S6 的成对比较就不公平（比的是两套判据）。
  - **用同一个工具重跑**：直接读表再自己格式化，就会出现"复核显示的 assignee 与证据里的不一样"这类假变化；
    复用工具等于复用它的授权、脱敏与渲染。
  - **version 不参与**（设计稿原话："version 单独不够：`markTriageFailed` 不递增 version"）：
    它变=并发控制变了，不代表业务事实变；它不变=更不能说明业务事实没变。
    所以"只改 version"应当**仍然 `COMPLETED`**（有用例钉住，防止把复核做成"动不动就 INCOMPLETE"）。
  - **不放进指纹**：查询时刻/耗时/动态超时状态（§5：恒定误报）；`order.logs_page` 这类游标切片（相对量）。
- **代价与适用边界**：
  - ① **复核成本暂不计入 `toolCalls`**（设计稿 L213 说"复核 SQL/时间计入总成本"）——这是**已知偏离**：
    计数口径一改会牵动 8 条按 `toolCalls` 写死的用例（含预算上限用例），需要单独一轮把口径定清楚再落。
  - ② `type` / `priority` / `triageStatus` **当前无法比对**（工具不产出这些事实键）——**不假装比过**；
    一旦产物化必须一并纳入，否则"逐字段"就成了空话。
  - ③ 复核只在**工具结果**上做（同一次调查内），不承诺全库一致快照；跨请求一致性仍需 S4 的状态存储。
  - ④ 未完成的对外呈现是 `status=INCOMPLETE` + `renderedText`；HTTP 层用 `Result.ok`（**未完成是业务状态，不是系统失败**），
    客户端必须看 `status`，不能把 `code=200` 读成"调查成功"。
- **关联**：`docs/AGENT-PLAN.md` §3.2（复核口径）/ §3.3（`STATE_CHANGED` 有生产者了）；
  `docs/agent-design/AGENT-DESIGN.md` L213；D86（状态模型）；`FinalReview`；
  `AgentInvestigationService`（`INCOMPLETE` → `renderIncomplete`）；
  `AgentInvestigationController`（`INCOMPLETE` → `Result.ok` + 无报告）；
  用例 `FinalReviewTest`（5 条）；槽 17 / 24 补期望（成熟度 19 → 21）

## D91 · 工具调用前的权限重校验（L116 第 3 条）：数据层重解析、终止不切范围、**不重校验会话**

- **日期**：2026-10-06
- **问题**：`CANCELLED(PERMISSION_REVOKED)` 在 §3.3 里登记了很久，**没有任何代码会产生它**。
  设计稿 L116 第 3 条要求"每次工具重新校验**会话**、账号、权限、主管当前部门；部门在首读后固定；
  主管调部门则终止，不切换到新范围"。怎么落地既忠实又不越界？
- **选择**：
  1. 抽**共享协作者** `PermissionRecheck`，**每次工具调用之前**调用（含入口预读），agent 与基线共用同一个 bean；
  2. 重校验**数据层**的账号 / 角色 / 部门——用 `WorkOrderService.resolveDepartmentScope(userId)`（与列表接口、
     与受理层**同源**，D85），**不另写** `isAdmin()` 之类的本地判断；
  3. 与 `ToolContext` 的**受理期快照**比对：已不是 `DEPT_ADMIN`/已无部门、部门与快照不一致、载体不可解析 → 一律撤销；
  4. 撤销 → `CANCELLED(PERMISSION_REVOKED)`：`report == null`、**evidence 保留**、
     渲染走 `renderCancelled(...)`（顶部"调查已取消（PERMISSION_REVOKED）"）；
  5. **终止即终止**：不得按新部门/新角色继续查（L116 的"不切换到新范围"）。
- **理由**：
  - **同源**：D85 已经把"谁能拿到部门范围"收敛成 `resolveDepartmentScope`；重校验若自己再写一套准入，
    就会重新引入 D85 修掉的那类漂移（复制品迟早放宽）。
  - **不切换到新范围**：这是本条最容易写错的地方——"继续用新部门查"看起来更"聪明"，
    但那等于**用一次没重新鉴权的调查读了一份属于别人的数据**（用户在被撤权后不该拿到新范围的内容）。
  - **不重校验会话**（**有意的偏离**）：执行线程是 S4 的消费/调度线程，**没有 Sa-Token 会话**；
    §11-2 已定稿"执行侧不重新取值，那里没有会话，想取也取不到"。为对齐 L116 的字面去**伪造一个会话检查**，
    只会得到一个恒真的假判据——比不写更糟。
- **代价与适用边界**：
  - ① **每次工具调用多一次"账号/角色/部门"解析查询**（`resolveDepartmentScope` → 角色 + 用户）。
    **本轮不做缓存**：缓存会把"撤销后仍按旧范围跑"重新引进来，正是本条要防的事；
    若将来要优化，必须先定**缓存失效口径**（登记为待优化项）。
  - ② **检测点在工具边界**：撤销发生在**最后一次工具调用之后**时，本轮检测不到（那需要 S4 的取消/状态机）。
    本轮只承诺"工具调用之间能看见撤销"。
  - ③ 与 D79 的承诺边界并存：`ToolContext` 仍是受理期快照，本类只是在**工具边界**上把它与"现在"比一次；
    **不承诺执行期实时**（那要靠 S4）。
  - ④ HTTP 层：`CANCELLED` 与 `INCOMPLETE` 一样走 `Result.ok`（未完成/已取消是**业务状态**，不是系统失败），
    客户端必须看 `status`。
- **关联**：`docs/AGENT-PLAN.md` §3.2（重校验口径）/ §3.3（`PERMISSION_REVOKED` 有生产者了）；
  `docs/agent-design/AGENT-DESIGN.md` L116；D79（快照承诺边界）/ D85（准入同源）/ D90（复核同模式）；
  `PermissionRecheck`；`AgentRunResult.cancelled`；`AgentReportRenderer.renderCancelled`；
  用例 `PermissionRecheckTest`（4 条）；槽 16 补期望（成熟度 21 → 22）

## D92 · 运行中有界重试（槽 21）：只重试可恢复类、三个上限、物理调用必须回传

- **日期**：2026-10-07
- **问题**：槽 21 的矩阵要求"**有界等待/重试，全部计物理调用与耗时**"，槽 22 又要求"**不无谓重试**"；
  而实现里 `HttpAgentModel` 对非 2xx **直接抛 `MODEL_HTTP_ERROR`、一次都不重试**——
  一次 429 就丢掉整次调查。重试该放在哪、界怎么定、计数怎么办？
- **选择**（**在模型边界**重试，**不在循环里再包一层**——那会重复计数）：
  1. **只重试可恢复类**：HTTP `429`、`5xx`、连接类（`MODEL_UNREACHABLE`）；
  2. **非 429 的 4xx 一律不重试**；
  3. **次数上限 = 3 次物理调用**（含首次，即最多重试 2 次）；
  4. **单次等待上限 = 2s**：`Retry-After` 只作参考值，**不得超过它**；没有头时按 200ms、400ms 退避；
  5. **总等待受"本轮剩余预算"约束**：睡下去会超出预算就**不睡**，按 `MODEL_TIMEOUT` 抛出 →
     循环按既有口径翻成 `TIMED_OUT(RUN_BUDGET_EXCEEDED)`；
  6. **物理调用次数回传**：`AgentModelException.attempts()` + 失败消息里的"物理调用 N 次"；
     重试用尽**复用 `MODEL_HTTP_ERROR`**（不新开码）。
- **理由**：
  - **只重试可恢复类**：429 是限流、5xx 是服务端瞬时故障、连接失败是网络抖动——它们"再试一次可能就好"；
    而 400/401/403 是**请求本身不对**，重试只是把同一个错误再问一遍（槽 22 的"无谓重试"）。
  - **三个上限缺一不可**：只限次数不限等待 → 一次 `Retry-After: 3600` 就能把调查挂住；
    只限等待不限总预算 → 3 次 ×2s 仍可能把一个只剩 1s 预算的调查拖过头。
  - **放在模型边界**：重试是"这一次模型调用"的内部事务；放到循环里会让每一轮的重试叠加到轮次计数上，
    口径立刻变形（同一个 429 可能被算成两轮）。
  - **复用 `MODEL_HTTP_ERROR`**：终态语义没变（就是"模型给了非 2xx"），只是多试了几次；
    消息里已经带状态码与次数，新增一个码只会让"同一个失败"出现两种叫法。
- **代价**：
  - ① **重试放大 token 成本与墙钟**：最坏 3 次物理调用（供应商按调用计费），且"失败被感知得更晚"
    （最多多等 ≈2s×2 + 调用时间）。**可接受的理由**：这几种失败本来就可能是瞬时的，
    直接失败会让用户重发一次**整轮调查**（更贵）；而最坏等待被 2s×2 与运行预算**双重**卡住。
  - ② **计数口径**：`toolCalls` 不含重试（它不是工具调用）、`modelRounds` 仍是**逻辑轮次**；
    物理次数通过 `attempts()` 与失败消息可见。**代价**：没有结构化的"物理调用数"字段
    （加字段要动 `AgentRunResult` 的公开构造签名与 2 处直接构造的用例）——登记为待优化。
  - ③ **超时链不变**：重试发生在**同一轮**的读超时之内（§4.1 的收敛逻辑照旧），所以
    超时链不需要为它加档。
- **关联**：`docs/AGENT-PLAN.md` §3.3（复用 `MODEL_HTTP_ERROR`）/ §3.4（有界重试四条界）/ §4.1；
  `HttpAgentModel`（`MAX_ATTEMPTS` / `MAX_WAIT_PER_ATTEMPT_MILLIS`）；`AgentModelException.attempts()`；
  用例 `ModelRetryTest`（5 条）；**槽 23 仍缺"取消 + 名额归还"**——它卡在**没有并发名额/限流概念**：
  现在既没有并发位、也没有"占位/归还"的生命周期，S4 先要有状态存储与名额管理，才能谈"期限收口 + 名额不提前释放"。

> **更正引用块（2026-10-07）**：上面最后那句"**槽 23 卡在没有并发名额/限流概念**"是**错的**——
> 名额管理**不需要等 S4 的状态存储**：它就是受理层的一个 `Semaphore`，本轮已经落地（D93）。
> 我当时把"需要一套完整的取消/生命周期"和"需要一个有界并发位"混成了一件事。原文保留，作为"过早断言卡点"的留痕。

## D93 · 受理层有界并发位 + 忙碌立即拒绝 + 名额幂等归还（槽 23 的前半）

- **日期**：2026-10-07
- **问题**：槽 23 的矩阵要"与成功空集区分；**期限收口且名额不提前释放**"。前半（工具故障 ≠ 成功空集）早就落地了；
  后半在当时被写成"属 S4、本机推不动"——**那个判断是错的**（见 D92 的更正引用块）。
  真正要回答的是：名额放在哪、容量取多少、取不到怎么办、什么时候归还。
- **选择**：
  1. **名额放在受理层**（`AgentInvestigationService`）——一次调查从受理到出终态只占 1 个名额；
  2. **容量可配**（`agent.investigation.max-concurrent`），**默认 1**（§4.3 的标题就是"并发 1 之外"）；
  3. **取不到 → 立即返回 `BUSY / AGENT_BUSY`**（HTTP 层 `409 CONFLICT`），**不排队、不阻塞**；
  4. **每一条终止路径都在 `finally` 里归还**：COMPLETED / FAILED / TIMED_OUT / CANCELLED / INCOMPLETE，
     以及"抛异常"这条路径；归还发生在**执行体返回之后**（底层资源已释放）；
  5. **归还幂等**：句柄 `Permit` 记"已归还"，`releaseOnce()` **只 release 一次**。
- **理由**：
  - **不排队**：§4.3 明写"忙碌时的明确响应，不是静默排队"。排队会把"忙碌"变成"悄悄变慢"——
    用户看不到边界、也拿不到重试的依据；拒绝则让调用方**立刻**知道该稍后再来。
  - **不新开码**：复用 `409 CONFLICT`——它是"当前容量与请求冲突"，不是"请求本身有错"。
  - **幂等必须显式守卫**：`Semaphore.release()` **多调一次就多一个名额**，于是"有界"会悄悄失效
    （而且失效是**单向放大**：跑得越多、名额越多，最后等于没有上限）。这与"失败不得被伪装成成功"同族：
    **边界不能靠调用方自觉**，要由持有者自己记状态。
  - **默认容量 1**：这一版的目标是"把口径做对"，不是"把吞吐做大"；容量是**初值**，
    要调大得先有真实并发数据（现在没有）。
- **代价**：
  - ① **进程内、重启即丢**：名额不是分布式配额；多实例各自独立 → 全局并发上限是 `实例数 × 容量`（§4.3 同性质）。
  - ② **忙碌是明确拒绝**：用户会看到 409 而不是"等一会儿就好"；代价换来的是**可预期**（不排队 = 不堆积请求）。
  - ③ 容量 1 时没有并发收益——这是刻意的：先用最小的容量把"泄漏/放大"这类错误暴露出来。
  - ④ **"真正的取消"仍未落地**：本轮只保证**名额不泄漏**；打断进行中的调查（interrupt / 连接断开 / 读循环中止）
    卡在 **IO 不响应 interrupt**（阻塞 `read` 只在读超时后返回，见 §11-3），所以"取消延迟上界 = 当前轮读超时"。
    衔接：将来的取消路径**必须复用同一个 `Permit.releaseOnce()`**，不得另写归还逻辑。
- **关联**：`docs/AGENT-PLAN.md` §3.2（容量拒绝的响应形态）/ §3.3（BUSY 不在运行状态组合里）/ §4.3（四条口径）/ §11-3（取消上界）；
  `AgentInvestigationService`（`Permit` / `availablePermits`）；`AgentInvestigationController`（409）；
  `application.yml`（`agent.investigation.max-concurrent`）；用例 `InvestigationConcurrencyTest`（5 条）；
  槽 23 补期望（成熟度 23 → 24）

## D94 · 真正的取消：信号 + 读取循环检查 + 主动断开；**有意不用 `Thread.interrupt()`**

- **日期**：2026-10-07
- **问题**：§11-3 裁决"取消延迟上界 = 当前轮读超时"，并要求"验收必须含**连接断开 + 名额归还**的实证"。
  实现上有两条路：① 用 `Thread.interrupt()`（看起来最直观）；② 用**显式取消信号 + 读取循环检查 + 主动断开**。
- **选择**：②
  - `Cancellation`（`volatile` 标志 + 原因）：由调用方置位；
  - `AgentModel` 增加**带取消信号的默认方法**（默认转两参版本 → 既有实现与桩**零改动**）；
  - `HttpAgentModel` **在读取循环里每个 chunk 检查一次**，取消时**主动 `disconnect()`** 后抛出 `USER_CANCELLED`；
    重试等待前也检查一次（取消期间**不睡**）；
  - 调查循环**每轮开始前**检查一次 → 不再发下一个请求；
  - 终态 `CANCELLED(USER_CANCELLED)`：`report == null`、事实保留；名额走同一个 `Permit.releaseOnce()`。
- **理由**：
  - **`interrupt()` 在这条链路上是假动作**：等待点是**阻塞 `read`**，它**不响应** interrupt——
    interrupt 只会把标志置上，`read` 仍要等数据到达或**读超时**。用它会让调用方以为"取消立刻生效"，
    而实际行为与不取消**完全一样**（只是多了一个没人看的标志）。**这属于"看起来做了、其实没做"**，
    与本项目反复强调的"表象层不等于业务结果"同族。
  - **主动 `disconnect()` 才是真释放**：它切断连接、让读取立刻失败，
    并且 §11-3 要的"连接断开"实证就是它的直接观察量。
  - **上界仍然是"当前轮读超时"**：如果取消发生在**没有数据到达**的阻塞 read 上，循环检查压根没机会执行——
    所以**必须把上界写进契约**，而不是声称"取消是即时的"。这也是 §4.2 那条裁决的由来。
  - **默认方法而不是改签名**：`AgentModel` 加一个带默认实现的 3 参方法，既有实现与所有桩**不用改**；
    改接口会波及 2 个测试内的 `RecordingModel`，收益为零。
- **代价**：
  - ① **取消不是即时的**：最坏要等一个读超时（当前 45s，且被剩余预算收敛）。要缩短只有换可中断 IO。
  - ② 取消**不保证供应商停止计费**：连接断开只是本地不再等，供应商侧可能仍在生成（§11-3 已记）。
  - ③ 取消**不产生报告**（`report == null`）：用户拿到的是"已取消 + 原因码 + 已核实的部分事实"。
  - ④ **异步接口仍未做**：当前选同步方案——取消信号由调用方（将来的异步任务/前端）置位；
    现在没有那个调用方，所以**取消能力已就绪但没有生产触发点**（登记，不假装已接线）。
- **关联**：`docs/AGENT-PLAN.md` §4.2（上界与落地说明）/ §11-3（裁决 + 落地状态）/ §6（S4 行）；
  `Cancellation`；`AgentModel.respond(..., Cancellation)`；`HttpAgentModel.readAtMost`；
  `InvestigationAgent.investigate(..., Cancellation)`；D93（名额归还衔接）；用例 `CancellationTest`（4 条）

## D95 · §4.3 的两道限流：用户级频率限制 + 全局模型调用预算（进程内护栏）

- **日期**：2026-10-07
- **问题**：§4.3 列了三条成本控制，D93 只落了**并发位**；"用户级频率限制"与"全局调用预算"一直没做。
  取值怎么定？计数放哪？与并发位是什么关系？
- **选择**：
  1. 新增 `InvestigationThrottle`（受理层协作件）：
     · **用户级频率限制**：`window-seconds` 默认 **60**、`max-per-user` 默认 **3**；滑窗按**可注入时钟**推进；
     · **全局模型调用预算**：`global-model-call-budget` 默认 **1000** 次**模型调用**（首版按次数，不按 token）；
  2. 两道判断都在**发起任何模型调用之前**，放行时**立即记账**；
  3. 超限**立即拒绝**（不排队），新增**互不相同**的对外码：`RATE_LIMITED`（HTTP 429）、
     `BUDGET_EXHAUSTED`（HTTP 503），与 `AGENT_BUSY`（409）并列；
  4. **进程内计数**：不落库、不做分布式配额。
- **理由**：
  - **三者正交，必须分开**：并发位管"**同时刻几个**"、频率管"**单位时间内每人几次**"、预算管"**累计总量**"。
    混在一起就会出现"以为限制了总量、其实只是限制了并发"这类错觉。
  - **不复用 `BUSY`**：三种拒绝的**成因**（同时刻挤 / 单人太密 / 全局额度到顶）与**用户可采取的动作**
    （稍后重试 / 降频 / 找管理员）都不同；同一个码会让调用方无法区分。
  - **默认值 60s / 3 次 / 1000 次**：都是**初值**——按"一个人一分钟内不可能合理地发起 3 次以上调查"
    与"1000 次够跑完 S6 的 144 次对照且留有余量"取的；
    **依据是拟定规模而不是实测**（没有真实流量数据），标"待实测"。
  - **为什么 S6 之前必须先有这两道闸门**：holdout 拟定规模 **24 × 2 × 3 = 144 次真实调查**，全部真金白银。
    没有频率与总量闸门，一次脚本写错（循环跑飞、重试叠加）就能把预算烧穿——**成本可控性是开跑的前提**。
- **代价**：
  - ① **进程内**：计数在 JVM 里，**重启即丢**（§4.3 已明确同性质）。
  - ② **多实例各自独立**：全局预算是"每实例 1000"，不是"整个部署 1000"；要真全局得引入共享计数器（S4 之后）。
  - ③ **与并发位正交但相乘**：容量 1 + 每人每分钟 3 次 + 全局 1000 次，实际吞吐受**三者中先到顶的那个**约束；
    调参时要一起看，别只调一个。
  - ④ **拒绝会让用户看到 429/503**：这是"明确响应"的代价（换来的是不排队、不悄悄变慢）。
- **关联**：`docs/AGENT-PLAN.md` §3.2（拒绝码与 HTTP 层）/ §3.3（运行之外的拒绝码）/ §4.3（三道闸门对照表）；
  `InvestigationThrottle`；`AgentInvestigationService`（判断在并发位之前）；`application.yml`（三组配置）；
  用例 `InvestigationThrottleTest`（6 条）；D93（并发位与名额归还）

## D96 · S5 资源与主业务影响：本机实测（桩模型 + 专用库）

- **日期**：2026-10-07
- **问题**：§6 的 S5 要求"查线程、连接池、内存与**主业务影响**"。本机能做吗？怎么做才不是"看起来没影响"？
- **做法**（全部本机、零真实费用；原始数字见 `docs/agent-eval/s5-resource-impact-20261007.md`）：
  专用库 `work_order_s5`（结构克隆自 `work_order_test`）+ 桩端点（**固定 3s 延时**）+ 后端进程
  （`max-concurrent=3` 临时值）+ `Threads_connected` 采样器 + "有/无调查并发"的两批各 30 单提交。
- **实测结果**：
  - **主业务 30/30 `code=200`（错误 0，按业务码判定）**；P99 **31 → 55 ms（+24 ms）**，p50 20 → 28 ms；
  - **资源有界**：RSS 309 → 310 MB、线程 60 → 59~62（**不随并发增长**）、`Threads_connected` **恒为 6**；
  - **不泄漏**：堆 used 上升 ~6 MB 后持平，**`jcmd GC.run` 后降到 35 MB（低于基线 68 MB）**
    ⇒ 那些是**可回收的临时对象**；
  - 3 个并发调查全部 `COMPLETED`（6.2 s/个 = 2 次模型调用 × 3 s 桩延时）。
- **选择**：把 S5 记为 **✅**，但**只对"本机 + 桩"成立**；生产影响仍需服务器完整栈。
- **理由**：
  - **判据必须可证伪**："主业务受扰没有"不能靠感觉——所以用**两批各 30 单**对照（同环境、同批大小），
    并**按业务 code** 而不是 HTTP 状态判定（本项目的老坑：HTTP 200 + code=500 会被读成成功）。
  - **"不泄漏"必须有决定性判据**：只看堆 used 会把"还没 GC"误判成"泄漏"——
    所以显式跑一次 `jcmd GC.run`：落到基线以下才是真结论。
- **代价与边界**：
  - ① **桩延时不是生产延迟**（L139）：6.2 s/个与 P99 的绝对值都**不可外推**；能用的只有**相对关系与上限**。
  - ② **Hikari active/idle/pending 未观测**：应用没接 actuator、也没开 JMX；本机也没有 mysql 客户端。
    替代口径是 MySQL 侧 `Threads_connected`（= 应用实际占用的连接数，恒为 6）。
  - ③ **`max-concurrent=3` 是临时值**（为了制造并发而临时调高，正式默认仍是 1）；这份记录里写明了是临时值。
  - ④ **30 单的样本量很小**：P99 在 n=30 时≈max，只能说"绝对量是几十毫秒"，不能说"生产 P99 只涨 24 ms"。
- **关联**：`docs/agent-eval/s5-resource-impact-20261007.md`；`docs/AGENT-PLAN.md` §6（S5 行）；
  `src/test/java/com/workorder/agent/eval/S5FixtureHarness` / `S5SamplerHarness`；`scripts/stub-llm-agent.py`；
  §4.1（超时链）/ L139（离线 vs 部署端）

## D97 · S6 阶段 1：holdout harness 桩跑通——**桩跑不是成绩**

- **日期**：2026-10-07
- **问题**：S6 要"成对对照、保留全部失败、分母固定"。真模型那一遍有**真实费用**与前置（须委托方批准）。
  批准之前，怎么证明"评测真的能跑完、失败真的不会被吞"，而不是又写一堆"待跑"的文档？
- **做法**（全部本机、零真实费用；硬证据见 `docs/agent-eval/s6-holdout-stub-run-20261007.md`）：
  新增 `AgentEvalHoldoutHarness`（**类名不带 `Test`** → 默认 `mvn test` 不跑它）：失败注入用**本地 HTTP 桩**
  （agent 侧复用装配好的 `HttpAgentModel`，只把 `llm.api.url` 指向桩——**真实 HTTP 写读 + JSON 解析 + 有界重试**都在链路上），
  专用库 `work_order_holdout`（结构克隆自 `work_order_test` + `t_role`，跑完按 D19 最宽口径统计再 DROP），
  24 例 × 2 方案 × 3 次 = **144 次**，每例每方案 3 次**全部保留**；故障注入槽（16/17/19/20/21/22/24）由桩**按场景驱动**。
- **结果**：144 次跑完；产出逐例表 + 汇总 + **完整失败清单**；**确定性**（同批夹具 × 3 次，逐例终态/类型一致）通过；
  **失败保留证明**通过（负向自检：故意改错的期望被判失败并进清单，且**不进 144 的分母**）。注入槽终态全部正确
  （16 `CANCELLED(PERMISSION_REVOKED)`、17/24 `INCOMPLETE(STATE_CHANGED)`、19 `FAILED(MODEL_PROTOCOL_ERROR)`、
  20 `FAILED(NO_PROGRESS)`、21 `COMPLETED`、22 `FAILED(MODEL_HTTP_ERROR)`）。
- **选择**：把 §6 的 S6 记为 **🔶**（harness 就绪、**真跑待批准**）——**这一遍不是成绩**。
- **理由**：
  - **桩跑必须与成绩切开**：桩对任何输入返回固定值（固定 `ORDER_STATUS`）→ 只能验证 harness 与链路，
    不能当模型能力或两方案对照（同 `scripts/triage-eval.py` 文件头的口径，手册 L139）。
  - **"失败保留"是硬判据**：若一遍下来"全过"，很可能是判分器被写成了必过——所以三件一起做：
    分母固定（失败/超时留分母）、真实失败全列、**显式负向自检**。
  - **注入槽要"场景驱动"**：19/20/21/22 的 `question` 是注入描述、不是业务问题——由桩回
    非法批次 / 重复调用 / 429 / 401 来驱动，而不是当自然语言提问。
- **代价与边界**：
  - ① 桩跑的数字**零证明力**：正确性、证据覆盖、延迟都**不可外推**到生产或真模型。
  - ② 故障注入只作用在 **agent 路径**（它是模型/执行期事件）→ 这些槽的 `fixed` 行显示 `COMPLETED` 属**驱动范围**，
    不代表 fixed 更强或更弱（该结论只能来自真模型那一遍）。
  - ③ **槽 23（工具故障 / 池饱和）本轮未驱动**：没有可注入故障的工具边界；其"名额不提前释放"由
    `InvestigationConcurrencyTest`（5 条）覆盖、"与成功空集区分"由 `TOOL_FAILED` 路径覆盖，真机端到端留 S6 后段。
  - ④ 真模型那一遍的**成本/时长是估算**（token 以实际 usage 为准，**不凭记忆估**，手册 §6.1）。
- **关联**：`docs/agent-eval/s6-holdout-stub-run-20261007.md`；`docs/AGENT-PLAN.md` §6（S6 行）；
  `src/test/java/com/workorder/agent/eval/AgentEvalHoldoutHarness.java`；
  手册 §3.1（强基线）/ §5（样本与评分契约）/ §5.1（24 槽）/ L128 / L139 / L145；
  D89（超时链，估算依据）；D96（S5：资源与主业务影响——**与 S6 的分工**）

## D98 · S6 阶段 2：holdout **真模型**对照（只跑一次）——**业务默认维持 `fixed`**

- **日期**：2026-10-07
- **问题**：S6 要"成对对照、保留全部失败、按实测决定默认方案"。真模型那一遍怎么跑、结论是什么？
- **冻结确认**（跑之前记，跑完复核仍在）：HEAD `8062fac`；`scripts/agent-eval-holdout.json` SHA-256
  `DA3DB1C39BFA4FCBDA4E3D62F3ECFE5BE1020AD9741A6C3A357EDA6A6ED78D83`；**跑完该 hash 未变**（评测集/期望/契约/业务代码全程未动）。
- **做法**：`AgentEvalHoldoutHarness` 的**真模型模式**（`S6_REAL=1`）——`llm.api.url` 指向 harness 自己的
  **转发代理**（原样转发请求体、原样回传响应，并在转发侧记录**物理调用**与 **usage**）；专用库 `work_order_holdout`；
  **24 例 × 2 方案 × 3 次 = 144 次**；模型 `deepseek-flash`（真供应商）。
  故障注入槽：19/20/22 由代理**合成**（纯协议故障，不调供应商）、21 先合成 429 再转发、16/17/24 改库后转发。
- **实测**（原始记录 `docs/agent-eval/s6-holdout-real-20261007.md`）：
  - **质量**：agent 通过 **51 / 72**；fixed 通过 **30 / 72**（分母固定 144，失败全留）。
  - **成本**：agent 物理调用 **82** 次（fixed 0）；token **输入 141 217 + 输出 86 036**（usage 缺 0 次）；
    单次耗时 median agent **3 102 ms** vs fixed **21 ms**（min/median/max = 4 / 30 / 60012 ms）。
  - **可靠性**：agent 有 **7 / 72** 以非正常码收口 —— `MODEL_TIMEOUT` 2（45 041 / 47 693 ms，撞单轮读超时）、
    `RUN_BUDGET_EXCEEDED` 1（60 012 ms，撞运行墙钟）、`MODEL_PROTOCOL_ERROR` 4。
  - **越权**：跨部门 3 槽（13/14/15）两方案都 `FAILED(FORBIDDEN)`，零内容外泄。
  - **复现性**：逐例（方案 × 例）三次终态/类型完全一致 **42 / 48**（真模型不保证逐次一致，不一致的如实保留）。
- **选择**：**业务默认维持 `mode=fixed`**；agent 保留为**实验开关**（与 §1 第八题"没有可证明的净收益就不改默认"一致）。
- **理由**：
  - **§1 第八题**：这是**单次、小样本**（24 例）——**不足以证明净收益**，因此不改默认。
  - **L219**：20% 只是**小样本探索阈值**；**不得**把 51 vs 30 写成"统计显著"或"已测改善"。**agent 未输**（通过更多），
    但这只是**信号**，不是**结论**。
  - **差距的来源要说清**：fixed 的 42 个失败里**大多数是 `UNSUPPORTED` 类型错**——差距主要在 fixed 的
    **透明关键词分类器**（§3.1 L118 允许的首版），**不在"数据访问能力"**：基线用的是**同一批工具、同一权限、同一预算**，
    没有少给任何能力（L110）。
  - **可靠性代价**：agent 有 `MODEL_TIMEOUT` / `RUN_BUDGET_EXCEEDED` / `MODEL_PROTOCOL_ERROR` 共 7/72——
    换成默认要承担这些。
- **口径三条（必须带上）**：
  - ① **不可外推**：本机 + 真供应商 + **单次**；延迟含真实网络，但仍是"本机 vs 真模型"，**不是**生产数字。
  - ② **反向样本不足**：24 例、各 3 次、**只跑一遍**；agent 的非正常收口只有 7 个，"可靠性"结论同样**样本不足**。
  - ③ **失败保留**：分母固定 **144**；失败 **63 条全部列出**（不删难例、不缩分母）；并有一次**负向自检**证明判分器不吞失败。
- **附带发现（登记为待复测 / 待裁决，本轮不改）**：
  - **D89 的 45s 单轮读超时被真机越过 2 次**（45 041 / 47 693 ms → `MODEL_TIMEOUT`），而 D89 的 17 次样本 max 仅 26.1s
    → **样本太小**；45s 需按**更大样本**复测（**本轮不动超时链**，改它属业务代码）。
  - 1 次运行墙钟触顶（60 012 ms → `RUN_BUDGET_EXCEEDED`）。
  - **槽 16 的驱动依赖"模型在首轮发起工具调用"**：真模型 3 次都**直接收尾**（无工具调用）→ `PermissionRecheck` 未触发 → `COMPLETED`。
    这是 B 层驱动的**已知边界**，写进记录 §6。
- **关联**：`docs/agent-eval/s6-holdout-real-20261007.md`；`docs/agent-eval/README.md` §9 / §12 / §13；
  `docs/AGENT-PLAN.md` §6（S6 行）；`src/test/java/com/workorder/agent/eval/AgentEvalHoldoutHarness.java`；
  手册 §3.1 / §5 / §5.1 / L110 / L116 / L128 / L139 / L145 / L219 / L222；D89（超时链）；D96（S5 分工）；D97（桩跑）

## D99 · P7③：按 DEMO-SCRIPT 在本机走完 10 步演示（**本机 ≠ 生产**）

- **日期**：2026-10-07
- **问题**：P7 的「对外呈现·③」此前登记为 **未做**，理由是"本机**没有 docker CLI**、6379/5672/9000/8080 **全不通**、必须在服务器"。
  那句还成立吗？本机能不能按 `deploy/DEMO-SCRIPT.md` 把 10 步真正走一遍并留下证据？
- **做法**（全部本机；**不接真模型**，分诊用本机桩）：
  - Docker CLI 用**显式路径** `…\DockerDesktop\resources\bin\docker.exe`（CLAUDE §5「命令找不到 ≠ 没有装」）。
  - **端口表**（仓库外 override，§5 不入库；先只读确认 9000/8080/80/8081 全空闲，故无需再换端口）：
    | 服务 | 宿主端口 | 说明 |
    | --- | --- | --- |
    | mysql | **3307** | 已有，不动 |
    | redis | **6380** | 已有，不动 |
    | rabbitmq | **5673**（+15673 控制台） | 已有，不动 |
    | backend | **9000** | 本轮新起 |
    | frontend | **80** | 本轮新起 |
    | xxl-job-admin | **127.0.0.1:8080** | 本轮新起 |
  - **就绪判据 = 真实请求**：`POST /api/login` 得 `code=200`（不是"容器 Up"）；辅助看**本次启动窗口** `docker logs --since`。
  - 分诊模型 = 本机桩 `scripts/stub-llm.py`（18080，`STUB_DELAY_MS=3000`/`STUB_TYPE=NETWORK`/`STUB_PRIORITY=1`），backend 用 `host.docker.internal:18080` → **零真实费用**。
  - 演示用户：`demo_sub`(SUBMITTER) / `demo_hand`(HANDLER) / `demo_admin`(DEPT_ADMIN)，复用了 admin 的 BCrypt 哈希、dept=1（本机演示库原本**只有 admin**、0 单、无自有任务，故现场造数）。
- **结果（10 步逐一，判据全落业务 code / 业务状态）**：
  1. **登录** `demo_sub` → `code=200`。
  2. **提交**（只填 title/content）→ **130ms**、`code=200`、`type=OTHER`、`status=PENDING`（先落兜底值、响应不等 LLM）。
  3. **+6s 刷新** → `type=NETWORK`、`priority=1`、`slaDeadline` **10:15 → 03:15（收缩）**；日志出现 `TRIAGE` 修正行。
  4. **处理人站内信** → 「新工单待抢单：WO-…」（`refType=ORDER`/`refId` 非空/未读）。
  5. **抢单** → `code=200` → `status=ACCEPTED`、`assigneeId=3`。
  6. **开始处理** → `status=IN_PROGRESS`。
  7. **提交验收** → `status=AWAIT_APPROVAL`。
  8. **驳回**（带 `action-token`）→ `code=200` → `status=IN_PROGRESS`、`rejectCount=1`；**重复用同一个 token → 业务 `code=409`**（幂等）。
  9. **三次驳回** → `status=ESCALATED_ADMIN`、`rejectCount=3`；admin 收到「驳回次数已达上限」；**提交人再查该单 → `code=403 无权查看该工单`**（升级单仅管理员）。
  10. **SLA 告警**：把 6 号单改成逾期 → 本地 `@Scheduled`（日志 `[sla-scan] 触发来源=local 本轮通知 1 条`，300s 节拍）→ admin 收到「工单 WO-… SLA 超时」、**该单 `status` 不变**（仍 `PENDING`）、Redis `sla_notified:6`=1。
  - **调查助手**（override 打开 `enabled=true` + `mode=fixed` → **零模型调用**）：`DEPT_ADMIN` → `COMPLETED` + 渲染**三段齐全**；**非 `DEPT_ADMIN` → 业务 `code=403`（HTTP 200）**；**未登录 → 业务 `code=401`**；**开关关（默认）→ 该路径 404**。
- **选择**：把 P7「对外呈现」行标 **✅**，并补"验证位置"列（本机 vs **仍需服务器**）。
- **口径与边界**：
  - ① **本机演示 ≠ 生产**：单机 Windows + 本机桩 + 单实例；端口与资源都**不是** 2C4G / 6 容器生产形态。
  - ② **判据落业务 code / 业务状态**，不落 HTTP 状态（例：越权是 HTTP 200 + 业务 `code=403`）。
  - ③ **仍需服务器**：浏览器页面走查（`DEMO-SCRIPT` §0.2）、调度中心三项（本机演示库**未注册 5 个自有任务**，`xxl_job_info` 只有平台示例任务 `trigger_status=0`）、6 容器 / 2C4G 资源。
- **附带发现（都登记，不在本轮改）**：
  - **backend 镜像是旧的**（`deploy-backend:latest` 构建于 **2026-09-24**，落后 HEAD）→ 旧镜像里分诊跑在**请求线程**（同步）、还把桩返回的 `NETWORK` 判成"非法类型"（与当前 `VALID_TYPES` 矛盾）。**重建 backend 镜像**后行为才与源码一致（提交 130ms、类型写回正确）。**教训：本机演示前必须 rebuild 或确认镜像 = HEAD**。
  - compose 的 `backend.environment` **未包含** `AGENT_INVESTIGATION_ENABLED/MODE` → 只导出到宿主机 shell **不会**进容器；本次用**仓库外 override** 加（**未改仓库 compose**，避免把"演示开关"变成默认）。
  - `DEMO-SCRIPT` 里调查助手的 curl 头**写错了**（`satoken`）——实际 `sa-token.token-name: Authorization`；本轮已修正文档。
- **恢复现场**：停 `backend`/`frontend`/`xxl-job-admin` + 桩；**保留 `mysql`/`redis`/`rabbitmq`**。演示库按 D19 **先统计再清理**——
  清前 `t_work_order`8 / `t_work_order_log`20 / `t_notification`6 / `t_event_outbox`9 / `t_consume_record`8 / `t_user`4 / `t_user_role`4；
  清后订单/日志/通知 = 0、用户 = 1（admin）；`t_event_outbox` 9 行**全 SENT**（无 PENDING，不会重投）。
  **所有临时配置都在仓库外 → 无需还原**（`docs/PENDING-RESTORE.md` 已注明）。
- **关联**：`ASYNC-SCHEDULING-PLAN.md` §P7（对外呈现行 + 收尾清单第 8/9 条）；`deploy/DEMO-SCRIPT.md`；
  `docs/INTERVIEW-RESUME.md`「数字定稿表」；`README.md` §6.1；D24（隔离）/ D68 / D72 / D73 / D89 / D98

## D100 · P7 服务器侧四条：本轮**未取得证据** → P7 不整体勾（不推断、不补造）

- **日期**：2026-10-07
- **问题**：P7「服务器侧收尾清单」的四条（① 浏览器页面走查 / ② 调度中心 5 个自有任务 / ③ 6 容器·2C4G 资源 / ④ 删 108 条 `TST-` 残留）
  能否据委托方粘贴的服务器输出勾掉？
- **现状**：本轮委托方**未粘贴**四条中任何一条的**原始输出**，也**未给出可见性裁定**。
- **判定（逐条）**：**① 证据不足 ② 证据不足 ③ 证据不足 ④ 证据不足**——一律按"**未取得**"处理
  （**不推断、不补造、不替它补数字**）。因此 **P7 不整体勾**，且**不改**任何"仍需服务器"的措辞为"已完成"。
- **每条缺什么（判据见 §P7 清单）**：
  - ① 浏览器页面走查：十站**逐站**结论；乱码/空值/错位要**定位到页与列**（只写"没问题"不算）。
  - ② 调度中心：`xxl_job_info` 的 **5 行自有任务 `trigger_status=1`** 原文 + 手动触发后 `xxl_job_log` 的
    **`handle_code`** 与 **`handle_msg`**（**必须带业务摘要**，空/NULL 不算）。
  - ③ 资源：**同一次取数**的 `free -m` 的 `MemAvailable` 与各容器 RSS，**口径写明**（RSS 合计 ≠ 可用内存，两个量纲）。
  - ④ 删除残留：**先按最宽口径统计留档再删**（D19 两口径取最大），并给**删除前后计数** + **六表孤儿检查**（工单/日志/通知/outbox/consume_record/message_retry）。
- **处置**：收到原始输出后按 §P7 清单逐条比对；**四条都通过**才把 P7 整体标 ✅ 并更正对外材料里"仍需服务器"的措辞；
  任一条不通过则维持待办并写清缺口。
- **可见性**：本轮**无裁定**→ **未做任何变更**（保持私有），也未执行 `deploy/README.md` §0 的过筛动作（那是一套"改公开"才做的动作）。
- **关联**：`ASYNC-SCHEDULING-PLAN.md` §P7「服务器侧收尾清单（本机无效的 4 项）」及其**结果区**；
  `deploy/DEPLOY-RUNBOOK.md` §4/§5；`deploy/DEMO-SCRIPT.md` §0.2；`deploy/README.md` §0；D19（删除纪律）；D99（本机 10 步）
## D101 · P7 服务器侧收尾（第二轮）：②③④ 通过、① 未取得 + **"2C4G" 口径更正** + 三条新观察

- **日期**：2026-10-07
- **问题**：P7 剩余四条只能在服务器上验。委托方转贴了 2026-10-07 11:22:40 CST 的原始输出——
  判据怎么算、附带发现怎么处置？
- **选择**：②③④ 判**通过**、① 判**未取得（P7 不整体勾）**；同时更正规格口径、登记三条观察。
- **理由（逐条）**：
  - **② 调度中心**：5 个自有任务 `trigger_status=1` + 最近 10 条 `trigger_code=200` / `handle_code=200`
    且 `handle_msg` 带业务摘要，且 **`触发来源=xxl`** ⇒ 既证明"任务已注册并启用"，也证明"**是调度中心在触发**"
    （不是本地 `@Scheduled` 兜底代劳）——这正是该项要区分的两件事。
  - **③ 资源**：`available 1661 MiB` 与 6 容器 RSS 合计 **≈1264 MiB** 是**两个量纲，不相加**；
    6 容器逐个 `memswap == mem` ⇒ **不变量 #12 成立**。**只做了一次取数**，故只作"某时刻的资源形态"，不作趋势。
  - **④ `TST-` 残留**：两表计数均为 **0** ⇒ 该项**不是待办而是过期登记**——P0a 第 9 项在 2026-09-23 已完成
    （`INVARIANTS.md:85` 记载清理后两表为 0）。**处置是关闭，不是执行删除**。这与本会话第三次出现的
    "旧登记过期"同族（前两次：`@OrderAction` 零应用点、`testSubmitOrder_slaDeadlineNull` 不存在）。
- **规格口径更正（本轮最重的一条）**：实测 **4 vCPU / 3723 MiB**，而全仓文档一律写 **2C4G**。
  - "4G"**有据**：`ASYNC-SCHEDULING-PLAN.md:350-351` 的历史 `free -m` 也是 `total 3723`，**同一台机器**；
  - **"2C"没有任何实测依据**（全仓无 `nproc`/`lscpu` 读数），是当初的假设；
  - 影响面**必须分开看**：**内存类结论不变**（"余量够用"的载体是 3723 MiB）；**CPU 类表述要更正**，
    含 `:266` 那句"不可外推到 **2 vCPU** 目标机"的换算基准。
- **代价**：
  - 本轮更正只覆盖**三处最常被引用的位置**（`CLAUDE.md:37` 不变量 10、本文件 §目标、`README.md:6` 首屏），
    其余引用点（§1.6、§P6 等）**仍是旧措辞**——已登记，**不假装"全仓已清"**；
  - ③ 只有**单次取数**：`MemAvailable 1661` 与早先登记的 `1750` 不同、RSS 合计 `1264` 与 `1148` 不同，
    **不能据此说"内存变多了"**（不同时刻、不同负载）；要作趋势必须多轮采样；
  - `rabbitmq CPU 40.33%`（其余容器 <1%）只有**一次瞬时读数**，与早先记录的"周期性瞬时尖峰"一致但**未证实**，
    仅登记；
  - `backend` 绑 `0.0.0.0:9000` 属**待确认**，不是已确认暴露——**未看云安全组之前不下结论**，也未改配置。
- **关联**：`ASYNC-SCHEDULING-PLAN.md` §P7「服务器侧收尾清单」结果区·第二轮；`INVARIANTS.md` §1.6.8 与 I4(c)；
  `CLAUDE.md:37`；`README.md:6`；D19（删除纪律）、D99、D100
## D102 · P7 收口：① 口述级通过 + **可见性裁定"保持私有"** + 安全组确认 9000 未放通

- **日期**：2026-10-07
- **问题**：P7 服务器侧收尾还剩 ① 浏览器走查与可见性裁定；另有上一轮登记的两条观察
  （宿主 swap 74 MiB、`backend` 绑 `0.0.0.0:9000`）待定。
- **选择**：
  - ① 记为**口述级证据通过**（**不升级为"逐站留痕"**）；②③④ 早已由原始输出通过 ⇒ **P7 判为整体 ✅**；
  - **可见性：保持私有**（委托方裁定）；
  - 云安全组截图确认 **9000 未放通** ⇒ 上一轮的安全观察**结论为"不暴露"**。
- **理由**：
  - ① 是**人眼判据**（D73：自动化判据抓不到种子双编码），委托方即仪器，口述"都可以没问题"是它的天然证据形式；
    但**口述 ≠ 逐站留痕**——两者不能混，这正是本项目反复强调的"表象层 ≠ 证据强度"。
  - 可见性保持私有 ⇒ 过筛清单（`deploy/README.md` §0）**不需要执行**，等价于"不做任何外部变更"，风险最低。
  - 安全组 4 条规则（`443` / `22` / `80` / `ICMP`）里**没有 9000** ⇒ 容器绑 `0.0.0.0` 只是**纵深防御**上的
    可改进项，**不是已确认的暴露**；据此**不改任何配置**。
- **代价**：
  - ① **没有逐站结论、没有截图**：若日后要用它对外陈述（简历 / 面试），需要**补一轮**（或补截图），
    否则只能引用为"已人工走查、未发现项"这一较弱表述；
  - **22 端口对全部 IPv4 开放**（SSH 全公网可达）——登记为**加固项**，本轮**不改**；建议后续按需把来源收敛到自己的 IP；
  - 上一轮另外两条观察仍未闭合：**宿主 swap 1→74 MiB 的来源未定位**、**`rabbitmq` 单次 CPU 40.33% 未证实**。
- **关联**：`ASYNC-SCHEDULING-PLAN.md` §P7 结果区·第三轮；`deploy/README.md` §0；D99、D100、D101

## D103 · 前端接入「调查助手」：页面落地 + 三条边界（**UI 未验证** / 60s<75s<90s 包络 / 演示形态变为"页面 + API"）

- **日期**：2026-10-07
- **问题**：调查助手此前只有同步 API（开关默认关），前端一直没有调用点——D89 与 `AGENT-PLAN` §4.1 把"前端 90s"标成
  ⏳ 待 UI，§6.1 / README / DEMO-SCRIPT 一律写着"前端未接"。现在页面落地了，必须一次性把**现状**与**证据强度**分开写清楚。
- **选择**：
  1. **页面落地**：`/agent/investigation`（**仅 `DEPT_ADMIN`** 可见/可达——用新增的 `route meta.role` 表达，
     **不新造**一个后端并不存在的权限码；受理层的准入本来就是按角色判的，§11-2 / D79 / D85）；
     `frontend/src/api/agent.ts` 用**专用 axios 实例**（`timeout: 90000`，且**不做** `code !== 200` 的自动 toast/reject，
     否则五种拒绝与 `INCOMPLETE`/`CANCELLED` 的身份会被拦截器吃掉）；**全局 `frontend/src/utils/request.ts:22` 的 15000 不动**。
  2. **状态描述直接改**（"现在是什么"，留旧措辞就是误导）：`README.md` 两处、`deploy/DEMO-SCRIPT.md` 标题 + 新增页面版步骤、
     `AGENT-PLAN` §4.1 的 ⏳→✅、§6.1 那条"不能说"。**历史记录另按 §5 惯例处理**：D89 追加块里那行 ⏳ **原文保留**，
     紧随其后加一行更正；`docs/agent-eval/README.md` 是**转述** §6.1，随 §6.1 一起改，不单独动。
  3. **登记三条边界**：① **UI 交互未验证**；② **90s/75s/60s 的包络理由**；③ **演示形态**由"仅 API"变为"页面 + API"。
- **理由**：
  - **证据强度必须分层写**："构建通过"（`vue-tsc` 0 错误）与"页面点通"是两种东西。本轮实测过的是**接口**——
    真实 HTTP（`mode=fixed`，零模型调用）：`COMPLETED` + 三段 `renderedText`；非 `DEPT_ADMIN` → **HTTP 200 + 业务 403**；
    开关关 → **404**；**没有**实测过**页面**（本机浏览器自动化不可用：`browser-client` 要找 `browser/26.930.61225`，
    实际装的是 `26.930.31730`，版本错位）。把两者混成一句话，正是本项目反复犯的"**表象层 ≠ 业务结果**"（D24 同族）。
  - **包络不必逐档实测**：三层是**嵌套且内层先到顶**——运行预算 **60s**（到点即进终态；§3.3：没有任何路径会停在 `RUNNING`）
    < 代理 **75s** < 前端 **90s**。所以代理那一档只在"内层失守"时才可能被触发，为它造假想流量成本大于收益。
    与 D89"不按 max 定值"同向：**够用的判据是包络关系**，不是每一档都有实测数字。
  - **状态描述与历史记录分开处理**：README / §4.1 / §6.1 回答"现在是什么"→ 直接改；D89 的追加块回答"当时怎么想"→
    **原文不删 + 更正紧跟**（`CLAUDE.md` §5 记录惯例）。
- **代价**：
  - ① 页面没跑过 → **样式/交互层本轮不可判**（`v-loading` 遮罩、`el-alert` 排版、菜单显隐、`/403` 拦截都没有人眼判据）；
    要对外说"页面可用"，必须先补一次真机走查——`DEMO-SCRIPT` 新增的**页面版步骤**就是那次补验的脚本。
  - ② **90s > 线上 nginx 的 75s** → 经 nginx 时**代理可能先返回 504/502**；页面把这种"非业务响应"收成
    "服务端返回 HTTP xxx（非业务响应）"，但该档**未实测**（本机直连 9000，不经过 nginx）。
  - ③ 为按业务 code 分支而**绕开了全局拦截器** ⇒ 该接口的 **401 清理**（清 token + 回登录页）由**页面自己**做；
    将来新增调用点必须一并处理，不能只依赖 `request.ts`。
  - ④ 前端**绕不过**"同步接口最坏要盯着转圈约 90s"这个代价（D89 已记）；取消/异步仍属 S4。
  - ⑤ `frontend/api-docs.json` 里**没有**这个端点（controller 带 `@ConditionalOnProperty` 且默认关，快照不收录），
    类型**以后端 VO 为准**——**没有**修改契约文件。
- **本机夹具清理留痕**（D19 口径：**先按最宽口径统计，留档范围必须覆盖删除范围**）：
  演示夹具 `dept_admin_demo`(id=5) / `submitter_demo`(id=6) / 工单 `WO-20261007-00001`(id=9) 建在**本机 3307 的 `work_order`**；
  本机无 `mysql` 客户端，改用 **JDBC**（`mysql-connector-j-8.3.0` + `java 单文件源码`）执行，谓词覆盖
  **主键 / 外键 / 冗余列 / 文本列**（`order_no`、`event_id`、`payload`、`title/content`）四种口径后删除：

  | 表名 | 删除前计数 | 删除后计数 |
  | --- | --- | --- |
  | `t_work_order_log` | 1 | 0 |
  | `t_work_order` | 1 | 0 |
  | `t_notification` | 0 | 0 |
  | `t_event_outbox` | **2** | 0 |
  | `t_consume_record` | 0 | 0 |
  | `t_message_retry` | 0 | 0 |
  | `t_user_role` | 2 | 0 |
  | `t_user` | 2 | 0 |

  （`t_event_outbox` 是**最宽口径**才看得到的两行——只按 `order_id` 那一类口径统计会漏；删后 `work_order.t_user` 总数 = 1，即只剩 `admin`。）
  另：Redis `order:seq:20261007` **未动**（值 = 1）——删行不会撞单号唯一键（下一张是 `-00002`），无需按 D45 对齐计数器。
- **本机环境观察（非本轮引入，别当成产品缺陷）**：3307 的 `work_order` 库**缺 P6 的 6 项**——
  `t_archive_log` / `t_job_watermark` / `t_daily_report` / `t_daily_report_part` 四张表，
  加 `t_message_retry.idx_created_at` / `t_event_outbox.idx_status_sent_at` 两个索引（与 `SchemaStartupCheck` 报的"缺失 6 项"逐项对上），
  启动时 ERROR 但**不阻断**；`LLM_API_URL` 未配 → `LlmStartupCheck` ERROR（`fixed` 模式不调模型，无影响）。
  按本轮前置说明：**服务器上这两项都正常**（12 项自检通过 / `triage 可用`）——上面这些只是**本机库**的形态。
- **关联**：`frontend/src/api/agent.ts`、`frontend/src/views/agent/InvestigationView.vue`、`frontend/src/router/index.ts`、
  `frontend/src/layout/AppSidebar.vue`；`docs/AGENT-PLAN.md` §4.1 / §6.1；`deploy/DEMO-SCRIPT.md`（页面版 + API 版）；
  `README.md`「工单调查助手」；`docs/agent-eval/README.md` §9 尾注；D19（删除纪律）、D24、D89、D93、D95、D102

> **更正块（2026-10-07，真机走查之后；上面原文全部保留）**：本条目里**边界①「UI 交互未验证」**——即"选择"第 3 条、
> "理由"里那句"**没有**实测过**页面**"、以及**代价①**"页面没跑过 → 样式/交互层本轮不可判"——**均已被取代**：
> 2026-10-07 在**服务器真机**做了逐站走查，**A 组 4 条全过**（主管看得到菜单 / 非主管看不到且敲 URL 被挡到 `/403` /
> 发起后立刻出现「调查中」/ 三段与**直连 API 逐字一致** / 非主管与跨部门按**业务码**呈现拒绝）、**B 组十站全过**
> （含角色管理无 D73 双编码、SLA 配置 30/120 与释放时刻吻合、统计看板「共 4 个工单」与列表一致），
> 证据见 `ASYNC-SCHEDULING-PLAN.md` §P7 结果区·第四轮与 **D105**。
> **验证方式是人眼看，不是自动化**；覆盖范围是"**本机浏览器 + 服务器这一套数据**"，得不出"多环境多数据都正确"。
> 同条目其余部分**不受影响**：边界②（60s < 75s < 90s 包络）、边界③（演示形态 = 页面 + API）与代价②③④⑤ 仍然有效；
> 代价①里那句"必须先补一次真机走查"**已经补了**——`DEMO-SCRIPT` 的页面版步骤仍是下次走查的脚本。

## D104 · DEMO-SCRIPT 判据修正：把"服务层内部对象"当成了"对外响应形状"（+ 一条判据纪律）

- **日期**：2026-10-07
- **问题**：`deploy/DEMO-SCRIPT.md` 调查助手的 **API 版**里，"预期返回"样例与 **#1 / #3 / #4** 三条判据描述的是
  **服务层的 `Outcome`**（含 `report` 对象、`failureCode`），不是**接口的对外形状**。实测形状是**扁平**的：
  `data = {status, problemType, evidenceIds, suggestionIds, renderedText}`，**没有 `report` 对象**；
  越权时是 `HTTP 200 + body.code=403 + data=null`（既没有 `status` 也没有 `failureCode`）。
  ⇒ 那几条判据**在 HTTP 响应里根本观察不到**：照它验只会得到"假失败"，反过来也可能把不存在的字段当"通过"。
- **选择**（本轮只改 DEMO-SCRIPT 这一处，**一行代码不动**）：
  1. 样例改成**实测形状**：扁平、去掉嵌套 `report`；并写明 `AgentInvestigationVO` 里为 null 的字段被
     `spring.jackson.default-property-inclusion: non_null` **整个键省掉**（所以 `COMPLETED` 时**看不到 `failureCode` 键**，
     那是省键而非漏写）；
  2. #1 → 判 `data.problemType` / `data.evidenceIds` / `data.suggestionIds`（不再写成 `report` 的子字段）；
  3. #3 → 判 **`HTTP 200` + `body.code=403` + `data=null`**，并注明服务层的 `Outcome.status=FAILED` /
     `failureCode=FORBIDDEN` 由 `AgentInvestigationWiringTest` 覆盖，**不是 HTTP 可观察项**；
  4. #4 → `status != COMPLETED` 时三个编号字段**均为空**，并按 HTTP 实际分岔：`INCOMPLETE` / `CANCELLED` →
     `code=200` 且**有** `renderedText`；`FAILED` / `TIMED_OUT` → 业务码 **500** + `data=null`；
  5. **在该段正文里加一条自检纪律**（不只写进本 D 条目）：
     **判据必须能在 HTTP 响应里观察到；服务层内部对象的字段不算判据。**
- **理由**：
  - **判据的生命线是可验证**：判据写出来是给人/脚本照着勾的；指向响应里**不存在**的字段，等于把"不可验证"
    伪装成"已验证"（本项目最贵的一类错误）。
  - **与 §6 第 6 项是同族反向失误**：`CLAUDE.md` §6 第 6 项防的是把**表象**当**结果**（HTTP 200 被读成成功），
    这条防的是把**内部**当**表象**（`Outcome` 的字段被当成响应字段）——**病根都是"拿错了层"**，
    所以纪律也放在同一个家族里写。
  - **错的是转述不是实现**：controller 的映射与 `toVO` 本来就是扁平的、`report` 从不下发（`AgentInvestigationVO`
    根本没有 `report` 字段），所以修文档即可，改代码反而会破坏契约。
  - **实证来源**（2026-10-07 本机真实 HTTP，`mode=fixed`、零模型调用）：`COMPLETED` + 三段 `renderedText`
    （扁平、无 `failureCode` 键）；非 `DEPT_ADMIN` → `HTTP 200 + {"code":403,...}`；开关关 → `HTTP 404 + {"code":404,...}`。
- **代价**：
  - **同类风险不止这一处，本轮不假装全仓已清**。已核实仍在的候选（都是"描述 `Outcome`/内部对象"的句子，
    在各自语境里是**合同/服务层**描述，**未必都要改**——要点是**别把它们当 HTTP 判据用**）：
    `docs/AGENT-PLAN.md:244`（容量拒绝注："`report` 与 `renderedText` 均为 `null`"，却挂在"HTTP 层 409"那句旁边）、
    `docs/AGENT-PLAN.md:263` / `:276`（最终短读取复核表与权限撤销表："`report == null`、已核实事实保留在 `evidence`"）、
    `docs/AGENT-PLAN.md` §3.3 的状态组合表（整表是**运行状态**口径，不是 HTTP 形状）。
  - 判据 **#2 / #5 本轮未改**——它们本来就是 HTTP 可观察的（`data.renderedText` 三段、开关关 404）。
  - 这条纪律只能防"文档把判据写错层"，防不了反向漂移（实现变了而文档没跟上）；
    也防不了"判据对但没人真跑"——后者靠"先红后绿"与真机走查，不靠本条目。
  - 修的是**对外表述**，不改接口行为、不改契约、不改评测集与期望。
- **关联**：`deploy/DEMO-SCRIPT.md`（调查助手 · API 版：样例 + #1/#3/#4 + 判据纪律）；
  `src/main/java/com/workorder/controller/AgentInvestigationController.java:74-91`（`FORBIDDEN` 映射与 `toVO`）；
  `src/main/java/com/workorder/common/vo/AgentInvestigationVO.java`（扁平 VO，无 `report` 字段）；
  `src/test/java/com/workorder/agent/AgentInvestigationWiringTest.java`（`FORBIDDEN` 用例）；
  `docs/AGENT-PLAN.md` §3.2（成功/失败响应行）；`CLAUDE.md` §6 第 6 项；D103（同一批证据来源）
## D105 · P7 走查收尾：A 组 4 条 + B 组十站全过 + 三条 UI 发现项 + 五条工具教训

- **日期**：2026-10-07
- **问题**：P7 的 ① 浏览器走查此前只有"口述级"证据（D102 已把它记为代价）。补一轮**逐站走查**，
  同时把这几轮排查挣到的工具教训固定下来。
- **选择**：逐站走查**留痕入档**；三条 UI 发现项**登记为待办、本轮不改代码**；五条工具教训写入 `CLAUDE.md` 与 runbook。
- **理由**：
  - 走查是**人眼判据**——探针、`handle_code`、业务日志全绿也可能页面错（D73 的先例）。本轮它起到了两次**交叉印证**：
    **角色管理**证明"服务器上不存在 D73 式双编码"；**SLA 配置**的 30/120 与运行事实（19:03:14 接单 → 19:33:54 释放、
    分诊重算 `finish_minutes=120` → SLA 21:03:02）**逐项吻合**。
  - 三条发现项都是**呈现问题**，不是协议缺陷；改它们要动前端文案/表单，属独立一轮。
- **五条工具教训（本轮实测挣得）**：
  1. **判据要落在直连 API**：页面上的结果可能是**上一次请求留下的旧响应**，重建镜像/更新代码**不会自动刷新它**。
     本轮页面显示旧文案、而同一时刻直连 API 返回新文案——还一度被误读成"服务器在跑旧代码"。
  2. **`Built <秒>` 的耗时是"是否真编译"的判据**：`Built 20.7s` = 层缓存命中（**没编译**）；
     `--no-cache` 后 `98.5s` 才是真编译。BuildKit 报 `Built` **不等于**镜像内容更新。
  3. **`.jar` 是 ZIP，不能直接 `grep`**：常量被压缩，必须先 `unzip` 再 grep；且 Spring Boot fat jar 的应用类在
     **`BOOT-INF/classes/`** 下、不在根目录。检查前先自证路径（`unzip -l | grep -c`）。
  4. **历史日志 ≠ 当前状态**：日志里那行 `finish_months`（正确应为 `finish_minutes`）是**当时那段代码写的**，
     重建不会改写历史；拿它当"现在还是旧代码"的证据是**拿错了层**。
  5. **给命令的规范**（本轮连续给错 6 条命令、害委托方白跑）：代码块内**只能有命令**，不得混解释文字；
     需要替换的值用**中文提示**而不是 `<尖括号>`（`<` 会被 shell 当重定向）；路径**先自证**再拿去 grep。
- **代价**：
  - 三条 UI 发现项**仍未修**（本轮只登记），其中「已完成」歧义最容易在演示时被误读；
  - 走查覆盖的是**本机浏览器 + 服务器这一套数据**，不等于多环境多数据下页面都正确；
  - **525 只是一次性现象**（10/10 复测全 200），**根因未定位**——诊断三件套已入 runbook，但没有可复现证据。
- **关联**：`ASYNC-SCHEDULING-PLAN.md` §P7 结果区·第四轮；`CLAUDE.md` §5；`deploy/DEPLOY-RUNBOOK.md` 常见失败表；D99–D104

> **更正块（2026-10-07，同一轮收尾；上面原文保留）**：本条目里"三条 UI 发现项**登记为待办、本轮不改代码**"（"选择"）
> 与**代价①**"三条 UI 发现项**仍未修**"**已作废**——三条各自的处置是：
> ① 「已完成」歧义 → **已改**为「**调查已完成**」（`frontend/src/types/agent.ts` 的 `AGENT_STATUS_MAP`；它指调查终态
> `COMPLETED`，与工单状态「已释放」并排不再被读成"工单已完成"）；
> ② 注册页部门输入框 → **已补**常驻标题「**部门 ID（选填）**」（`frontend/src/views/login/RegisterView.vue`；
> 标题放在输入框**上方**，避免这一行相对其它字段缩进、把走查判据里的"无错位"打掉）；
> ③ 站内信 `888/902` 每条一天的超时噪声 → **只登记不改**（本轮）：已写进 `deploy/CLEANUP-BEFORE-DEMO.md` §4 第 4 条
> ——演示前把这两张单的 `sla_deadline` 推到未来。
> 本条目其余部分**不受影响**：五条工具教训、"走查是**人眼判据**、只覆盖本机浏览器 + 服务器这一套数据"这条边界。

## D106 · 两条小改（工单编号下拉可手填 + 建议目录补「下一步在提交人侧」）+ **L128 上报与待裁**

- **日期**：2026-10-07
- **问题**：
  ① 调查页的「工单编号」只有手填框——"记不住单号"是常态；而列表接口**本来就支持按部门 + 单号模糊搜索**，
     没理由让人盲记。
  ② 建议目录（`AgentSuggestion`）只有 `CONTACT_ASSIGNEE` / `ESCALATE_TO_DEPT_ADMIN` / `WAIT_FOR_CLAIM`，
     **表达不了"下一步在提交人侧"**；而状态机明确写着：`AWAIT_APPROVAL` 时「验收通过 / 驳回」**只允许提交人**做。
     这个缺口是从**冻结集槽 01 的 `pending` 注**意识到的（原文："'指向提交人验收'在现有建议目录中没有对应项
     ——该期望暂不可表达，需先扩建议目录才能判"）。
- **选择**：
  1. **前端**：`/agent/investigation` 的「工单编号」由 `el-input` 换成 `el-select`
     （`filterable` + `remote` + `clearable` + **`allow-create`**），远程调 `listOrders({orderNo, page:1, size:20})`；
     **保留手填**——列表有分页与条数上限，真实环境远不止 20 条。关键词为空不发请求；远程搜索走**全局** axios
     （普通列表接口），调查请求仍走 `api/agent.ts` 的 90s 独立实例；`request.ts` 的全局 15s **未动**。
  2. **后端**：`AgentSuggestion` 新增 `WAIT_FOR_SUBMITTER_ACCEPTANCE("等待提交人验收，必要时提醒其处理")`；
     `FixedFlowInvestigator.suggestionsFor` 的 `ORDER_STATUS` 分支按**证据里的 `order.status == AWAIT_APPROVAL`**
     给出它（否则业务默认 `mode=fixed` 永远走不到这条目录）；按 §11-4 只加**禁止项**（不做"必须建议 X"）：
     `AgentReportValidator.checkProhibitedSuggestions` 新增第三条——`order.status` 不是 `AWAIT_APPROVAL` 时
     不得给这条建议，失败码**复用 `REPORT_INVALID`**。
  3. **⚠ 必须上报委托方裁决（不自己拍）**：缺口是从**冻结集**槽 01 的 `pending` 注意识到的。按手册
     **`AGENT-LEARNING-EVAL.md`:128**（"开发集用于改 prompt/规则；冻结集只用于最终检验。若根据冻结失败调优，
     该集变成开发材料，另建 holdout。"）这里有两种处置：
     - **处置一**：认定该 holdout **自本轮起已不是"最终检验集"**，下次最终检验须**另建新 holdout**。
       **代价**：要重写 24 例（用例、fixture、期望、矩阵口径）——工作量最大，但口径最干净。
     - **处置二**：认定槽 01 的 `pending` 注本身就是**"待补前提"**（它登记的是"目录缺项 ⇒ 该期望暂不可表达"，
       不是"跑出来的失败"），所以**不算据冻结失败调优**。
       **代价**：这个判断将来可能被挑战——同一份记录里既有"我按它改了实现"、又有"它不算开发材料"，
       边界只能靠解释维持。
     **本轮不选**：本记录只登记问题与两种处置，**选择权留给委托方**。
  4. **本轮明确不做**：**不把槽 01 升级成硬判据**（`expect_*` / `pending` / `note` **一字不动**）、
     **不改任何冻结期望**、不重跑真模型、不动业务默认（`agent.investigation.enabled` 默认关、`mode` 默认 `fixed`）。
- **理由**：
  - 两条改动的规则来源都是**状态机与业务语义**，不是评测期望：① 下拉候选范围与助手准入**同源**——`DEPT_ADMIN`
    的列表过滤走 `resolveDepartmentScope` → `departmentMemberIds`，与受理层是**同一个方法**（D85），
    所以"下拉里选得到的单，助手一定允许查"；② "下一步在提交人侧"由状态转移表给出（`AWAIT_APPROVAL` 的下一步
    只有 `APPROVE`/`REJECT`，服务层再按 `submitterId` 校验身份）。
  - **为什么扩目录而不是改渲染**：报告只交编号、正文由后端渲染（§3.2）——新的"下一步方向"必须是一条**目录项**，
    否则只能往渲染器里塞自由文本，那正是本项目一直拒绝的形态。
- **怎么验证的（判据）**：
  - `mvn -o test`：**364 / 0 / 0** → BUILD SUCCESS（原 361 + 本轮新增 **3**：渲染文案 1、固定流程分支 1、禁止项③ 1）。
  - `cd frontend && npm run build`：**EXIT 0**。
  - 本机实跑（`mode=fixed`、**零模型调用**）：对一张 `AWAIT_APPROVAL` 的单发起调查，`renderedText` 的
    【下一步核实建议】段出现新文案（单号与原文见交付报告）。
  - 老三条禁止项行为**不变**：`assignee` 未知 / `accept_events` 为空时仍不得含 `CONTACT_ASSIGNEE`（既有用例仍绿）。
  - **冻结集两个 JSON 的 SHA256 跑前跑后一致**（holdout `DA3DB1C3…D83`、dev `057BF3E4…00A`）。
- **代价**：
  - ① **L128 的选择权在委托方**：在裁决之前，"该 holdout 是否仍是最终检验集"处于**未定**状态——
    这段时间**不要拿它的读数当"最终检验结论"**。
  - ② 新目录项是**行为变化**：模型侧今后可以给出这条建议（此前目录里没有它）；新禁止项③会**收紧**校验——
    若某次模型跑因此在某槽失败，先看是不是"状态不是 `AWAIT_APPROVAL` 却建议了等待提交人验收"，
    **不要直接归因成"模型变差"**。
  - ③ 下拉的**分页边界**：只列前 20 条，超出的单号必须手填（页面上已写明）；`allow-create` 需要
    "输入后按回车"才落成新值——页面也写了这句提示。
  - ④ 本轮**没有浏览器走查**（只做了构建 + 真机 HTTP）：下拉的交互（远程搜索、`allow-create` 的回车/失焦行为）
    **尚无人眼判据**，下次演示前按 `DEMO-SCRIPT` 的页面版步骤过一遍。
- **关联**：`frontend/src/views/agent/InvestigationView.vue`；`AgentSuggestion` / `FixedFlowInvestigator` /
  `AgentReportValidator`；用例 `AgentReportRendererTest` / `FixedFlowInvestigatorTest` / `AgentMinimalLoopTest`；
  `docs/AGENT-PLAN.md` §3.1（前提列 + 建议目录）/ §11-4；`deploy/DEMO-SCRIPT.md`（页面版第 2 步）；
  `docs/agent-design/AGENT-LEARNING-EVAL.md:128`；`scripts/agent-eval-holdout.json` 槽 01；D85、D103、D105

- **追加边界（2026-10-08，此前漏登记）**：上文那句"**下拉里选得到的单，助手一定允许查**"**只在账号是纯
  `DEPT_ADMIN` 时成立**。依据是本地代码（可复核）：`WorkOrderServiceImpl.applyRoleFilters`（`:506-541`，
  由 `:492` 的 `wrapper.and(...)` 包住）在账号**还带 `HANDLER`** 时，会把
  「**自己接的单**（`assignee_id = 我`，**不限部门**）」+「**`PENDING` 未分配池**」（`status='PENDING' AND assignee_id IS NULL`，同样**不限部门**）**并进**列表；带 `SUBMITTER` 时还会并进「`submitter_id = 我`」。
  这些分支**都不做部门过滤**，所以列表里**可能出现**助手按受理层范围（`resolveDepartmentScope` → 同部门提交人集合）
  **不接受**的单 → 对这张单发起调查会命中**业务 code=403**（HTTP 200）。
  **处置**：演示账号用**纯 `DEPT_ADMIN`** 时无影响；多角色叠加时按页面**现有的 403 分支**呈现，
  **不新增错误处理**，也不在前端猜"哪些候选一定可查"。**代价**：多角色账号在演示里会看到
  "列表能选、点了 403"这种组合——它是**口径差异**而不是缺陷；真要消掉，得让列表接口与受理层共用同一套
  范围谓词（属独立一轮，本轮不做）。
