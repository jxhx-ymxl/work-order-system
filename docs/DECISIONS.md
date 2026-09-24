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
- **代价**：需要一次性建库（`work_order_test`）并导入 `sql/init.sql`，已写入 `README.md` §5.1；测试库会累积少量测试数据（但它本来就是测试库）。
- **关联文档**：`README.md` §5.1、`INVARIANTS.md` §三、I9

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
- **关联文档**：`src/test/resources/application-test.yml`、`README.md` §5.1；生产 URL 见 `src/main/resources/application.yml`（**待裁决**）

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
- **关联文档**：`src/test/java/com/workorder/service/WorkOrderFlowServiceTest.java`、`INVARIANTS.md` I9、`README.md` §5.1

---

## D24 · 测试必须使用独立的 Redis DB，而不只是独立 MySQL 库

- **日期**：2026-09-23
- **问题**：P0a 把测试指向独立 MySQL 库后，测试就不会影响运行中的应用了吗？
- **备选项**：① 只隔离 MySQL；② Redis 也隔离（`spring.data.redis.database`）
- **选择**：② 在 `src/test/resources/application-test.yml` 设置 `spring.data.redis.database: ${TEST_REDIS_DB:1}`，应用仍用默认 DB 0
- **理由**：测试与应用共用同一个 Redis 实例与 DB 时，**测试会清掉应用正在用的键**。
- **反转留痕（本轮代价最大的一处）**：**原以为**隔离 MySQL 就够了 → P0a 预演压测时发现 **250 次"成功"提交其实全部业务失败**（HTTP 200 + body `code=500`，`Duplicate entry 'WO-20260923-00260' for key 't_work_order.order_no'`）→ **后来发现** `OrderNoGeneratorTest:72` 会 `redisTemplate.delete(key)` 删除每日编号 key `order:seq:<日期>`，而测试与运行中的应用共用 Redis DB 0，于是应用计数器被清零到 261，而库里今日单号已到 268，之后每次提交都撞唯一键 → **因此改为**测试用 Redis DB 1。另有两处连带教训：**① 压测脚本必须校验响应体的业务 `code`，只看 HTTP 状态会把"HTTP 200 + code=500"计成成功**（这是本轮"250 ok"假象的直接原因）；**② 共用外部状态（Redis、MQ、对象存储）的测试隔离必须逐项确认，不能因为隔离了数据库就认为完成**。
- **代价**：测试与应用的 Redis 数据不再共享，需要在测试库侧重建依赖的键（当前测试只依赖编号 seq 与驳回 token，均在测试内自建，无额外成本）；`TEST_REDIS_DB` 可覆盖，CI 若用独立 Redis 实例可设回 0。
- **关联文档**：`src/test/resources/application-test.yml`、`INVARIANTS.md` I9、`README.md` §5.1

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
