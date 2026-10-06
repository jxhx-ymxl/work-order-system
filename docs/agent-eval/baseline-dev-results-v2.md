# 离线评测记录：baseline（FixedFlowInvestigator）· 开发集 12 条

> ⚠ **本轮只跑了 baseline**：agent 侧**未**运行、冻结集（24 条）**未**运行。
> 本文件的数字只说明「固定流程在这 12 条上的行为」，**不是**两方案对照，**不是**模型成绩。

| 项 | 值 |
| --- | --- |
| 运行日期 | 2026-10-06 |
| 用例文件 | `scripts/agent-eval-dev.json`（开发集 12 条） |
| 专用临时库 | `wo_agent_eval_20261006`（结构克隆自 `work_order_test` + `t_role` 参考行；跑完按 D19 最宽口径统计后 DROP） |
| 方案 | `FixedFlowInvestigator`（mode=fixed） |
| 起点单 | **结构化入参 `order_ref`**（设计稿 L80）：入口固定预读一次主工单并注册 root 引用，**不从问题文本解析**；预读计入工具成本 |
| 模型调用 | **0**（baseline 首版不引入模型做意图分类） |
| 重复次数 | 每例 1 次 × 2 轮（第 2 轮用于确定性判据；README 的「3 次」是 C 层真实对照的要求） |
| 耗时口径 | 本机、工具打本地临时库——**非生产延迟**（手册 L139：离线延迟不代表生产延迟） |

## 逐例结果

| id | 期望终态 | 实际终态 | 期望类型 | 实际类型 | 越权/禁止项 | 工具调用 | 耗时(ms) | 契约通过 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| DEV-01 | COMPLETED | COMPLETED | ORDER_STATUS | ORDER_STATUS | 否 | 1 | 38 | ✅ |
| DEV-02 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | TIMEOUT_SITUATION | 否 | 2 | 46 | ✅ |
| DEV-03 | COMPLETED | COMPLETED | REASSIGN_HISTORY | REASSIGN_HISTORY | 否 | 2 | 31 | ✅ |
| DEV-04 | COMPLETED | COMPLETED | REASSIGN_HISTORY | REASSIGN_HISTORY | 否 | 2 | 17 | ✅ |
| DEV-05 | COMPLETED | COMPLETED | ORDER_STATUS | UNSUPPORTED | 否 | 1 | 15 | ❌ |
| DEV-06 | COMPLETED | COMPLETED | ORDER_STATUS | UNSUPPORTED | 否 | 1 | 4 | ❌ |
| DEV-07 | COMPLETED | COMPLETED | UNSUPPORTED | UNSUPPORTED | 否 | 1 | 14 | ✅ |
| DEV-08 | FAILED(FORBIDDEN) | FAILED(FORBIDDEN) | N/A | （无报告） | 否 | 1 | 11 | ✅ |
| DEV-09 | FAILED(FORBIDDEN) | FAILED(FORBIDDEN) | N/A | （无报告） | 否 | 0 | 2 | ✅ |
| DEV-10 | COMPLETED | COMPLETED | ORDER_STATUS | ORDER_STATUS | 否 | 1 | 18 | ✅ |
| DEV-11 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | TIMEOUT_SITUATION | 否 | 2 | 28 | ✅ |
| DEV-12 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | TIMEOUT_SITUATION | 否 | 2 | 19 | ✅ |

## 汇总（分母固定 = 计划用例数 12，失败与超时保留在分母）

- 计划 run：**12**（分母）
- 全任务通过：**10 / 12**
- 正常调查完成率：**10 / 10**（分母排除 2 条「预期授权拒绝」样例，见手册 L209）
- 实际 COMPLETED 的 run：**10 / 12**
- 预期失败且确实失败：**2 / 2**
- 越权 / 禁止项命中：**0**
- 未判定：**0**（baseline 是确定性流程，没有「未判定」这一档）
- 工具调用合计：**16** 次
- 工具调用分布：入口预读让每条**至少 1 次**；需要同部门对照的类型（TIMEOUT_SITUATION / REASSIGN_HISTORY）再 +1。
- 耗时合计：**243 ms**（本机 + 本地临时库，**非生产延迟**）

## 完整失败清单（2 条，不删难例）

根因归类（按出现顺序，不按好看程度）：

| 根因 | 条数 | 说明 |
| --- | --- | --- |
| 透明关键词表未覆盖该问法 → `UNSUPPORTED` | 2 | 起点单已由结构化入参给出，问题只剩分类 |
| `must_cover_facts` 未被引用 | 0 | §3.1 的完成判据不过 |

### DEV-05

- 期望：`COMPLETED` / `ORDER_STATUS`
- 实际：`COMPLETED` / `UNSUPPORTED`
- 逐项：终态 一致；类型 **不一致**；事实白名单 **不过**；未知声明 通过
- 实际细节：type=UNSUPPORTED；facts=[]；unknown=[]；缺失的 must_cover=[order.exists, order.status, order.assignee]；suggestions=[]
- 判定：**实现覆盖不足（分类规则，待裁决）**——起点单已经由结构化入参给出，但透明关键词表没覆盖该问法 → `UNSUPPORTED`。期望侧依据：§3.1 的 requiredFacts（如 `order.assignee`）与 D82 的条件必需事实都要求该类型可判；实现侧依据：baseline 首版刻意**只用透明关键词、不引入模型**（§3.1 L118）。扩关键词会动实现，本轮纪律不允许。→ **待裁决**。

### DEV-06

- 期望：`COMPLETED` / `ORDER_STATUS`
- 实际：`COMPLETED` / `UNSUPPORTED`
- 逐项：终态 一致；类型 **不一致**；事实白名单 **不过**；未知声明 通过
- 实际细节：type=UNSUPPORTED；facts=[]；unknown=[]；缺失的 must_cover=[order.exists]；suggestions=[]
- 判定：**实现覆盖不足（分类规则，待裁决）**——起点单已经由结构化入参给出，但透明关键词表没覆盖该问法 → `UNSUPPORTED`。期望侧依据：§3.1 的 requiredFacts（如 `order.assignee`）与 D82 的条件必需事实都要求该类型可判；实现侧依据：baseline 首版刻意**只用透明关键词、不引入模型**（§3.1 L118）。扩关键词会动实现，本轮纪律不允许。→ **待裁决**。

## 确定性判据（判据 4）

- 两轮逐例终态、problemType 一致（断言在 harness 里，失败会直接报错）：**通过**
- 两轮汇总计数相同：**通过**（`pass=10/12 completed=10 expectedReject=2`）
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
| t_user | 32 |
| t_user_role | 13 |
| t_work_order | 11 |
| t_work_order_log | 12 |

- 清理动作：`DROP DATABASE `wo_agent_eval_20261006`（只作用于本轮自建的、名字带 `wo_agent_eval_` 前缀的临时库）
- 业务库与共享的 `work_order_test` **未被写入**（只读取了表结构 + `t_role`）
