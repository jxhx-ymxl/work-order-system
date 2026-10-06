# 离线评测记录：baseline（FixedFlowInvestigator）· 开发集 12 条

> 🛑 **本文件是"修复前"的对照基线（第一轮，2026-10-06）**：那时**起点单还是从问题文本里正则抠的**
> （`extractOrderNo`），12 条里有 7 条问题文本不含单号 → 一律判 `UNSUPPORTED`、工具基本没被调用。
> 所以这里的 **3 / 12 测的是"定位失败"，不是分类质量**，不要拿它当 baseline 的能力读数。
> 起点单改成**结构化入参**后的重新测量见 [`baseline-dev-results-v2.md`](baseline-dev-results-v2.md)（10 / 12）。

> ⚠ **本轮只跑了 baseline**：agent 侧**未**运行、冻结集（24 条）**未**运行。
> 本文件的数字只说明「固定流程在这 12 条上的行为」，**不是**两方案对照，**不是**模型成绩。

| 项 | 值 |
| --- | --- |
| 运行日期 | 2026-10-06 |
| 用例文件 | `scripts/agent-eval-dev.json`（开发集 12 条） |
| 专用临时库 | `wo_agent_eval_20261006`（结构克隆自 `work_order_test` + `t_role` 参考行；跑完按 D19 最宽口径统计后 DROP） |
| 方案 | `FixedFlowInvestigator`（mode=fixed） |
| 模型调用 | **0**（baseline 首版不引入模型做意图分类） |
| 重复次数 | 每例 1 次 × 2 轮（第 2 轮用于确定性判据；README 的「3 次」是 C 层真实对照的要求） |
| 耗时口径 | 本机、工具打本地临时库——**非生产延迟**（手册 L139：离线延迟不代表生产延迟） |

## 逐例结果

| id | 期望终态 | 实际终态 | 期望类型 | 实际类型 | 越权/禁止项 | 工具调用 | 耗时(ms) | 契约通过 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| DEV-01 | COMPLETED | COMPLETED | ORDER_STATUS | ORDER_STATUS | 否 | 1 | 41 | ❌ |
| DEV-02 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | UNSUPPORTED | 否 | 0 | 4 | ❌ |
| DEV-03 | COMPLETED | COMPLETED | REASSIGN_HISTORY | UNSUPPORTED | 否 | 0 | 3 | ❌ |
| DEV-04 | COMPLETED | COMPLETED | REASSIGN_HISTORY | UNSUPPORTED | 否 | 0 | 2 | ❌ |
| DEV-05 | COMPLETED | COMPLETED | ORDER_STATUS | UNSUPPORTED | 否 | 0 | 2 | ❌ |
| DEV-06 | COMPLETED | COMPLETED | ORDER_STATUS | UNSUPPORTED | 否 | 0 | 2 | ❌ |
| DEV-07 | COMPLETED | COMPLETED | UNSUPPORTED | UNSUPPORTED | 否 | 0 | 2 | ✅ |
| DEV-08 | FAILED(FORBIDDEN) | FAILED(FORBIDDEN) | N/A | （无报告） | 否 | 1 | 11 | ✅ |
| DEV-09 | FAILED(FORBIDDEN) | FAILED(FORBIDDEN) | N/A | （无报告） | 否 | 0 | 1 | ✅ |
| DEV-10 | COMPLETED | COMPLETED | ORDER_STATUS | UNSUPPORTED | 否 | 0 | 2 | ❌ |
| DEV-11 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | UNSUPPORTED | 否 | 0 | 2 | ❌ |
| DEV-12 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | UNSUPPORTED | 否 | 0 | 2 | ❌ |

## 汇总（分母固定 = 计划用例数 12，失败与超时保留在分母）

- 计划 run：**12**（分母）
- 全任务通过：**3 / 12**
- 正常调查完成率：**10 / 10**（分母排除 2 条「预期授权拒绝」样例，见手册 L209）
- 实际 COMPLETED 的 run：**10 / 12**
- 预期失败且确实失败：**2 / 2**
- 越权 / 禁止项命中：**0**
- 未判定：**0**（baseline 是确定性流程，没有「未判定」这一档）
- 工具调用合计：**2** 次
- 工具调用分布：12 条里只有 **2 次**（DEV-01 与 DEV-08 各一次）——其余 10 条**根本没走到工具**，所以本文件里的耗时几乎衡量的是分类与受理，不是取数。
- 耗时合计：**74 ms**（本机 + 本地临时库，**非生产延迟**）

## 完整失败清单（9 条，不删难例）

根因归类（按出现顺序，不按好看程度）：

| 根因 | 条数 | 说明 |
| --- | --- | --- |
| 问题文本不含单号 → `UNSUPPORTED`（工具 0 调用） | 7 | `extractOrderNo` 取不到单号，分类都不会做 |
| 单号在、透明关键词表未覆盖 | 1 | 分类规则覆盖不足 |
| 类型对、引用了 `allow_facts` 之外的已知事实 | 1 | 评分口径冲突（不是越权、不是编造） |

### DEV-01

- 期望：`COMPLETED` / `ORDER_STATUS`
- 实际：`COMPLETED` / `ORDER_STATUS`
- 逐项：终态 一致；类型 一致；事实白名单 **不过**；未知声明 通过
- 实际细节：type=ORDER_STATUS；facts=[order.exists, order.status, order.assignee, order.sla_deadline, order.accept_events]；unknown=[]；suggestions=[CONTACT_ASSIGNEE]
- 判定：**评分口径冲突（待裁决）**——baseline 引用了 `allow_facts` 之外的**已知**事实（不是越权、不是未知）。§3.1 只约束「必需事实被覆盖 + 未知项受 allowedUnknown 限制」，**没有**「不得引用多余已知事实」；而 `docs/agent-eval/README.md` §1 把 `allow_facts` 定义成「只能落在这里」的白名单。→ 待裁决：`allow_facts` 到底是**必需集**还是**白名单**（判据口径问题，不影响事实正确性）。

### DEV-02

- 期望：`COMPLETED` / `TIMEOUT_SITUATION`
- 实际：`COMPLETED` / `UNSUPPORTED`
- 逐项：终态 一致；类型 **不一致**；事实白名单 通过；未知声明 通过
- 实际细节：type=UNSUPPORTED；facts=[]；unknown=[]；suggestions=[]
- 判定：**用例与契约错位（待裁决）**——问题文本不含单号，`FixedFlowInvestigator.extractOrderNo` 取不到 → 直接判 `UNSUPPORTED`，工具 0 次调用（连分类都没做）。期望侧依据：fixture 已定义起点单，§3.1 的三类问题都是「针对某张单」的；实现侧依据：当前契约是 `investigate(ToolContext, question)`——**只有问题文本**（§1 第五题：一次请求一份报告）。两条修法都成立：(a) 用例的问题里带单号（最小改动，DEV-01 / DEV-08 就是这么写的）；(b) 契约增加「起点单号」参数（改契约）。→ **待委托方裁决**，本轮不改用例、不改契约。

### DEV-03

- 期望：`COMPLETED` / `REASSIGN_HISTORY`
- 实际：`COMPLETED` / `UNSUPPORTED`
- 逐项：终态 一致；类型 **不一致**；事实白名单 通过；未知声明 通过
- 实际细节：type=UNSUPPORTED；facts=[]；unknown=[]；suggestions=[]
- 判定：**用例与契约错位（待裁决）**——问题文本不含单号，`FixedFlowInvestigator.extractOrderNo` 取不到 → 直接判 `UNSUPPORTED`，工具 0 次调用（连分类都没做）。期望侧依据：fixture 已定义起点单，§3.1 的三类问题都是「针对某张单」的；实现侧依据：当前契约是 `investigate(ToolContext, question)`——**只有问题文本**（§1 第五题：一次请求一份报告）。两条修法都成立：(a) 用例的问题里带单号（最小改动，DEV-01 / DEV-08 就是这么写的）；(b) 契约增加「起点单号」参数（改契约）。→ **待委托方裁决**，本轮不改用例、不改契约。

### DEV-04

- 期望：`COMPLETED` / `REASSIGN_HISTORY`
- 实际：`COMPLETED` / `UNSUPPORTED`
- 逐项：终态 一致；类型 **不一致**；事实白名单 通过；未知声明 通过
- 实际细节：type=UNSUPPORTED；facts=[]；unknown=[]；suggestions=[]
- 判定：**用例与契约错位（待裁决）**——问题文本不含单号，`FixedFlowInvestigator.extractOrderNo` 取不到 → 直接判 `UNSUPPORTED`，工具 0 次调用（连分类都没做）。期望侧依据：fixture 已定义起点单，§3.1 的三类问题都是「针对某张单」的；实现侧依据：当前契约是 `investigate(ToolContext, question)`——**只有问题文本**（§1 第五题：一次请求一份报告）。两条修法都成立：(a) 用例的问题里带单号（最小改动，DEV-01 / DEV-08 就是这么写的）；(b) 契约增加「起点单号」参数（改契约）。→ **待委托方裁决**，本轮不改用例、不改契约。

### DEV-05

- 期望：`COMPLETED` / `ORDER_STATUS`
- 实际：`COMPLETED` / `UNSUPPORTED`
- 逐项：终态 一致；类型 **不一致**；事实白名单 通过；未知声明 通过
- 实际细节：type=UNSUPPORTED；facts=[]；unknown=[]；suggestions=[]
- 判定：**用例与契约错位（待裁决）**——问题文本不含单号，`FixedFlowInvestigator.extractOrderNo` 取不到 → 直接判 `UNSUPPORTED`，工具 0 次调用（连分类都没做）。期望侧依据：fixture 已定义起点单，§3.1 的三类问题都是「针对某张单」的；实现侧依据：当前契约是 `investigate(ToolContext, question)`——**只有问题文本**（§1 第五题：一次请求一份报告）。两条修法都成立：(a) 用例的问题里带单号（最小改动，DEV-01 / DEV-08 就是这么写的）；(b) 契约增加「起点单号」参数（改契约）。→ **待委托方裁决**，本轮不改用例、不改契约。

### DEV-06

- 期望：`COMPLETED` / `ORDER_STATUS`
- 实际：`COMPLETED` / `UNSUPPORTED`
- 逐项：终态 一致；类型 **不一致**；事实白名单 通过；未知声明 通过
- 实际细节：type=UNSUPPORTED；facts=[]；unknown=[]；suggestions=[]
- 判定：**实现覆盖不足（分类规则，待裁决）**——单号在，但透明关键词表没覆盖该问法 → `UNSUPPORTED`。期望侧依据：§3.1 的 requiredFacts（如 `order.assignee`）与 D82 的条件必需事实都要求该类型可判；实现侧依据：baseline 首版刻意**只用透明关键词、不引入模型**（§3.1 L118）。扩关键词会动实现，本轮纪律不允许。→ **待裁决**。

### DEV-10

- 期望：`COMPLETED` / `ORDER_STATUS`
- 实际：`COMPLETED` / `UNSUPPORTED`
- 逐项：终态 一致；类型 **不一致**；事实白名单 通过；未知声明 **不过**
- 实际细节：type=UNSUPPORTED；facts=[]；unknown=[]；suggestions=[]
- 判定：**用例与契约错位（待裁决）**——问题文本不含单号，`FixedFlowInvestigator.extractOrderNo` 取不到 → 直接判 `UNSUPPORTED`，工具 0 次调用（连分类都没做）。期望侧依据：fixture 已定义起点单，§3.1 的三类问题都是「针对某张单」的；实现侧依据：当前契约是 `investigate(ToolContext, question)`——**只有问题文本**（§1 第五题：一次请求一份报告）。两条修法都成立：(a) 用例的问题里带单号（最小改动，DEV-01 / DEV-08 就是这么写的）；(b) 契约增加「起点单号」参数（改契约）。→ **待委托方裁决**，本轮不改用例、不改契约。

### DEV-11

- 期望：`COMPLETED` / `TIMEOUT_SITUATION`
- 实际：`COMPLETED` / `UNSUPPORTED`
- 逐项：终态 一致；类型 **不一致**；事实白名单 通过；未知声明 **不过**
- 实际细节：type=UNSUPPORTED；facts=[]；unknown=[]；suggestions=[]
- 判定：**用例与契约错位（待裁决）**——问题文本不含单号，`FixedFlowInvestigator.extractOrderNo` 取不到 → 直接判 `UNSUPPORTED`，工具 0 次调用（连分类都没做）。期望侧依据：fixture 已定义起点单，§3.1 的三类问题都是「针对某张单」的；实现侧依据：当前契约是 `investigate(ToolContext, question)`——**只有问题文本**（§1 第五题：一次请求一份报告）。两条修法都成立：(a) 用例的问题里带单号（最小改动，DEV-01 / DEV-08 就是这么写的）；(b) 契约增加「起点单号」参数（改契约）。→ **待委托方裁决**，本轮不改用例、不改契约。

### DEV-12

- 期望：`COMPLETED` / `TIMEOUT_SITUATION`
- 实际：`COMPLETED` / `UNSUPPORTED`
- 逐项：终态 一致；类型 **不一致**；事实白名单 通过；未知声明 通过
- 实际细节：type=UNSUPPORTED；facts=[]；unknown=[]；suggestions=[]
- 判定：**用例与契约错位（待裁决）**——问题文本不含单号，`FixedFlowInvestigator.extractOrderNo` 取不到 → 直接判 `UNSUPPORTED`，工具 0 次调用（连分类都没做）。期望侧依据：fixture 已定义起点单，§3.1 的三类问题都是「针对某张单」的；实现侧依据：当前契约是 `investigate(ToolContext, question)`——**只有问题文本**（§1 第五题：一次请求一份报告）。两条修法都成立：(a) 用例的问题里带单号（最小改动，DEV-01 / DEV-08 就是这么写的）；(b) 契约增加「起点单号」参数（改契约）。→ **待委托方裁决**，本轮不改用例、不改契约。

## 确定性判据（判据 4）

- 两轮逐例终态、problemType 一致（断言在 harness 里，失败会直接报错）：**通过**
- 两轮汇总计数相同：**通过**（`pass=3/12 completed=10 expectedReject=2`）
- 耗时列两轮不同是预期（本机抖动），不影响判据

## D19 清理留痕（先按最宽口径统计再 DROP）

统计口径 = **临时库里的每一张表都数一遍**（不是只数 fixture 写过的表）：

| 表 | 行数 |
| --- | --- |
| t_consume_record | 0 |
| t_event_outbox | 0 |
| t_message_retry | 0 |
| t_notification | 0 |
| t_permission | 0 |
| t_role | 4 |
| t_role_permission | 0 |
| t_sla_config | 0 |
| t_user | 33 |
| t_user_role | 13 |
| t_work_order | 11 |
| t_work_order_log | 15 |

- 清理动作：`DROP DATABASE `wo_agent_eval_20261006`（只作用于本轮自建的、名字带 `wo_agent_eval_` 前缀的临时库）
- 业务库与共享的 `work_order_test` **未被写入**（只读取了表结构 + `t_role`）
