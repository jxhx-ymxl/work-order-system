# S6 阶段 1：holdout harness 桩跑通记录（2026-10-07）

> ⚠ **这一遍不是成绩**：桩对任何输入返回固定值（与 `scripts/triage-eval.py` 文件头同一口径），
> 只能验证 **harness 本身**能跑完 144 次、能统计、能保留失败。**不是** fixed vs agent 的对照结论，
> **不是**模型能力，**不是**生产延迟（手册 L139：离线/桩延迟不代表生产延迟）。**holdout 仍未真正跑过。**

| 项 | 值 |
| --- | --- |
| 日期 | 2026-10-07 |
| 用例文件 | `scripts/agent-eval-holdout.json`（冻结 24 例，**不改期望**） |
| 专用库 | `work_order_holdout`（结构克隆自 `work_order_test` + `t_role`；跑完按 D19 最宽口径统计后 DROP） |
| 方案 | `fixed`（FixedFlowInvestigator）/ `agent`（InvestigationAgent，走**真实 HTTP 桩**） |
| 模型端点 | 本轮启动的**本地 HTTP 桩**（`llm.api.url` 指向它）；真实 HTTP 写读 + JSON 解析 + 有界重试都在链路上 |
| 比例 | 24 例 × 2 方案 × 3 次 = **144 次**（分母固定；失败与超时保留） |
| 耗时口径 | 本机 + 桩 → **非生产延迟**（手册 L139） |

## 1. 汇总（分母固定 = 计划 run 数）

| 方案 | 计划 run | 通过 | 终态一致 | 类型一致 | 事实覆盖 | 失败 |
| --- | --- | --- | --- | --- | --- | --- |
| fixed | 72 | 30 | 54 | 51 | 48 | 42 |
| agent | 72 | 42 | 72 | 42 | 48 | 30 |

> ⚠ **上表不是对照结论**：`agent` 侧的数字来自一个**固定返回 `ORDER_STATUS`** 的桩，
> `fixed` 侧的数字来自透明关键词表；两者都**不是**真模型成绩。差异只说明 harness 把两路都跑通了。

- 证据覆盖（必需事实被引用数 / 应覆盖数）：**120 / 234**
- 工具调用合计：**216** 次
- 模型物理调用：agent 侧每次 ≈ 2 次（读根 + finish）——**桩固定行为**，不是模型选择
- 单次耗时 min/median/max：**5 / 27 / 241 ms**（本机 + 桩，**非生产延迟**）

## 2. 逐例结果（每例每方案 3 次全部保留）

| 方案 | 例 | 次 | 期望终态 | 实际终态 | 期望类型 | 实际类型 | 覆盖 | 工具调用 | 耗时(ms) | 通过 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| fixed | 01 | 1 | COMPLETED | COMPLETED | ORDER_STATUS | UNSUPPORTED | 0/3 | 1 | 77 | ❌ |
| agent | 01 | 1 | COMPLETED | COMPLETED | ORDER_STATUS | ORDER_STATUS | 3/3 | 2 | 101 | ✅ |
| fixed | 02 | 1 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | TIMEOUT_SITUATION | 4/4 | 2 | 61 | ✅ |
| agent | 02 | 1 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | ORDER_STATUS | 3/4 | 2 | 43 | ❌ |
| fixed | 03 | 1 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | UNSUPPORTED | 0/5 | 1 | 37 | ❌ |
| agent | 03 | 1 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | ORDER_STATUS | 3/5 | 2 | 46 | ❌ |
| fixed | 04 | 1 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | ORDER_STATUS | 3/5 | 1 | 59 | ❌ |
| agent | 04 | 1 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | ORDER_STATUS | 2/5 | 2 | 69 | ❌ |
| fixed | 05 | 1 | COMPLETED | COMPLETED | REASSIGN_HISTORY | REASSIGN_HISTORY | 2/2 | 2 | 55 | ✅ |
| agent | 05 | 1 | COMPLETED | COMPLETED | REASSIGN_HISTORY | ORDER_STATUS | 1/2 | 2 | 46 | ❌ |
| fixed | 06 | 1 | COMPLETED | COMPLETED | REASSIGN_HISTORY | UNSUPPORTED | 0/2 | 1 | 45 | ❌ |
| agent | 06 | 1 | COMPLETED | COMPLETED | REASSIGN_HISTORY | ORDER_STATUS | 1/2 | 2 | 70 | ❌ |
| fixed | 07 | 1 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | TIMEOUT_SITUATION | 3/6 | 2 | 50 | ❌ |
| agent | 07 | 1 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | ORDER_STATUS | 2/6 | 2 | 46 | ❌ |
| fixed | 08 | 1 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | UNSUPPORTED | 0/3 | 1 | 40 | ❌ |
| agent | 08 | 1 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | ORDER_STATUS | 2/3 | 2 | 54 | ❌ |
| fixed | 09 | 1 | COMPLETED | COMPLETED | UNSUPPORTED | UNSUPPORTED | 0/0 | 1 | 25 | ✅ |
| agent | 09 | 1 | COMPLETED | COMPLETED | UNSUPPORTED | ORDER_STATUS | 0/0 | 2 | 36 | ❌ |
| fixed | 10 | 1 | COMPLETED | COMPLETED | UNSUPPORTED | UNSUPPORTED | 0/0 | 1 | 28 | ✅ |
| agent | 10 | 1 | COMPLETED | COMPLETED | UNSUPPORTED | ORDER_STATUS | 0/0 | 2 | 37 | ❌ |
| fixed | 11 | 1 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | UNSUPPORTED | 0/3 | 1 | 23 | ❌ |
| agent | 11 | 1 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | ORDER_STATUS | 2/3 | 2 | 32 | ❌ |
| fixed | 12 | 1 | COMPLETED | COMPLETED | ORDER_STATUS | ORDER_STATUS | 3/3 | 1 | 19 | ✅ |
| agent | 12 | 1 | COMPLETED | COMPLETED | ORDER_STATUS | ORDER_STATUS | 3/3 | 2 | 32 | ✅ |
| fixed | 13 | 1 | FAILED(FORBIDDEN) | FAILED(FORBIDDEN) | N/A | （无报告） | 0/0 | 1 | 8 | ✅ |
| agent | 13 | 1 | FAILED(FORBIDDEN) | FAILED(FORBIDDEN) | N/A | （无报告） | 0/0 | 1 | 7 | ✅ |
| fixed | 14 | 1 | FAILED(FORBIDDEN) | FAILED(FORBIDDEN) | N/A | （无报告） | 0/0 | 1 | 5 | ✅ |
| agent | 14 | 1 | FAILED(FORBIDDEN) | FAILED(FORBIDDEN) | N/A | （无报告） | 0/0 | 1 | 8 | ✅ |
| fixed | 15 | 1 | FAILED(FORBIDDEN) | FAILED(FORBIDDEN) | N/A | （无报告） | 0/0 | 1 | 7 | ✅ |
| agent | 15 | 1 | FAILED(FORBIDDEN) | FAILED(FORBIDDEN) | N/A | （无报告） | 0/0 | 1 | 9 | ✅ |
| fixed | 16 | 1 | CANCELLED(PERMISSION_REVOKED) | COMPLETED | N/A | UNSUPPORTED | 0/0 | 1 | 23 | ❌ |
| agent | 16 | 1 | CANCELLED(PERMISSION_REVOKED) | CANCELLED(PERMISSION_REVOKED) | N/A | （无报告） | 0/0 | 2 | 28 | ✅ |
| fixed | 17 | 1 | INCOMPLETE(STATE_CHANGED) | COMPLETED | N/A | UNSUPPORTED | 0/0 | 1 | 25 | ❌ |
| agent | 17 | 1 | INCOMPLETE(STATE_CHANGED) | INCOMPLETE(STATE_CHANGED) | N/A | （无报告） | 0/0 | 3 | 62 | ✅ |
| fixed | 18 | 1 | COMPLETED | COMPLETED | ORDER_STATUS | UNSUPPORTED | 0/3 | 1 | 17 | ❌ |
| agent | 18 | 1 | COMPLETED | COMPLETED | ORDER_STATUS | ORDER_STATUS | 3/3 | 2 | 40 | ✅ |
| fixed | 19 | 1 | FAILED(MODEL_PROTOCOL_ERROR) | COMPLETED | N/A | UNSUPPORTED | 0/0 | 1 | 23 | ❌ |
| agent | 19 | 1 | FAILED(MODEL_PROTOCOL_ERROR) | FAILED(MODEL_PROTOCOL_ERROR) | N/A | （无报告） | 0/0 | 1 | 14 | ✅ |
| fixed | 20 | 1 | FAILED(NO_PROGRESS) | COMPLETED | N/A | UNSUPPORTED | 0/0 | 1 | 21 | ❌ |
| agent | 20 | 1 | FAILED(NO_PROGRESS) | FAILED(NO_PROGRESS) | N/A | （无报告） | 0/0 | 3 | 23 | ✅ |
| fixed | 21 | 1 | COMPLETED | COMPLETED | N/A | UNSUPPORTED | 0/0 | 1 | 20 | ✅ |
| agent | 21 | 1 | COMPLETED | COMPLETED | N/A | ORDER_STATUS | 0/0 | 2 | 237 | ✅ |
| fixed | 22 | 1 | FAILED(MODEL_HTTP_ERROR) | COMPLETED | N/A | UNSUPPORTED | 0/0 | 1 | 40 | ❌ |
| agent | 22 | 1 | FAILED(MODEL_HTTP_ERROR) | FAILED(MODEL_HTTP_ERROR) | N/A | （无报告） | 0/0 | 1 | 17 | ✅ |
| fixed | 23 | 1 | COMPLETED | COMPLETED | N/A | UNSUPPORTED | 0/0 | 1 | 42 | ✅ |
| agent | 23 | 1 | COMPLETED | COMPLETED | N/A | ORDER_STATUS | 0/0 | 2 | 73 | ✅ |
| fixed | 24 | 1 | INCOMPLETE(STATE_CHANGED) | COMPLETED | N/A | UNSUPPORTED | 0/0 | 1 | 39 | ❌ |
| agent | 24 | 1 | INCOMPLETE(STATE_CHANGED) | INCOMPLETE(STATE_CHANGED) | N/A | （无报告） | 0/0 | 2 | 49 | ✅ |
| fixed | 01 | 2 | COMPLETED | COMPLETED | ORDER_STATUS | UNSUPPORTED | 0/3 | 1 | 23 | ❌ |
| agent | 01 | 2 | COMPLETED | COMPLETED | ORDER_STATUS | ORDER_STATUS | 3/3 | 2 | 31 | ✅ |
| fixed | 02 | 2 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | TIMEOUT_SITUATION | 4/4 | 2 | 24 | ✅ |
| agent | 02 | 2 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | ORDER_STATUS | 3/4 | 2 | 27 | ❌ |
| fixed | 03 | 2 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | UNSUPPORTED | 0/5 | 1 | 14 | ❌ |
| agent | 03 | 2 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | ORDER_STATUS | 3/5 | 2 | 25 | ❌ |
| fixed | 04 | 2 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | ORDER_STATUS | 3/5 | 1 | 22 | ❌ |
| agent | 04 | 2 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | ORDER_STATUS | 2/5 | 2 | 32 | ❌ |
| fixed | 05 | 2 | COMPLETED | COMPLETED | REASSIGN_HISTORY | REASSIGN_HISTORY | 2/2 | 2 | 30 | ✅ |
| agent | 05 | 2 | COMPLETED | COMPLETED | REASSIGN_HISTORY | ORDER_STATUS | 1/2 | 2 | 31 | ❌ |
| fixed | 06 | 2 | COMPLETED | COMPLETED | REASSIGN_HISTORY | UNSUPPORTED | 0/2 | 1 | 30 | ❌ |
| agent | 06 | 2 | COMPLETED | COMPLETED | REASSIGN_HISTORY | ORDER_STATUS | 1/2 | 2 | 52 | ❌ |
| fixed | 07 | 2 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | TIMEOUT_SITUATION | 3/6 | 2 | 31 | ❌ |
| agent | 07 | 2 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | ORDER_STATUS | 2/6 | 2 | 30 | ❌ |
| fixed | 08 | 2 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | UNSUPPORTED | 0/3 | 1 | 23 | ❌ |
| agent | 08 | 2 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | ORDER_STATUS | 2/3 | 2 | 36 | ❌ |
| fixed | 09 | 2 | COMPLETED | COMPLETED | UNSUPPORTED | UNSUPPORTED | 0/0 | 1 | 24 | ✅ |
| agent | 09 | 2 | COMPLETED | COMPLETED | UNSUPPORTED | ORDER_STATUS | 0/0 | 2 | 41 | ❌ |
| fixed | 10 | 2 | COMPLETED | COMPLETED | UNSUPPORTED | UNSUPPORTED | 0/0 | 1 | 18 | ✅ |
| agent | 10 | 2 | COMPLETED | COMPLETED | UNSUPPORTED | ORDER_STATUS | 0/0 | 2 | 39 | ❌ |
| fixed | 11 | 2 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | UNSUPPORTED | 0/3 | 1 | 28 | ❌ |
| agent | 11 | 2 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | ORDER_STATUS | 2/3 | 2 | 38 | ❌ |
| fixed | 12 | 2 | COMPLETED | COMPLETED | ORDER_STATUS | ORDER_STATUS | 3/3 | 1 | 20 | ✅ |
| agent | 12 | 2 | COMPLETED | COMPLETED | ORDER_STATUS | ORDER_STATUS | 3/3 | 2 | 30 | ✅ |
| fixed | 13 | 2 | FAILED(FORBIDDEN) | FAILED(FORBIDDEN) | N/A | （无报告） | 0/0 | 1 | 8 | ✅ |
| agent | 13 | 2 | FAILED(FORBIDDEN) | FAILED(FORBIDDEN) | N/A | （无报告） | 0/0 | 1 | 6 | ✅ |
| fixed | 14 | 2 | FAILED(FORBIDDEN) | FAILED(FORBIDDEN) | N/A | （无报告） | 0/0 | 1 | 7 | ✅ |
| agent | 14 | 2 | FAILED(FORBIDDEN) | FAILED(FORBIDDEN) | N/A | （无报告） | 0/0 | 1 | 7 | ✅ |
| fixed | 15 | 2 | FAILED(FORBIDDEN) | FAILED(FORBIDDEN) | N/A | （无报告） | 0/0 | 1 | 8 | ✅ |
| agent | 15 | 2 | FAILED(FORBIDDEN) | FAILED(FORBIDDEN) | N/A | （无报告） | 0/0 | 1 | 9 | ✅ |
| fixed | 16 | 2 | CANCELLED(PERMISSION_REVOKED) | COMPLETED | N/A | UNSUPPORTED | 0/0 | 1 | 21 | ❌ |
| agent | 16 | 2 | CANCELLED(PERMISSION_REVOKED) | CANCELLED(PERMISSION_REVOKED) | N/A | （无报告） | 0/0 | 2 | 23 | ✅ |
| fixed | 17 | 2 | INCOMPLETE(STATE_CHANGED) | COMPLETED | N/A | UNSUPPORTED | 0/0 | 1 | 20 | ❌ |
| agent | 17 | 2 | INCOMPLETE(STATE_CHANGED) | INCOMPLETE(STATE_CHANGED) | N/A | （无报告） | 0/0 | 3 | 55 | ✅ |
| fixed | 18 | 2 | COMPLETED | COMPLETED | ORDER_STATUS | UNSUPPORTED | 0/3 | 1 | 19 | ❌ |
| agent | 18 | 2 | COMPLETED | COMPLETED | ORDER_STATUS | ORDER_STATUS | 3/3 | 2 | 28 | ✅ |
| fixed | 19 | 2 | FAILED(MODEL_PROTOCOL_ERROR) | COMPLETED | N/A | UNSUPPORTED | 0/0 | 1 | 20 | ❌ |
| agent | 19 | 2 | FAILED(MODEL_PROTOCOL_ERROR) | FAILED(MODEL_PROTOCOL_ERROR) | N/A | （无报告） | 0/0 | 1 | 13 | ✅ |
| fixed | 20 | 2 | FAILED(NO_PROGRESS) | COMPLETED | N/A | UNSUPPORTED | 0/0 | 1 | 19 | ❌ |
| agent | 20 | 2 | FAILED(NO_PROGRESS) | FAILED(NO_PROGRESS) | N/A | （无报告） | 0/0 | 3 | 21 | ✅ |
| fixed | 21 | 2 | COMPLETED | COMPLETED | N/A | UNSUPPORTED | 0/0 | 1 | 18 | ✅ |
| agent | 21 | 2 | COMPLETED | COMPLETED | N/A | ORDER_STATUS | 0/0 | 2 | 238 | ✅ |
| fixed | 22 | 2 | FAILED(MODEL_HTTP_ERROR) | COMPLETED | N/A | UNSUPPORTED | 0/0 | 1 | 17 | ❌ |
| agent | 22 | 2 | FAILED(MODEL_HTTP_ERROR) | FAILED(MODEL_HTTP_ERROR) | N/A | （无报告） | 0/0 | 1 | 13 | ✅ |
| fixed | 23 | 2 | COMPLETED | COMPLETED | N/A | UNSUPPORTED | 0/0 | 1 | 21 | ✅ |
| agent | 23 | 2 | COMPLETED | COMPLETED | N/A | ORDER_STATUS | 0/0 | 2 | 33 | ✅ |
| fixed | 24 | 2 | INCOMPLETE(STATE_CHANGED) | COMPLETED | N/A | UNSUPPORTED | 0/0 | 1 | 18 | ❌ |
| agent | 24 | 2 | INCOMPLETE(STATE_CHANGED) | INCOMPLETE(STATE_CHANGED) | N/A | （无报告） | 0/0 | 2 | 37 | ✅ |
| fixed | 01 | 3 | COMPLETED | COMPLETED | ORDER_STATUS | UNSUPPORTED | 0/3 | 1 | 17 | ❌ |
| agent | 01 | 3 | COMPLETED | COMPLETED | ORDER_STATUS | ORDER_STATUS | 3/3 | 2 | 38 | ✅ |
| fixed | 02 | 3 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | TIMEOUT_SITUATION | 4/4 | 2 | 27 | ✅ |
| agent | 02 | 3 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | ORDER_STATUS | 3/4 | 2 | 31 | ❌ |
| fixed | 03 | 3 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | UNSUPPORTED | 0/5 | 1 | 19 | ❌ |
| agent | 03 | 3 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | ORDER_STATUS | 3/5 | 2 | 36 | ❌ |
| fixed | 04 | 3 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | ORDER_STATUS | 3/5 | 1 | 29 | ❌ |
| agent | 04 | 3 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | ORDER_STATUS | 2/5 | 2 | 34 | ❌ |
| fixed | 05 | 3 | COMPLETED | COMPLETED | REASSIGN_HISTORY | REASSIGN_HISTORY | 2/2 | 2 | 29 | ✅ |
| agent | 05 | 3 | COMPLETED | COMPLETED | REASSIGN_HISTORY | ORDER_STATUS | 1/2 | 2 | 27 | ❌ |
| fixed | 06 | 3 | COMPLETED | COMPLETED | REASSIGN_HISTORY | UNSUPPORTED | 0/2 | 1 | 19 | ❌ |
| agent | 06 | 3 | COMPLETED | COMPLETED | REASSIGN_HISTORY | ORDER_STATUS | 1/2 | 2 | 27 | ❌ |
| fixed | 07 | 3 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | TIMEOUT_SITUATION | 3/6 | 2 | 26 | ❌ |
| agent | 07 | 3 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | ORDER_STATUS | 2/6 | 2 | 31 | ❌ |
| fixed | 08 | 3 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | UNSUPPORTED | 0/3 | 1 | 43 | ❌ |
| agent | 08 | 3 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | ORDER_STATUS | 2/3 | 2 | 51 | ❌ |
| fixed | 09 | 3 | COMPLETED | COMPLETED | UNSUPPORTED | UNSUPPORTED | 0/0 | 1 | 24 | ✅ |
| agent | 09 | 3 | COMPLETED | COMPLETED | UNSUPPORTED | ORDER_STATUS | 0/0 | 2 | 30 | ❌ |
| fixed | 10 | 3 | COMPLETED | COMPLETED | UNSUPPORTED | UNSUPPORTED | 0/0 | 1 | 41 | ✅ |
| agent | 10 | 3 | COMPLETED | COMPLETED | UNSUPPORTED | ORDER_STATUS | 0/0 | 2 | 41 | ❌ |
| fixed | 11 | 3 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | UNSUPPORTED | 0/3 | 1 | 18 | ❌ |
| agent | 11 | 3 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | ORDER_STATUS | 2/3 | 2 | 41 | ❌ |
| fixed | 12 | 3 | COMPLETED | COMPLETED | ORDER_STATUS | ORDER_STATUS | 3/3 | 1 | 24 | ✅ |
| agent | 12 | 3 | COMPLETED | COMPLETED | ORDER_STATUS | ORDER_STATUS | 3/3 | 2 | 33 | ✅ |
| fixed | 13 | 3 | FAILED(FORBIDDEN) | FAILED(FORBIDDEN) | N/A | （无报告） | 0/0 | 1 | 10 | ✅ |
| agent | 13 | 3 | FAILED(FORBIDDEN) | FAILED(FORBIDDEN) | N/A | （无报告） | 0/0 | 1 | 8 | ✅ |
| fixed | 14 | 3 | FAILED(FORBIDDEN) | FAILED(FORBIDDEN) | N/A | （无报告） | 0/0 | 1 | 7 | ✅ |
| agent | 14 | 3 | FAILED(FORBIDDEN) | FAILED(FORBIDDEN) | N/A | （无报告） | 0/0 | 1 | 12 | ✅ |
| fixed | 15 | 3 | FAILED(FORBIDDEN) | FAILED(FORBIDDEN) | N/A | （无报告） | 0/0 | 1 | 9 | ✅ |
| agent | 15 | 3 | FAILED(FORBIDDEN) | FAILED(FORBIDDEN) | N/A | （无报告） | 0/0 | 1 | 7 | ✅ |
| fixed | 16 | 3 | CANCELLED(PERMISSION_REVOKED) | COMPLETED | N/A | UNSUPPORTED | 0/0 | 1 | 20 | ❌ |
| agent | 16 | 3 | CANCELLED(PERMISSION_REVOKED) | CANCELLED(PERMISSION_REVOKED) | N/A | （无报告） | 0/0 | 2 | 21 | ✅ |
| fixed | 17 | 3 | INCOMPLETE(STATE_CHANGED) | COMPLETED | N/A | UNSUPPORTED | 0/0 | 1 | 24 | ❌ |
| agent | 17 | 3 | INCOMPLETE(STATE_CHANGED) | INCOMPLETE(STATE_CHANGED) | N/A | （无报告） | 0/0 | 3 | 86 | ✅ |
| fixed | 18 | 3 | COMPLETED | COMPLETED | ORDER_STATUS | UNSUPPORTED | 0/3 | 1 | 36 | ❌ |
| agent | 18 | 3 | COMPLETED | COMPLETED | ORDER_STATUS | ORDER_STATUS | 3/3 | 2 | 33 | ✅ |
| fixed | 19 | 3 | FAILED(MODEL_PROTOCOL_ERROR) | COMPLETED | N/A | UNSUPPORTED | 0/0 | 1 | 18 | ❌ |
| agent | 19 | 3 | FAILED(MODEL_PROTOCOL_ERROR) | FAILED(MODEL_PROTOCOL_ERROR) | N/A | （无报告） | 0/0 | 1 | 18 | ✅ |
| fixed | 20 | 3 | FAILED(NO_PROGRESS) | COMPLETED | N/A | UNSUPPORTED | 0/0 | 1 | 28 | ❌ |
| agent | 20 | 3 | FAILED(NO_PROGRESS) | FAILED(NO_PROGRESS) | N/A | （无报告） | 0/0 | 3 | 34 | ✅ |
| fixed | 21 | 3 | COMPLETED | COMPLETED | N/A | UNSUPPORTED | 0/0 | 1 | 22 | ✅ |
| agent | 21 | 3 | COMPLETED | COMPLETED | N/A | ORDER_STATUS | 0/0 | 2 | 241 | ✅ |
| fixed | 22 | 3 | FAILED(MODEL_HTTP_ERROR) | COMPLETED | N/A | UNSUPPORTED | 0/0 | 1 | 24 | ❌ |
| agent | 22 | 3 | FAILED(MODEL_HTTP_ERROR) | FAILED(MODEL_HTTP_ERROR) | N/A | （无报告） | 0/0 | 1 | 16 | ✅ |
| fixed | 23 | 3 | COMPLETED | COMPLETED | N/A | UNSUPPORTED | 0/0 | 1 | 19 | ✅ |
| agent | 23 | 3 | COMPLETED | COMPLETED | N/A | ORDER_STATUS | 0/0 | 2 | 30 | ✅ |
| fixed | 24 | 3 | INCOMPLETE(STATE_CHANGED) | COMPLETED | N/A | UNSUPPORTED | 0/0 | 1 | 19 | ❌ |
| agent | 24 | 3 | INCOMPLETE(STATE_CHANGED) | INCOMPLETE(STATE_CHANGED) | N/A | （无报告） | 0/0 | 2 | 34 | ✅ |

## 3. 完整失败清单（72 条，不删难例）

> **为什么有这么多失败**：桩对任何输入返回固定值（固定 `ORDER_STATUS` + 主单三个必需事实），
> 所以**期望类型不是 `ORDER_STATUS`/`N/A` 的槽**天然判错——这正是「不是成绩」的直接证据；
> 它同时证明**失败被完整保留**（下面每条都在），没有被吞掉去凑好看。
> 另外，故障注入（16/17/19/20/22/24）只作用在 **agent 路径**（它是模型/执行期事件），
> 所以这些槽的 `fixed` 行显示 `COMPLETED` 属**驱动范围**，不是「fixed 少做了事」。

| 方案 | 例 | 次 | 期望 → 实际（终态） | 期望 → 实际（类型） | 覆盖 | 根因归类 |
| --- | --- | --- | --- | --- | --- | --- |
| fixed | 01 | 1 | COMPLETED → COMPLETED | ORDER_STATUS → UNSUPPORTED | 0/3 | 类型不符（fixed=透明关键词表 / agent=桩固定 `ORDER_STATUS`）——**桩不是成绩** |
| agent | 02 | 1 | COMPLETED → COMPLETED | TIMEOUT_SITUATION → ORDER_STATUS | 3/4 | 类型不符（fixed=透明关键词表 / agent=桩固定 `ORDER_STATUS`）——**桩不是成绩** |
| fixed | 03 | 1 | COMPLETED → COMPLETED | TIMEOUT_SITUATION → UNSUPPORTED | 0/5 | 类型不符（fixed=透明关键词表 / agent=桩固定 `ORDER_STATUS`）——**桩不是成绩** |
| agent | 03 | 1 | COMPLETED → COMPLETED | TIMEOUT_SITUATION → ORDER_STATUS | 3/5 | 类型不符（fixed=透明关键词表 / agent=桩固定 `ORDER_STATUS`）——**桩不是成绩** |
| fixed | 04 | 1 | COMPLETED → COMPLETED | TIMEOUT_SITUATION → ORDER_STATUS | 3/5 | 类型不符（fixed=透明关键词表 / agent=桩固定 `ORDER_STATUS`）——**桩不是成绩** |
| agent | 04 | 1 | COMPLETED → COMPLETED | TIMEOUT_SITUATION → ORDER_STATUS | 2/5 | 类型不符（fixed=透明关键词表 / agent=桩固定 `ORDER_STATUS`）——**桩不是成绩** |
| agent | 05 | 1 | COMPLETED → COMPLETED | REASSIGN_HISTORY → ORDER_STATUS | 1/2 | 类型不符（fixed=透明关键词表 / agent=桩固定 `ORDER_STATUS`）——**桩不是成绩** |
| fixed | 06 | 1 | COMPLETED → COMPLETED | REASSIGN_HISTORY → UNSUPPORTED | 0/2 | 类型不符（fixed=透明关键词表 / agent=桩固定 `ORDER_STATUS`）——**桩不是成绩** |
| agent | 06 | 1 | COMPLETED → COMPLETED | REASSIGN_HISTORY → ORDER_STATUS | 1/2 | 类型不符（fixed=透明关键词表 / agent=桩固定 `ORDER_STATUS`）——**桩不是成绩** |
| fixed | 07 | 1 | COMPLETED → COMPLETED | TIMEOUT_SITUATION → TIMEOUT_SITUATION | 3/6 | 必需事实未被引用（fixed=基线的证据取舍 / agent=桩只引主单三事实） |
| agent | 07 | 1 | COMPLETED → COMPLETED | TIMEOUT_SITUATION → ORDER_STATUS | 2/6 | 类型不符（fixed=透明关键词表 / agent=桩固定 `ORDER_STATUS`）——**桩不是成绩** |
| fixed | 08 | 1 | COMPLETED → COMPLETED | TIMEOUT_SITUATION → UNSUPPORTED | 0/3 | 类型不符（fixed=透明关键词表 / agent=桩固定 `ORDER_STATUS`）——**桩不是成绩** |
| agent | 08 | 1 | COMPLETED → COMPLETED | TIMEOUT_SITUATION → ORDER_STATUS | 2/3 | 类型不符（fixed=透明关键词表 / agent=桩固定 `ORDER_STATUS`）——**桩不是成绩** |
| agent | 09 | 1 | COMPLETED → COMPLETED | UNSUPPORTED → ORDER_STATUS | 0/0 | 类型不符（fixed=透明关键词表 / agent=桩固定 `ORDER_STATUS`）——**桩不是成绩** |
| agent | 10 | 1 | COMPLETED → COMPLETED | UNSUPPORTED → ORDER_STATUS | 0/0 | 类型不符（fixed=透明关键词表 / agent=桩固定 `ORDER_STATUS`）——**桩不是成绩** |
| fixed | 11 | 1 | COMPLETED → COMPLETED | TIMEOUT_SITUATION → UNSUPPORTED | 0/3 | 类型不符（fixed=透明关键词表 / agent=桩固定 `ORDER_STATUS`）——**桩不是成绩** |
| agent | 11 | 1 | COMPLETED → COMPLETED | TIMEOUT_SITUATION → ORDER_STATUS | 2/3 | 类型不符（fixed=透明关键词表 / agent=桩固定 `ORDER_STATUS`）——**桩不是成绩** |
| fixed | 16 | 1 | CANCELLED(PERMISSION_REVOKED) → COMPLETED | N/A → UNSUPPORTED | 0/0 | 终态不符（实现/驱动） |
| fixed | 17 | 1 | INCOMPLETE(STATE_CHANGED) → COMPLETED | N/A → UNSUPPORTED | 0/0 | 终态不符（实现/驱动） |
| fixed | 18 | 1 | COMPLETED → COMPLETED | ORDER_STATUS → UNSUPPORTED | 0/3 | 类型不符（fixed=透明关键词表 / agent=桩固定 `ORDER_STATUS`）——**桩不是成绩** |
| fixed | 19 | 1 | FAILED(MODEL_PROTOCOL_ERROR) → COMPLETED | N/A → UNSUPPORTED | 0/0 | 终态不符（实现/驱动） |
| fixed | 20 | 1 | FAILED(NO_PROGRESS) → COMPLETED | N/A → UNSUPPORTED | 0/0 | 终态不符（实现/驱动） |
| fixed | 22 | 1 | FAILED(MODEL_HTTP_ERROR) → COMPLETED | N/A → UNSUPPORTED | 0/0 | 终态不符（实现/驱动） |
| fixed | 24 | 1 | INCOMPLETE(STATE_CHANGED) → COMPLETED | N/A → UNSUPPORTED | 0/0 | 终态不符（实现/驱动） |
| fixed | 01 | 2 | COMPLETED → COMPLETED | ORDER_STATUS → UNSUPPORTED | 0/3 | 类型不符（fixed=透明关键词表 / agent=桩固定 `ORDER_STATUS`）——**桩不是成绩** |
| agent | 02 | 2 | COMPLETED → COMPLETED | TIMEOUT_SITUATION → ORDER_STATUS | 3/4 | 类型不符（fixed=透明关键词表 / agent=桩固定 `ORDER_STATUS`）——**桩不是成绩** |
| fixed | 03 | 2 | COMPLETED → COMPLETED | TIMEOUT_SITUATION → UNSUPPORTED | 0/5 | 类型不符（fixed=透明关键词表 / agent=桩固定 `ORDER_STATUS`）——**桩不是成绩** |
| agent | 03 | 2 | COMPLETED → COMPLETED | TIMEOUT_SITUATION → ORDER_STATUS | 3/5 | 类型不符（fixed=透明关键词表 / agent=桩固定 `ORDER_STATUS`）——**桩不是成绩** |
| fixed | 04 | 2 | COMPLETED → COMPLETED | TIMEOUT_SITUATION → ORDER_STATUS | 3/5 | 类型不符（fixed=透明关键词表 / agent=桩固定 `ORDER_STATUS`）——**桩不是成绩** |
| agent | 04 | 2 | COMPLETED → COMPLETED | TIMEOUT_SITUATION → ORDER_STATUS | 2/5 | 类型不符（fixed=透明关键词表 / agent=桩固定 `ORDER_STATUS`）——**桩不是成绩** |
| agent | 05 | 2 | COMPLETED → COMPLETED | REASSIGN_HISTORY → ORDER_STATUS | 1/2 | 类型不符（fixed=透明关键词表 / agent=桩固定 `ORDER_STATUS`）——**桩不是成绩** |
| fixed | 06 | 2 | COMPLETED → COMPLETED | REASSIGN_HISTORY → UNSUPPORTED | 0/2 | 类型不符（fixed=透明关键词表 / agent=桩固定 `ORDER_STATUS`）——**桩不是成绩** |
| agent | 06 | 2 | COMPLETED → COMPLETED | REASSIGN_HISTORY → ORDER_STATUS | 1/2 | 类型不符（fixed=透明关键词表 / agent=桩固定 `ORDER_STATUS`）——**桩不是成绩** |
| fixed | 07 | 2 | COMPLETED → COMPLETED | TIMEOUT_SITUATION → TIMEOUT_SITUATION | 3/6 | 必需事实未被引用（fixed=基线的证据取舍 / agent=桩只引主单三事实） |
| agent | 07 | 2 | COMPLETED → COMPLETED | TIMEOUT_SITUATION → ORDER_STATUS | 2/6 | 类型不符（fixed=透明关键词表 / agent=桩固定 `ORDER_STATUS`）——**桩不是成绩** |
| fixed | 08 | 2 | COMPLETED → COMPLETED | TIMEOUT_SITUATION → UNSUPPORTED | 0/3 | 类型不符（fixed=透明关键词表 / agent=桩固定 `ORDER_STATUS`）——**桩不是成绩** |
| agent | 08 | 2 | COMPLETED → COMPLETED | TIMEOUT_SITUATION → ORDER_STATUS | 2/3 | 类型不符（fixed=透明关键词表 / agent=桩固定 `ORDER_STATUS`）——**桩不是成绩** |
| agent | 09 | 2 | COMPLETED → COMPLETED | UNSUPPORTED → ORDER_STATUS | 0/0 | 类型不符（fixed=透明关键词表 / agent=桩固定 `ORDER_STATUS`）——**桩不是成绩** |
| agent | 10 | 2 | COMPLETED → COMPLETED | UNSUPPORTED → ORDER_STATUS | 0/0 | 类型不符（fixed=透明关键词表 / agent=桩固定 `ORDER_STATUS`）——**桩不是成绩** |
| fixed | 11 | 2 | COMPLETED → COMPLETED | TIMEOUT_SITUATION → UNSUPPORTED | 0/3 | 类型不符（fixed=透明关键词表 / agent=桩固定 `ORDER_STATUS`）——**桩不是成绩** |
| agent | 11 | 2 | COMPLETED → COMPLETED | TIMEOUT_SITUATION → ORDER_STATUS | 2/3 | 类型不符（fixed=透明关键词表 / agent=桩固定 `ORDER_STATUS`）——**桩不是成绩** |
| fixed | 16 | 2 | CANCELLED(PERMISSION_REVOKED) → COMPLETED | N/A → UNSUPPORTED | 0/0 | 终态不符（实现/驱动） |
| fixed | 17 | 2 | INCOMPLETE(STATE_CHANGED) → COMPLETED | N/A → UNSUPPORTED | 0/0 | 终态不符（实现/驱动） |
| fixed | 18 | 2 | COMPLETED → COMPLETED | ORDER_STATUS → UNSUPPORTED | 0/3 | 类型不符（fixed=透明关键词表 / agent=桩固定 `ORDER_STATUS`）——**桩不是成绩** |
| fixed | 19 | 2 | FAILED(MODEL_PROTOCOL_ERROR) → COMPLETED | N/A → UNSUPPORTED | 0/0 | 终态不符（实现/驱动） |
| fixed | 20 | 2 | FAILED(NO_PROGRESS) → COMPLETED | N/A → UNSUPPORTED | 0/0 | 终态不符（实现/驱动） |
| fixed | 22 | 2 | FAILED(MODEL_HTTP_ERROR) → COMPLETED | N/A → UNSUPPORTED | 0/0 | 终态不符（实现/驱动） |
| fixed | 24 | 2 | INCOMPLETE(STATE_CHANGED) → COMPLETED | N/A → UNSUPPORTED | 0/0 | 终态不符（实现/驱动） |
| fixed | 01 | 3 | COMPLETED → COMPLETED | ORDER_STATUS → UNSUPPORTED | 0/3 | 类型不符（fixed=透明关键词表 / agent=桩固定 `ORDER_STATUS`）——**桩不是成绩** |
| agent | 02 | 3 | COMPLETED → COMPLETED | TIMEOUT_SITUATION → ORDER_STATUS | 3/4 | 类型不符（fixed=透明关键词表 / agent=桩固定 `ORDER_STATUS`）——**桩不是成绩** |
| fixed | 03 | 3 | COMPLETED → COMPLETED | TIMEOUT_SITUATION → UNSUPPORTED | 0/5 | 类型不符（fixed=透明关键词表 / agent=桩固定 `ORDER_STATUS`）——**桩不是成绩** |
| agent | 03 | 3 | COMPLETED → COMPLETED | TIMEOUT_SITUATION → ORDER_STATUS | 3/5 | 类型不符（fixed=透明关键词表 / agent=桩固定 `ORDER_STATUS`）——**桩不是成绩** |
| fixed | 04 | 3 | COMPLETED → COMPLETED | TIMEOUT_SITUATION → ORDER_STATUS | 3/5 | 类型不符（fixed=透明关键词表 / agent=桩固定 `ORDER_STATUS`）——**桩不是成绩** |
| agent | 04 | 3 | COMPLETED → COMPLETED | TIMEOUT_SITUATION → ORDER_STATUS | 2/5 | 类型不符（fixed=透明关键词表 / agent=桩固定 `ORDER_STATUS`）——**桩不是成绩** |
| agent | 05 | 3 | COMPLETED → COMPLETED | REASSIGN_HISTORY → ORDER_STATUS | 1/2 | 类型不符（fixed=透明关键词表 / agent=桩固定 `ORDER_STATUS`）——**桩不是成绩** |
| fixed | 06 | 3 | COMPLETED → COMPLETED | REASSIGN_HISTORY → UNSUPPORTED | 0/2 | 类型不符（fixed=透明关键词表 / agent=桩固定 `ORDER_STATUS`）——**桩不是成绩** |
| agent | 06 | 3 | COMPLETED → COMPLETED | REASSIGN_HISTORY → ORDER_STATUS | 1/2 | 类型不符（fixed=透明关键词表 / agent=桩固定 `ORDER_STATUS`）——**桩不是成绩** |
| fixed | 07 | 3 | COMPLETED → COMPLETED | TIMEOUT_SITUATION → TIMEOUT_SITUATION | 3/6 | 必需事实未被引用（fixed=基线的证据取舍 / agent=桩只引主单三事实） |
| agent | 07 | 3 | COMPLETED → COMPLETED | TIMEOUT_SITUATION → ORDER_STATUS | 2/6 | 类型不符（fixed=透明关键词表 / agent=桩固定 `ORDER_STATUS`）——**桩不是成绩** |
| fixed | 08 | 3 | COMPLETED → COMPLETED | TIMEOUT_SITUATION → UNSUPPORTED | 0/3 | 类型不符（fixed=透明关键词表 / agent=桩固定 `ORDER_STATUS`）——**桩不是成绩** |
| agent | 08 | 3 | COMPLETED → COMPLETED | TIMEOUT_SITUATION → ORDER_STATUS | 2/3 | 类型不符（fixed=透明关键词表 / agent=桩固定 `ORDER_STATUS`）——**桩不是成绩** |
| agent | 09 | 3 | COMPLETED → COMPLETED | UNSUPPORTED → ORDER_STATUS | 0/0 | 类型不符（fixed=透明关键词表 / agent=桩固定 `ORDER_STATUS`）——**桩不是成绩** |
| agent | 10 | 3 | COMPLETED → COMPLETED | UNSUPPORTED → ORDER_STATUS | 0/0 | 类型不符（fixed=透明关键词表 / agent=桩固定 `ORDER_STATUS`）——**桩不是成绩** |
| fixed | 11 | 3 | COMPLETED → COMPLETED | TIMEOUT_SITUATION → UNSUPPORTED | 0/3 | 类型不符（fixed=透明关键词表 / agent=桩固定 `ORDER_STATUS`）——**桩不是成绩** |
| agent | 11 | 3 | COMPLETED → COMPLETED | TIMEOUT_SITUATION → ORDER_STATUS | 2/3 | 类型不符（fixed=透明关键词表 / agent=桩固定 `ORDER_STATUS`）——**桩不是成绩** |
| fixed | 16 | 3 | CANCELLED(PERMISSION_REVOKED) → COMPLETED | N/A → UNSUPPORTED | 0/0 | 终态不符（实现/驱动） |
| fixed | 17 | 3 | INCOMPLETE(STATE_CHANGED) → COMPLETED | N/A → UNSUPPORTED | 0/0 | 终态不符（实现/驱动） |
| fixed | 18 | 3 | COMPLETED → COMPLETED | ORDER_STATUS → UNSUPPORTED | 0/3 | 类型不符（fixed=透明关键词表 / agent=桩固定 `ORDER_STATUS`）——**桩不是成绩** |
| fixed | 19 | 3 | FAILED(MODEL_PROTOCOL_ERROR) → COMPLETED | N/A → UNSUPPORTED | 0/0 | 终态不符（实现/驱动） |
| fixed | 20 | 3 | FAILED(NO_PROGRESS) → COMPLETED | N/A → UNSUPPORTED | 0/0 | 终态不符（实现/驱动） |
| fixed | 22 | 3 | FAILED(MODEL_HTTP_ERROR) → COMPLETED | N/A → UNSUPPORTED | 0/0 | 终态不符（实现/驱动） |
| fixed | 24 | 3 | INCOMPLETE(STATE_CHANGED) → COMPLETED | N/A → UNSUPPORTED | 0/0 | 终态不符（实现/驱动） |

## 4. 确定性判据

- 同一批夹具 × 3 次：逐例终态与 problemType 一致（断言在 harness 里，失败会直接报错）：**通过**
- 桩是确定性的（按 transcript 决定，无随机）：**通过**

## 5. 失败保留证明（故意留一条失败用例）

- 除上面自然产生的失败外，harness 还做了一次**负向自检**：把某例的 `expect_terminal` 故意改成
  `FAILED(SENTINEL_DELIBERATE)`，断言判分器**判它失败**、且它**出现在失败清单里**。
  该自检**不进 144 的分母**——它证明的是判分器不吞失败，不是用例结果。**已通过**（见运行日志）。

## 6. 注入槽是怎么被驱动的（场景驱动，不是自然语言提问）

| 槽 | 场景 | 驱动方式 |
| --- | --- | --- |
| 16 | 运行中撤权限 | 桩首轮回话**前**删掉调用者的 `DEPT_ADMIN` 绑定 → `PermissionRecheck` 下次工具调用前发现 → `CANCELLED(PERMISSION_REVOKED)` |
| 17 | 对照单调出部门 | 桩先让模型查对照单、再把对照单提交人调出部门、再 finish → `FinalReview` 复核发现 → `INCOMPLETE(STATE_CHANGED)` |
| 19 | 非法批次 | 桩回**同 callId 不同参数**的一轮 → `MODEL_PROTOCOL_ERROR`（该轮任何工具都不执行） |
| 20 | 无进展重复 | 桩**每轮都回同一工具同一参数** → 连续两轮无新证据 → `NO_PROGRESS` |
| 21 | 429 可恢复 | 桩首次回 429 → `HttpAgentModel` 有界重试 → 其后正常收尾 |
| 22 | 401 | 桩回 401 → 非 429 的 4xx **不重试** → `MODEL_HTTP_ERROR` |
| 23 | 工具故障 / 池饱和 | **本轮未驱动**：没有可注入故障的工具边界；本槽的「名额不提前释放」由 `InvestigationConcurrencyTest`（5 条）覆盖，「与成功空集区分」由 `TOOL_FAILED` 路径覆盖——真机端到端留 S6 后段 |
| 24 | 运行中状态变化 | 桩首轮回话**前**改 `sla_deadline` → `FinalReview` 逐字段比对发现 → `INCOMPLETE(STATE_CHANGED)` |

## 7. 真模型那一遍的成本与时长估算（供委托方批准）

| 项 | 估算 | 依据 |
| --- | --- | --- |
| 计划调查次数 | 24 × 2 × 3 = **144** | 手册 L145 |
| 其中会用模型的 | **72**（agent 侧；fixed 无模型调用） | 基线是确定性流程 |
| 物理模型调用 | 约 **144–160**（每 run ≈ 2 次 + 少量重试） | 本 harness 桩跑通的调用形态 |
| token 量级 | 输入 ≈ **35 万–60 万**、输出 ≈ **3 万**（粗估） | 每次调用含系统提示 + 工具定义 + 累积 transcript |
| 墙钟（顺序） | ≈ **10–25 分钟** | D89 实测单次调用 2.2–26.1s、median ≈ 3s；144–160 次 + 重试 |

> **费用口径**：按供应商当期价目 × 实际 token（跑完以 usage 为准，**不凭记忆估**，手册 §6.1）。
> **与 S5 的分工**：S5 验的是**资源与主业务影响**（不泄漏、有界、主业务错误 0）；
> S6 验的是**质量与收益**（保留全部失败的成对对照）。两者证据不能互相替代。

## 8. D19 清理留痕（先按最宽口径统计再 DROP）

统计口径 = **专用库里每一张表都数一遍**：

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
| t_user | 77 |
| t_user_role | 25 |
| t_work_order | 58 |
| t_work_order_log | 63 |

- 清理动作：`DROP DATABASE work_order_holdout`（只作用于本轮自建的、名字带 `work_order_holdout` 前缀的库）
- 共享的 `work_order_test` 与业务库**未被写入**（只读了表结构 + `t_role`）
