# 工单调查助手：设计定稿 v1

日期：2026-10-05（Asia/Shanghai）  
项目：D:\new demo\work-order-system  
状态：八项业务取舍已确认；技术契约与初始限额为本轮设计建议，尚未实现或压测。

**结论：新增部门主管使用的只读工单调查助手。模型根据记录选择下一步查询；后端掌握权限、工具执行、证据校验与终止权。保留原分诊链路，必须与强固定流程做公平对照。**

本轮仅在会话输出目录保存设计材料；未修改项目文件、未提交、未调用真实模型、未连接业务数据库执行评测。学习、评测、简历与面试材料见同目录 AGENT-LEARNING-EVAL.md；逐题取舍见 GRILL-DECISIONS.md；术语候选见 CONTEXT.agent-draft.md。

## 1. 先挑战计划，再确定值得做的部分

| 挑战 | 项目证据与裁决 | 接受的代价 |
| --- | --- | --- |
| 只是“查详情再总结”，为什么不是普通 LLM 功能？ | 现有详情已包含工单和日志，不能拆成两个工具伪造多步。只有下一步查早期记录、规则还是对照单由模型根据结果决定，才体现 agent 特征。 | 强固定流程同样好时，应让它成为业务默认方案。 |
| 系统真的有维修知识吗？ | 提交验收没有维修过程/解决方案字段。首版做流程调查，不做维修知识问答。 | 不承诺查出根因或给出验证过的修复办法。 |
| “复用权限”到底复用哪条规则？ | 列表按多角色取并集；升级详情有跨部门分支；独立日志入口没有归属校验。新模块采用严格部门范围。 | 需要受限读取入口，不能把现有 Controller 直接注册成工具。 |
| 是否需要自动派单？ | 用户选择只读，只给主管事实与核实建议。 | 后续操作仍由人通过现有业务入口发起。 |
| 证据不足时必须给原因吗？ | 不必须；明确未知也可以是完成的调查。 | 不生成没有证据的原因假设。 |
| 为什么首版手写？ | 一个助手、四个窄工具、单次调查、无记忆与续跑；学习目标是看懂循环。 | 自己维护协议、超时、取消、trace 和测试，不是天然优于框架。 |
| 做成 agent 就有价值吗？ | 固定流程对照是必做验收。 | 实验可能证明学习价值成立，业务增益不足。 |

八项选择：① 部门主管只读调查；② 本部门提交人范围，升级单无例外；③ 可按需扩查同部门对照；④ 事实/缺口/核实建议，不猜原因；⑤ 单次报告，无跨次记忆；⑥ 重启不续跑，明确未完成；⑦ 模块专用小循环，不用编排框架；⑧ 强基线公平对照必做。

## 2. 业务契约与边界

典型问题：“这张工单为什么还没处理完？已有记录能确认什么，还缺什么信息，我下一步该找谁处理？”

- **本部门**：提交人当前部门等于当前主管部门，沿用现有列表的含义。不是处理人部门，也不是提交时历史部门，工单表没有独立部门归属字段。
- **调查完成**：形成符合契约的报告，不等于根因查明、遍历全库或问题解决。
- **无记录**：明确范围内的查询成功且没有对应记录。工具失败、截断、未查询均不能表述为“没有”。
- **事实层次**：能确认“数据库记录为处理中”“提交人描述网络不通”；不能直接确认现场状况、责任或线下是否有人工作。
- **同部门对照**：只是可见范围内的记录，不能解释为处理人全局工作量，更不能直接解释为延误原因。
- **一次调查**：本轮独立读取；下一次必须重新指定对象、鉴权和取证。

首版不包括自动定责、派单、改 SLA、催办消息、自由 SQL/URL、向量库、跨次聊天、长期任务、流程 DSL、多 agent 或重启续跑。核实建议只展示给用户，不自动发送给他人。

## 3. 为什么这里探索 agent，而分诊保持原样

| 维度 | 既有分诊 | 本次调查 |
| --- | --- | --- |
| 目标 | 文本转成受限类型/优先级 | 指定工单与问题，按需取证 |
| 下一步 | 提交→outbox→MQ→分类→写回→重算 SLA 已确定 | 已见记录影响是否继续、查哪个工具 |
| 模型权限 | 提供分类值，写回由业务代码决定 | 请求白名单查询，是否执行由后端决定 |
| 可靠性重点 | 持久投递、消费去重、重投、失败终态、事务 | 授权、预算、证据、取消、部分失败 |
| 验证重点 | 分类质量与失败语义 | 动态取证相对固定流程的净收益 |

**分诊不缺动态选择下一步的能力，更需要确定的写回与失败契约。调查有可变取证路径，值得实验，但不代表已证明非 agent 不可。**官方工程资料也以预定义代码路径与模型动态引导过程区分 workflow 和 agent。[外1]

模型依然是不可靠外部依赖；增加循环不会免除超时、校验与失败责任。

## 4. 最小架构

运行路径：

主管请求 → 登录/权限/准入 → 固定预读主工单 → 模型请求工具或报告 → 后端验证并执行 → 证据回传 → 再次决策 → 输出前复核 → 确定性渲染。

### 4.1 同进程、单请求

- 同一 Spring Boot 内新增 com.workorder.agent；默认 agent.enabled=false。配置无效只禁用调查能力，不让现有工单/分诊启动失败。
- 建议接口 POST /api/agent/investigations，请求仅 orderId、最多 500 字的 question。身份、部门、权限不接受客户端或模型覆盖。
- 使用 Spring MVC DeferredResult 返回一份结果；专用单工作线程、零等待队列，满载直接拒绝，不在 Servlet 线程回退执行。HTTP 连接仍等待，但 Servlet 线程不全程阻塞在模型上。[外4]
- 无任务表、MQ、XXL-Job、outbox：这次没有可靠投递业务写或跨重启恢复的承诺。单次调查不等于一定要同步阻塞。
- 独立 AgentModelClient 与 agent.model.* 配置；复用 HTTP/JSON 基础能力，不调用分诊业务 service，也不改变分诊客户端、提示词或超时。
- 传输初选 Java 17 HTTP client；取消、大小限制与供应商工具协议需实测。现有分诊文本 JSON 成功不能证明 native tool calling 已可用。
- 首个实施关卡是用虚构数据做独立能力探测：工具请求→callId→结果回传→下一轮→终答。探测显式执行，不在每次业务启动时自动花费真实调用。

### 4.2 数据库取舍

首版复用现有数据源，新增受限读取 facade/Mapper，固定参数化 SQL，每次工具短事务读取后释放连接，再调用模型。整个循环、Controller 不包大事务。

先不增加第二连接池或 DB 账号：并发 1，先测量竞争。代价是**应用层只读，不是数据库权限级隔离**；readOnly=true 不是阻止所有写入的安全边界，工具不能依赖写 service。

现有 Hikari 最大 20、取连接超时 30 秒。SQL 查询超时不包含这段等待，不能宣称“工具一定 1 秒内结束”。期限到达先停止接受结果、取消操作；worker 真正退出前不释放名额。若饱和实验表明不可接受，再评审独立小只读池/账号及其连接数、配置、运维成本，不静默改变共享池。

## 5. 工具契约

工具调用是模型返回名字和参数，应用验证后执行函数，再把结果交回；模型不会自动获得数据库权限。[外2][外3]

入口固定预读一次主工单，注册 root 引用；不浪费一次模型调用让它“选择”必读动作。两组实验均计预读成本。可选工具四种：

| 工具 | 输入 | 返回与约束 |
| --- | --- | --- |
| read_order_context | orderRef | 主单/已发现对照单字段 + 最近 20 条日志 + 证据 + 截断标记/早期游标。现有详情本就含日志，不人为拆分。 |
| read_earlier_events | orderRef、cursor | 更早一页，最多 20 条；游标绑定本轮、工单及边界，不能猜或无限翻页。 |
| find_related_orders | relation、可选 cursor | 同部门关系查询摘要，最多 10 条与本轮引用；关系锚定主单，不接受任意人/部门/SQL。 |
| read_sla_context | orderRef | 当前存储截止点、查询时间、是否过点、现有扫描状态是否适用、当前规则；绝不重算/升级/告警。 |

### 5.1 关系与投影

- SAME_ASSIGNEE_ACTIVE：主单当前处理人在本部门可见、状态 ACCEPTED 或 IN_PROGRESS 的其他单；排除主单。明确不是全部未结单。无处理人返回 NOT_APPLICABLE。
- SAME_SUBMITTER_RECENT：主单提交人近 30 天创建的其他可见单，排除主单。30 天是初始建议，不是相似语义检索。
- 相关单按 created_at/id 降序，日志按时间/id 降序，采用固定 keyset 游标；模型不能控制 SQL/排序片段。
- 多取 1 条用于 hasMore；“本页 10 条且有更多”不能写成“总共 10/12 条”。不为了展示总数扫描全库。
- 模型接收最小流程字段、人员本轮代号和必要文本；姓名由后端授权映射，排除手机号、凭据、无关资料。
- 描述/备注每项最多 1000 字，明确 contentTruncated。必要信息被截断就列缺口，首版不支持任意全文提取。
- 每轮最多 21 个工单引用、100 项证据；输出报告最多 8 条事实。上限是初值，不是测量结果。
- 每次返回查询范围、过滤条件、读取时刻和完整性。

### 5.2 统一结果与错误

结果含 callId、toolName、status、observedAt、scopeLabel、data、evidence、hasMore、nextCursor、limitations。

- OK：成功，包括合法空集；空集只对该查询范围有效。
- NOT_APPLICABLE：无适用对象，不等于技术故障。
- INVALID_ARGUMENT / UNSUPPORTED_TOOL：执行前拒绝，允许最多一次协议纠正。
- UNAVAILABLE：依赖/查询失败，不能返回空数组伪装成功。首版不自动重试数据库工具：停止取证，已有事实经最终复核后可返回INCOMPLETE；无可用事实则FAILED。模型不得把该失败改成COMPLETED。
- 越权/授权失效：终止，不把敏感详情交给模型。

工单正文与日志备注都是不可信数据，其中“忽略指令”“读取另一部门”“执行 SQL”不会增加权限或工具。

## 6. 权限执行点

1. 候选权限码 order:investigate，默认授予部门主管；按权限码鉴权，多角色不扩大部门范围。
2. 入口提取 actorId 和内部登录态标识，显式传给 worker，不假定异步线程自动继承 StpUtil 上下文。登录 token 不进入模型或日志。
3. 每次工具重新校验会话、账号、权限、主管当前部门。部门在首读后固定；主管调部门则终止，不切换到新范围。
4. SQL 强制关联提交人的当前部门。ESCALATED_ADMIN、SYS_ADMIN、多角色并集均无绕过。
5. 模型只用本轮 orderRef；引用难猜不是授权措施，每次查询仍做部门过滤。禁止复用无归属校验的日志入口。
6. 不存在与无权访问给相同“不可访问”响应，建议业务 code=404；模块权限不足用 403，不泄露跨部门单存在性。
7. 最终检查所有拟引用主单、对照单、聚合结果的范围；权限丢失时丢弃报告内容。

已发给供应商的文本不能因后续撤权而收回。最后复核防止继续读取和最终输出，但不宣称零时间窗口或可撤回授权。

## 7. 循环、预算与退出

### 7.1 执行算法

1. 准入后创建内存 RunContext：runId、身份、部门、deadline、计数器、证据、调用 ID。
2. 固定预读 root；无可用证据时停止，不让模型编报告。
3. 模型返回一个工具请求或最终结构化草稿。首版串行，不执行多工具并行批次。
4. 严格验证名字、枚举、类型、必填、额外字段和长度；不从自然语言猜调用，不用 asInt 等静默强转掩盖错误。
5. 合法请求先计数、鉴权、检查剩余时间，再执行；按原 callId 回传证据。
6. 相同工具+规范化参数可命中本轮快照缓存，仍重新鉴权、仍计工具请求。连续重复且无新证据以 NO_PROGRESS 终止；同 callId 不同参数是协议错误。
7. 草稿经证据/建议前置条件与新鲜度复核后，由后端渲染。
8. 正常完成、超时、断开共用一次性完成标记；finally 清理上下文，worker 实际退出才归还名额。

禁用工具后的调用、非法批次、错位调用 ID 等按协议错误处理，不执行其中“看起来合法”的部分。协议纠正按供应商要求返回配对的错误反馈或终止，不能破坏消息关联。

### 7.2 初始预算：全部待测

| 项目 | 初值 | 计数口径 |
| --- | --- | --- |
| 并发/队列 | 1/0 | 真正存活的 worker，禁止积压 |
| 总期限 | 60 秒 | 从准入开始；前 50 秒调查，后 10 秒预留复核/响应；剩余不足不启动新操作 |
| Servlet/前端等待 | 65/70 秒 | 同时核对反向代理和前端客户端超时，只调整调查路由 |
| 物理模型请求 | ≤5 次 | 正常请求、传输重试、协议纠正全计入；最后一次只允许报告 |
| 动态工具请求 | ≤4 次 | 参数拒绝、重复、缓存命中都计入；预读另记 1 次；末尾复核单独计 DB 成本 |
| 单次模型期限 | ≤12 秒 | 与剩余调查时间取小值，包括建连和读取完整有限响应 |
| 传输重试 | 全程≤1 次 | 瞬态网络/429/部分 5xx；计入五次预算；401/403/坏参数不重试；Retry-After 等不起就结束 |
| 协议纠正 | 全程≤1 次 | 与传输重试分开计数，但共同消耗五次额度 |
| SQL 执行 | 单语句目标≤1 秒 | 局部 Mapper 超时；不包含现有 30 秒取连接等待，取消能力另验 |
| 响应/上下文大小 | 模型响应64 KiB；工具结果16 KiB；每次模型输入48 KiB | 包括历史和工具定义；按完整证据项取舍，不截坏 JSON，不无界追加 |
| 输出 token | 单次≤1024 | 先验证供应商实际支持参数；usage 缺失记 unknown，不填0 |

不承诺精确总输入 token/美元硬上限：历史反复发送也有成本，tokenizer 与推理计费取决于供应商。首版硬控调用、字节、输出、时间并记录 usage。严格费用上限需另补对应计量/预留机制与供应商预算约束。

### 7.3 取消、崩溃与最坏情况

- Future 超时不等于 I/O 停止。Java HTTP client 取消是尝试，释放可能异步，上游也可能已经收到请求。[外5]
- 取消模型请求并关闭响应体，停止后续调用；SQL 尝试取消。底层 worker 未退出时保留准入名额，避免暗中堆积。
- 60 秒内最终授权校验不成，不返回缓存业务内容；只返回未完成状态。65 秒是健康进程响应目标，不是 JVM 停顿/崩溃下的绝对保证。
- 上游计算/费用可能在取消后继续；本地资源不能确认释放则暂停新调查准入、告警并人工恢复，不增加线程掩盖。
- 进程退出由连接断开/客户端期限收口；没有持久 RUNNING 行，也不自动续跑。刷新不能自动重放 POST；本地遗留标记只能显示“上次结果未知/未完成”。
- 前端阻止重复点击但不承诺跨请求幂等；重复 POST 可能再次计费。没有业务写，不承诺 exactly-once。

## 8. 证据报告与终态

### 8.1 不让引用替谎言背书

工具生成 evidenceId、kind、subjectRef、typedValue、observedAt、sourceRef、completeness。类型如 ORDER_STATUS、RECORDED_TRANSITION、CURRENT_DEADLINE、RELATED_PAGE、REPORTED_STATEMENT。

模型终答只选择 factIds、unknownCodes、nextCheckCodes 及其引用；不自由生成事实正文。后端模板渲染数值/时间/状态/姓名，自动补查询范围、截断、失败与核心主单状态，验证事实类型和建议前提。来源只是提交人的陈述时必须写“提交人描述……”，不升级为客观根因。

建议也是受限的：主单有处理人且处于接单/处理中，可建议找当前处理人核实；AWAIT_APPROVAL 可建议找提交人核实验收（现有 approveOrder 只允许提交人）。不能从备注提取任意收件人并执行动作。

这是用自由文风换可验证性的首版选择，不保证所有自由问题都能回答；超出流程调查范围时明确说明。即便 evidenceId 存在，也不表示它支持任意因果推断。

### 8.2 执行与证据是两个维度

| 字段 | 定义 |
| --- | --- |
| executionStatus | COMPLETED：契约正常完成；INCOMPLETE：超时/预算/工具失败/状态变化等阻止完成；FAILED：无可用报告或不可恢复协议/配置问题 |
| evidenceCoverage | SUFFICIENT_FOR_REQUEST / INSUFFICIENT / NOT_EVALUATED；针对问题，不从执行状态推导 |
| stopReason | NORMAL、DEADLINE、BUDGET、TOOL_UNAVAILABLE、MODEL_UNAVAILABLE、PROTOCOL_ERROR、NO_PROGRESS、STATE_CHANGED 等 |
| causeStatus | 首版通常 UNESTABLISHED，未评估则 NOT_EVALUATED；不编原因概率 |

正常调查但没有线下原因资料，可以 COMPLETED + INSUFFICIENT + NORMAL。查询没执行完不能用这个组合掩盖，必须 INCOMPLETE。不相关的早期日志仍有分页，并不自动等于调查未完成；必须明确实际查到的范围。

报告含 runId、起止/证据时刻、查询范围、事实、缺口、核实建议、终态、资源统计。执行状态、失败提示、计数由后端决定，模型不能自报成功。

### 8.3 Result 和 UI 契约

- 入口失败用现有业务 code 400/401/403/404；忙碌/未启用建议用既有整数重载 Result.fail(503, ...)，data=null，不假设 ErrorCode 已有503枚举。
- 已接收且形成终态对象时用 Result.ok(InvestigationReport)，内层可为 INCOMPLETE/FAILED。code=200 只表示收到本轮结果对象，不能代表调查完成。
- 权限撤销用非成功业务 code 且不带报告；现有 fail 工厂把 data 置 null，不能假设它支持部分报告。
- INCOMPLETE 只展示经过最终复核的部分事实，明显标注“未完成”；复核失败只给执行状态，无事实。
- 现有异常常用 HTTP200 携带失败 code；UI/eval 必须同时检查封装 code、executionStatus 和证据契约。

### 8.4 示例（虚构，不是项目实测）

> 执行：调查已完成，但证据不足以确定延误原因。  
> 已确认：状态记录为 IN_PROGRESS；最近一次操作记录发生在两天前。[E1,E2]  
> 已确认：同一处理人在本部门可见的 ACCEPTED/IN_PROGRESS 对照查询返回10条，存在下一页。[E3]  
> 无法确认：这两天的线下进展、处理人全局负荷、延误原因。  
> 下一步：向当前处理人核实进展与需要协调的事项。  
> 范围：提交人当前部门；对照未遍历全部页面，不能判断总量或因果。

E3 查询失败则删除该事实、标 INCOMPLETE 并说明失败，不能改写为“没有其他工单”。

## 9. 新鲜度、时间、SLA

- 每次工具读短快照并释放连接；不承诺贯穿模型调用的全库一致快照。
- 最终短读取阶段核对主单关键字段、引用对照单、日志/有界查询页面指纹：status、assignee、type/priority、triageStatus、slaDeadline、version 等；聚合要重查相同条件，不能只核一个订单版本。
- version 单独不够：markTriageFailed 不递增 version。所有拟输出证据要通过对应复核；复核 SQL/时间计入总成本。
- 权限/部门变更拒绝输出；业务关键事实变化标 INCOMPLETE/STATE_CHANGED，删掉受影响陈旧事实，建议新调查，不默默拼接或自动重跑。
- SLA 用 DB 同会话时间计算，显示+08:00；耗时用单调时钟，遵守 I10 时区一致要求。
- 存储 sla_deadline 是当前事实，当前 SLA 配置不是历史规则版本证明。
- 现有 SLA 扫描只取 PENDING/ACCEPTED/IN_PROGRESS 且 deadline < NOW；AWAIT_APPROVAL 等即使过点，也不能称必被该扫描升级。
- 分诊重算基准为 created_at + 新 finish_minutes，不是回答时间；工具不修改 deadline。
- triageStatus 不是 type/priority 的人工/AI 列级来源。模型 reason 并不必然在业务日志持久保存。
- ACCEPTED 释放扫描用 updated_at + 当前 accept_minutes；只能称当前扫描规则，不虚构历史接单时限。

## 10. 日志、资源与隔离

首版无新业务表/容器/向量库；运行上下文内存保存，终态销毁。调查不写工单操作日志，不制造业务动作。日志元数据不支持报告恢复或重启续跑。

记录 runId、内部 actorId/主单 ID、模型与提示词/工具版本、步骤、工具/参数摘要、结果状态/大小/证据哈希、耗时、调用数、usage、stopReason。不记录原始思维链；默认不记工单正文、完整 prompt、密钥、登录 token。

调查日志建议总量上限50 MiB、保留最多7天，先达者清理，实施时接入并验证日志滚动。脱敏完整轨迹仅在离线评测目录保存并清理。结构化 metadata 不等于完整审计重放。

现状后端容器1 GiB、JVM堆512 MiB，六容器且容器禁swap。没有新容器仍会增加线程、HTTP缓冲、上下文、DB/供应商竞争，不宣称零成本。开启门槛是在原限制下跑重复调查+主业务并发，检查 RSS/堆/GC/线程/连接池/主业务错误与时延，以及取消后的残留。性能阈值先按同机基线制定，目前不编造资源增量或影响率。

## 11. ADR 候选：理由、替代、代价

不占用未知的 D 编号，未写入项目 DECISIONS。

| 决策 | 理由 | 替代与代价 | 重审条件 |
| --- | --- | --- | --- |
| 独立只读 | 可学动态取证，不改事务链 | 写工具需授权、确认、审计、幂等 | 明确写操作需求 |
| 严格部门 | 消除本模块权限歧义 | 不支持跨部门升级调查 | 业务批准新的范围 |
| 小循环 | 范围窄，学习循环本身 | 自维协议与测试；框架可减少重复工作 | 多供应商、复杂图、恢复 |
| 单请求无续跑 | 不承担持久执行状态 | 断开要重做、重复费用 | 时长或恢复SLA变化 |
| 类型证据+模板 | 可验证事实和建议 | 自由文风、问题范围受限 | 可靠语义验收和新需求 |
| 同池+单worker | 少部件，先测 | 无物理隔离，池等待可能大 | 饱和实验或权限需求 |
| 强基线必做 | 收益可以被反驳 | 维护实验成本 | 范围变时更新，不取消 |

不用框架的理由不能是“无法控制循环”：Spring AI 提供用户控制工具执行，LangChain4j 有低层/高层抽象。[外2][外3] 查阅官方当前资料只核对能力，不等于验证对应发行版兼容 Boot3.3.5/Java17；本轮没有选框架版本。

## 12. 查缺补漏：事实与判断分开

| 优先级 | 已核实事实 | 建议与边界 |
| --- | --- | --- |
| 新模块接入前 | 日志入口路径无归属判断，既有 G2/F1-6 已记录 | 新 facade 必须过滤；原入口修复是独立任务，不称本轮首发现 |
| 新模块接入前 | 列表多角色并集，升级详情提前放行DEPT_ADMIN | 新范围已裁定；原业务差异是否bug未裁定 |
| 下次沿用分诊评测前 | triage-eval.py:227–229 逗号 ID 生成正则 ^order:(949,950): | 字符串测试不匹配 order:949:v0:ORDER_TRIAGE，而备选分隔符版本匹配；清理SQL生成需修，未查库不能断言现存残留 |
| 下次发布评测数字前 | triage-eval.py:145–153 提交失败未增加 insufficient_n | 信息不足组分母缩小；新评测先定分母后执行 |
| 产品能力边界 | completeOrder 没有维修过程/解决方案输入 | 不能承诺历史修复知识，不直接归为bug |
| 一致性设计 | markTriageFailed 不增version | 复核不能只看版本，本轮不改旧约定 |

D68 中清理SQL记录与逗号正则生成逻辑存在冲突，应复核实际执行记录；未执行数据库DELETE。D60–D73并非全是AI设计，D69–D73还包括调度、归档、日报、平台状态、字符集；其业务结果验证原则适用，但不意味着 agent 也需要调度基础设施。

本轮不是全仓审计；未做 EXPLAIN、真模型兼容、连接池饱和、服务器残留检查。

## 13. 后续实现落点

候选职责（尚未创建项目文件）：

| 职责 | 必须负责 |
| --- | --- |
| AgentInvestigationController + DTO | 输入、Result、DeferredResult、终态 |
| InvestigationRunner + RunContext | 循环、预算、取消、证据、调用关联 |
| AgentModelClient + 单供应商适配 | HTTP、协议、大小/期限、usage |
| InvestigationReadFacade + Mapper | 四个窄工具、统一部门范围、限量短事务 |
| ReportValidator + Renderer | 引用/事实/建议、最终复核、模板渲染 |
| AgentProperties + 专用executor | 开关、单worker、零队列、限额 |
| 模块测试 + agent-eval脚本 | 权限、协议桩、故障、强基线 |

五个切片：① 虚构工具与协议探测；② 一个真工具和部门范围；③ 动态循环+证据报告；④ 终态/取消/UI/资源；⑤ 冻结评测与基线。教学及每片验收见 AGENT-LEARNING-EVAL.md。

实施时需补权限种子增量迁移与 init 同步（无新业务表但有权限数据变更）、显式utf8mb4导入、配置/API/前端/路由超时/日志说明，以及CONTEXT、DECISIONS和模块不变量。先检查实际目录惯例再落具体文件，现有分诊只做隔离环境回归，不顺手改造。

## 14. 依据与本轮验证

### 本地依据

以下为读取时的位置；绝对项目根为 D:\new demo\work-order-system：

- D:\new demo\work-order-system\CLAUDE.md:29–39,93–103：事务/outbox/去重、禁swap、交付与业务成功判据。
- D:\new demo\work-order-system\docs\DECISIONS.md:D60–D73：D62假成功、D64来源限制、D66分母、D67事务外调用、D68实测边界。
- D:\new demo\work-order-system\INVARIANTS.md:I10,I12：时区与失败终态。
- D:\new demo\work-order-system\src\main\java\com\workorder\service\impl\WorkOrderServiceImpl.java:472–543,589–648：列表/详情/日志；:252–268,276–290：提交验收与提交人验收。
- D:\new demo\work-order-system\src\main\java\com\workorder\controller\WorkOrderController.java:60–63 与 D:\new demo\work-order-system\src\main\java\com\workorder\service\impl\WorkOrderLogServiceImpl.java:34–36：日志入口。
- D:\new demo\work-order-system\src\main\java\com\workorder\common\dto\PageQuery.java:16–26：列表过滤；D:\new demo\work-order-system\src\main\java\com\workorder\common\aop\OrderLogAspect.java:65–75：备注来源。
- D:\new demo\work-order-system\src\main\java\com\workorder\service\impl\OrderTriageServiceImpl.java:34–59,78–90：HTTP配置与文本探测。
- D:\new demo\work-order-system\src\main\java\com\workorder\service\impl\OrderTriageConsumeService.java:96–141,189–213：事务外调用、missingFields、SLA基准。
- D:\new demo\work-order-system\src\main\java\com\workorder\mapper\WorkOrderMapper.java:108–109,125–126：版本差异。
- D:\new demo\work-order-system\src\main\resources\mapper\WorkOrderMapper.xml:25–42：扫描谓词。
- D:\new demo\work-order-system\src\main\java\com\workorder\config\SaTokenConfig.java:12–19 与 D:\new demo\work-order-system\src\main\java\com\workorder\config\StpInterfaceImpl.java:50–78：登录与权限码。
- D:\new demo\work-order-system\src\main\java\com\workorder\common\Result.java、D:\new demo\work-order-system\src\main\java\com\workorder\common\ErrorCode.java、D:\new demo\work-order-system\src\main\java\com\workorder\config\GlobalExceptionHandler.java:19–64：响应口径。
- D:\new demo\work-order-system\scripts\triage-eval.py:145–153,227–229 与 D:\new demo\work-order-system\scripts\triage-eval-cases.json：评测依据。
- D:\new demo\work-order-system\pom.xml:7–21、D:\new demo\work-order-system\src\main\resources\application.yml、D:\new demo\work-order-system\deploy\docker-compose.yml：版本、池、超时、资源。

### 官方资料（2026-10-05 查阅）

仅支持概念/API能力，不代表项目兼容实测：

- [外1] Anthropic《Building effective agents》：预定义工作流与动态工具选择。URL：`https://www.anthropic.com/engineering/building-effective-agents`
- [外2] Spring AI《Tool Calling / User-Controlled Tool Execution》：框架也可由用户控制工具执行。URL：`https://docs.spring.io/spring-ai/reference/api/tools.html`
- [外3] LangChain4j《Tools》：低层/高层API；工具调用不等于JSON mode；模型支持存在差异。URL：`https://docs.langchain4j.dev/tutorials/tools/`
- [外4] Spring Framework《Asynchronous Requests》：DeferredResult与生命周期回调。URL：`https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-async.html`
- [外5] Java SE17 HttpClient：取消不保证立即生效，上游可能已收到请求。URL：`https://docs.oracle.com/en/java/javase/17/docs/api/java.net.http/java/net/http/HttpClient.html`

### 本轮实际动作

只读指定材料和直接相关代码；核对权限、字段、事务、SLA、响应语义；本地字符串匹配核查清理正则；在会话输出目录保存/回读设计稿；检查项目git状态。

没有项目修改、构建、迁移、真实模型调用、业务数据库操作、实际agent/基线实验、性能测量或提交。所有限额、样本数、验收目标都是建议，所有收益留待真实实验。
