# S6 阶段 2：holdout **真模型**对照记录（2026-10-07）

> 口径：24 例 × 2 方案（fixed / agent）× 3 次 = **144 次**，分母固定（失败与超时保留）。
> **单次运行**（只跑一遍、不取最好一次）；同数据快照、同权限、同任务集、同一最高预算。
> 模型 = **真供应商**（key 不回显）；harness 的**转发代理原样转发请求体、原样回传响应**。
> 延迟含**真实网络 + 本机库**——是「本机 vs 真模型」的数字，**不是**生产延迟（手册 L139）。

| 项 | 值 |
| --- | --- |
| 日期 | 2026-10-07 |
| 用例文件 | `scripts/agent-eval-holdout.json`（冻结 24 例，**不改期望**） |
| 专用库 | `work_order_holdout`（结构克隆自 `work_order_test` + `t_role`；跑完按 D19 最宽口径统计后 DROP） |
| 方案 | `fixed`（FixedFlowInvestigator，**0 次模型调用**）/ `agent`（InvestigationAgent） |
| 模型端点 | **真供应商**（`llm.api.url` 指向本 harness 的转发代理，代理再打真供应商；key 不回显） |
| 比例 | 24 例 × 2 方案 × 3 次 = **144 次**（分母固定；失败与超时保留） |
| 耗时口径 | 真模型 + 本机库 → **非生产延迟**（手册 L139） |

## 0. 公平性口径（跑之前先钉死）

- **报告契约没有自由文本**：报告只提交 `problemType` + 证据编号 + 建议编号，**正文由后端按证据渲染**（§3.2）
  → **基线不可能「交给模型生成报告」**。
- 两者差异**只在「谁决定引用哪些证据」**（规则 vs 模型）——这正是手册 L116 说的**唯一变量**。
- **基线侧模型调用 = 0 次**（确定性流程）；**agent 侧 = 72 次调查**会调模型。
- **同工具集 / 同权限 / 同预算 / 同渲染**：两边都经同一个 `AgentToolRegistry`（同四个工具）、
  同一个 `ToolContext` 构造路径（`resolveDepartmentScope`）、同一 `AgentLimits` 上限、同一个 `AgentReportRenderer`。
- **没有剥夺基线的任何数据访问能力**（L110 硬要求）：基线用的是**同一批工具**、**同一权限**、**同一预算上限**；
  基线唯一的差异在**问题类型分类用透明关键词**（§3.1 L118 允许，且只在开发集调优）。

## 1. 汇总（分母固定 = 计划 run 数）

| 方案 | 计划 run | 通过 | 终态一致 | 类型一致 | 事实覆盖 | 物理模型调用 | 失败 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| fixed | 72 | 30 | 54 | 51 | 48 | 0 | 42 |
| agent | 72 | 51 | 60 | 59 | 60 | 82 | 21 |

> 上表**是**本轮对照（真模型）。结论与依据见 §7 与 D 条目。

- 证据覆盖（必需事实被引用数 / 应覆盖数）：**132 / 234**
- 工具调用合计：**209** 次
- **物理模型调用**（agent 侧）：**82** 次；fixed 侧 **0** 次
- **token（实测 usage）**：输入 **141217** + 输出 **86036**；缺 usage 的响应 **0** 次（拿不到记 unknown，不按 0 均摊）
- 单次耗时 min/median/max：**4 / 30 / 60012 ms**（真模型 + 本机库，**非生产延迟**）

## 2. 逐例结果（每例每方案 3 次全部保留）

| 方案 | 例 | 次 | 期望终态 | 实际终态 | 期望类型 | 实际类型 | 覆盖 | 工具调用 | 物理调用 | tok_in | tok_out | 耗时(ms) | 通过 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| fixed | 01 | 1 | COMPLETED | COMPLETED | ORDER_STATUS | UNSUPPORTED | 0/3 | 1 | 0 | 0 | 0 | 81 | ❌ |
| agent | 01 | 1 | COMPLETED | TIMED_OUT(MODEL_TIMEOUT) | ORDER_STATUS | （无报告） | 0/3 | 1 | 0 | 0 | 0 | 45041 | ❌ |
| fixed | 02 | 1 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | TIMEOUT_SITUATION | 4/4 | 2 | 0 | 0 | 0 | 60 | ✅ |
| agent | 02 | 1 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | ORDER_STATUS | 3/4 | 2 | 2 | 3968 | 1207 | 5811 | ❌ |
| fixed | 03 | 1 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | UNSUPPORTED | 0/5 | 1 | 0 | 0 | 0 | 62 | ❌ |
| agent | 03 | 1 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | REASSIGN_HISTORY | 4/5 | 4 | 3 | 5094 | 13988 | 5673 | ❌ |
| fixed | 04 | 1 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | ORDER_STATUS | 3/5 | 1 | 0 | 0 | 0 | 59 | ❌ |
| agent | 04 | 1 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | TIMEOUT_SITUATION | 5/5 | 4 | 2 | 3758 | 1437 | 6390 | ✅ |
| fixed | 05 | 1 | COMPLETED | COMPLETED | REASSIGN_HISTORY | REASSIGN_HISTORY | 2/2 | 2 | 0 | 0 | 0 | 54 | ✅ |
| agent | 05 | 1 | COMPLETED | COMPLETED | REASSIGN_HISTORY | REASSIGN_HISTORY | 2/2 | 3 | 2 | 3286 | 500 | 3075 | ✅ |
| fixed | 06 | 1 | COMPLETED | COMPLETED | REASSIGN_HISTORY | UNSUPPORTED | 0/2 | 1 | 0 | 0 | 0 | 35 | ❌ |
| agent | 06 | 1 | COMPLETED | COMPLETED | REASSIGN_HISTORY | REASSIGN_HISTORY | 2/2 | 2 | 2 | 3217 | 1297 | 6130 | ✅ |
| fixed | 07 | 1 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | TIMEOUT_SITUATION | 3/6 | 2 | 0 | 0 | 0 | 53 | ❌ |
| agent | 07 | 1 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | TIMEOUT_SITUATION | 6/6 | 2 | 2 | 3112 | 1018 | 4771 | ✅ |
| fixed | 08 | 1 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | UNSUPPORTED | 0/3 | 1 | 0 | 0 | 0 | 26 | ❌ |
| agent | 08 | 1 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | TIMEOUT_SITUATION | 3/3 | 3 | 2 | 3415 | 1369 | 6419 | ✅ |
| fixed | 09 | 1 | COMPLETED | COMPLETED | UNSUPPORTED | UNSUPPORTED | 0/0 | 1 | 0 | 0 | 0 | 22 | ✅ |
| agent | 09 | 1 | COMPLETED | COMPLETED | UNSUPPORTED | UNSUPPORTED | 0/0 | 1 | 1 | 1389 | 282 | 1570 | ✅ |
| fixed | 10 | 1 | COMPLETED | COMPLETED | UNSUPPORTED | UNSUPPORTED | 0/0 | 1 | 0 | 0 | 0 | 30 | ✅ |
| agent | 10 | 1 | COMPLETED | COMPLETED | UNSUPPORTED | UNSUPPORTED | 0/0 | 1 | 1 | 1389 | 385 | 1970 | ✅ |
| fixed | 11 | 1 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | UNSUPPORTED | 0/3 | 1 | 0 | 0 | 0 | 22 | ❌ |
| agent | 11 | 1 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | UNSUPPORTED | 0/3 | 1 | 1 | 1393 | 430 | 2140 | ❌ |
| fixed | 12 | 1 | COMPLETED | COMPLETED | ORDER_STATUS | ORDER_STATUS | 3/3 | 1 | 0 | 0 | 0 | 17 | ✅ |
| agent | 12 | 1 | COMPLETED | COMPLETED | ORDER_STATUS | ORDER_STATUS | 3/3 | 1 | 1 | 1389 | 1575 | 7028 | ✅ |
| fixed | 13 | 1 | FAILED(FORBIDDEN) | FAILED(FORBIDDEN) | N/A | （无报告） | 0/0 | 1 | 0 | 0 | 0 | 7 | ✅ |
| agent | 13 | 1 | FAILED(FORBIDDEN) | FAILED(FORBIDDEN) | N/A | （无报告） | 0/0 | 1 | 0 | 0 | 0 | 8 | ✅ |
| fixed | 14 | 1 | FAILED(FORBIDDEN) | FAILED(FORBIDDEN) | N/A | （无报告） | 0/0 | 1 | 0 | 0 | 0 | 7 | ✅ |
| agent | 14 | 1 | FAILED(FORBIDDEN) | FAILED(FORBIDDEN) | N/A | （无报告） | 0/0 | 1 | 0 | 0 | 0 | 7 | ✅ |
| fixed | 15 | 1 | FAILED(FORBIDDEN) | FAILED(FORBIDDEN) | N/A | （无报告） | 0/0 | 1 | 0 | 0 | 0 | 7 | ✅ |
| agent | 15 | 1 | FAILED(FORBIDDEN) | FAILED(FORBIDDEN) | N/A | （无报告） | 0/0 | 1 | 0 | 0 | 0 | 6 | ✅ |
| fixed | 16 | 1 | CANCELLED(PERMISSION_REVOKED) | COMPLETED | N/A | UNSUPPORTED | 0/0 | 1 | 0 | 0 | 0 | 24 | ❌ |
| agent | 16 | 1 | CANCELLED(PERMISSION_REVOKED) | COMPLETED | N/A | UNSUPPORTED | 0/0 | 1 | 1 | 1398 | 258 | 1434 | ❌ |
| fixed | 17 | 1 | INCOMPLETE(STATE_CHANGED) | COMPLETED | N/A | UNSUPPORTED | 0/0 | 1 | 0 | 0 | 0 | 24 | ❌ |
| agent | 17 | 1 | INCOMPLETE(STATE_CHANGED) | COMPLETED | N/A | UNSUPPORTED | 0/0 | 1 | 1 | 1391 | 663 | 3289 | ❌ |
| fixed | 18 | 1 | COMPLETED | COMPLETED | ORDER_STATUS | UNSUPPORTED | 0/3 | 1 | 0 | 0 | 0 | 23 | ❌ |
| agent | 18 | 1 | COMPLETED | FAILED(MODEL_PROTOCOL_ERROR) | ORDER_STATUS | （无报告） | 0/3 | 1 | 1 | 1398 | 425 | 2239 | ❌ |
| fixed | 19 | 1 | FAILED(MODEL_PROTOCOL_ERROR) | COMPLETED | N/A | UNSUPPORTED | 0/0 | 1 | 0 | 0 | 0 | 19 | ❌ |
| agent | 19 | 1 | FAILED(MODEL_PROTOCOL_ERROR) | FAILED(MODEL_PROTOCOL_ERROR) | N/A | （无报告） | 0/0 | 1 | 0 | 0 | 0 | 23 | ✅ |
| fixed | 20 | 1 | FAILED(NO_PROGRESS) | COMPLETED | N/A | UNSUPPORTED | 0/0 | 1 | 0 | 0 | 0 | 26 | ❌ |
| agent | 20 | 1 | FAILED(NO_PROGRESS) | FAILED(NO_PROGRESS) | N/A | （无报告） | 0/0 | 3 | 0 | 0 | 0 | 27 | ✅ |
| fixed | 21 | 1 | COMPLETED | COMPLETED | N/A | UNSUPPORTED | 0/0 | 1 | 0 | 0 | 0 | 25 | ✅ |
| agent | 21 | 1 | COMPLETED | COMPLETED | N/A | UNSUPPORTED | 0/0 | 1 | 1 | 1386 | 625 | 3201 | ✅ |
| fixed | 22 | 1 | FAILED(MODEL_HTTP_ERROR) | COMPLETED | N/A | UNSUPPORTED | 0/0 | 1 | 0 | 0 | 0 | 29 | ❌ |
| agent | 22 | 1 | FAILED(MODEL_HTTP_ERROR) | FAILED(MODEL_HTTP_ERROR) | N/A | （无报告） | 0/0 | 1 | 0 | 0 | 0 | 21 | ✅ |
| fixed | 23 | 1 | COMPLETED | COMPLETED | N/A | UNSUPPORTED | 0/0 | 1 | 0 | 0 | 0 | 70 | ✅ |
| agent | 23 | 1 | COMPLETED | COMPLETED | N/A | UNSUPPORTED | 0/0 | 1 | 1 | 1391 | 808 | 3598 | ✅ |
| fixed | 24 | 1 | INCOMPLETE(STATE_CHANGED) | COMPLETED | N/A | UNSUPPORTED | 0/0 | 1 | 0 | 0 | 0 | 21 | ❌ |
| agent | 24 | 1 | INCOMPLETE(STATE_CHANGED) | INCOMPLETE(STATE_CHANGED) | N/A | （无报告） | 0/0 | 3 | 2 | 3666 | 2609 | 11666 | ✅ |
| fixed | 01 | 2 | COMPLETED | COMPLETED | ORDER_STATUS | UNSUPPORTED | 0/3 | 1 | 0 | 0 | 0 | 32 | ❌ |
| agent | 01 | 2 | COMPLETED | COMPLETED | ORDER_STATUS | ORDER_STATUS | 3/3 | 2 | 2 | 3831 | 1013 | 5372 | ✅ |
| fixed | 02 | 2 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | TIMEOUT_SITUATION | 4/4 | 2 | 0 | 0 | 0 | 42 | ✅ |
| agent | 02 | 2 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | ORDER_STATUS | 3/4 | 3 | 2 | 3622 | 884 | 4656 | ❌ |
| fixed | 03 | 2 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | UNSUPPORTED | 0/5 | 1 | 0 | 0 | 0 | 35 | ❌ |
| agent | 03 | 2 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | TIMEOUT_SITUATION | 5/5 | 3 | 2 | 3469 | 1704 | 7553 | ✅ |
| fixed | 04 | 2 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | ORDER_STATUS | 3/5 | 1 | 0 | 0 | 0 | 34 | ❌ |
| agent | 04 | 2 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | TIMEOUT_SITUATION | 5/5 | 4 | 2 | 3944 | 1264 | 5982 | ✅ |
| fixed | 05 | 2 | COMPLETED | COMPLETED | REASSIGN_HISTORY | REASSIGN_HISTORY | 2/2 | 2 | 0 | 0 | 0 | 56 | ✅ |
| agent | 05 | 2 | COMPLETED | COMPLETED | REASSIGN_HISTORY | REASSIGN_HISTORY | 2/2 | 3 | 2 | 3420 | 588 | 3089 | ✅ |
| fixed | 06 | 2 | COMPLETED | COMPLETED | REASSIGN_HISTORY | UNSUPPORTED | 0/2 | 1 | 0 | 0 | 0 | 24 | ❌ |
| agent | 06 | 2 | COMPLETED | COMPLETED | REASSIGN_HISTORY | REASSIGN_HISTORY | 2/2 | 2 | 2 | 3218 | 885 | 4594 | ✅ |
| fixed | 07 | 2 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | TIMEOUT_SITUATION | 3/6 | 2 | 0 | 0 | 0 | 37 | ❌ |
| agent | 07 | 2 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | TIMEOUT_SITUATION | 6/6 | 2 | 2 | 3112 | 1215 | 5947 | ✅ |
| fixed | 08 | 2 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | UNSUPPORTED | 0/3 | 1 | 0 | 0 | 0 | 25 | ❌ |
| agent | 08 | 2 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | TIMEOUT_SITUATION | 3/3 | 3 | 2 | 3351 | 1305 | 6185 | ✅ |
| fixed | 09 | 2 | COMPLETED | COMPLETED | UNSUPPORTED | UNSUPPORTED | 0/0 | 1 | 0 | 0 | 0 | 16 | ✅ |
| agent | 09 | 2 | COMPLETED | COMPLETED | UNSUPPORTED | UNSUPPORTED | 0/0 | 1 | 1 | 1389 | 260 | 1432 | ✅ |
| fixed | 10 | 2 | COMPLETED | COMPLETED | UNSUPPORTED | UNSUPPORTED | 0/0 | 1 | 0 | 0 | 0 | 26 | ✅ |
| agent | 10 | 2 | COMPLETED | COMPLETED | UNSUPPORTED | UNSUPPORTED | 0/0 | 1 | 1 | 1389 | 381 | 1899 | ✅ |
| fixed | 11 | 2 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | UNSUPPORTED | 0/3 | 1 | 0 | 0 | 0 | 21 | ❌ |
| agent | 11 | 2 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | UNSUPPORTED | 0/3 | 1 | 1 | 1393 | 295 | 1534 | ❌ |
| fixed | 12 | 2 | COMPLETED | COMPLETED | ORDER_STATUS | ORDER_STATUS | 3/3 | 1 | 0 | 0 | 0 | 26 | ✅ |
| agent | 12 | 2 | COMPLETED | COMPLETED | ORDER_STATUS | ORDER_STATUS | 3/3 | 1 | 1 | 1389 | 241 | 1606 | ✅ |
| fixed | 13 | 2 | FAILED(FORBIDDEN) | FAILED(FORBIDDEN) | N/A | （无报告） | 0/0 | 1 | 0 | 0 | 0 | 5 | ✅ |
| agent | 13 | 2 | FAILED(FORBIDDEN) | FAILED(FORBIDDEN) | N/A | （无报告） | 0/0 | 1 | 0 | 0 | 0 | 4 | ✅ |
| fixed | 14 | 2 | FAILED(FORBIDDEN) | FAILED(FORBIDDEN) | N/A | （无报告） | 0/0 | 1 | 0 | 0 | 0 | 5 | ✅ |
| agent | 14 | 2 | FAILED(FORBIDDEN) | FAILED(FORBIDDEN) | N/A | （无报告） | 0/0 | 1 | 0 | 0 | 0 | 6 | ✅ |
| fixed | 15 | 2 | FAILED(FORBIDDEN) | FAILED(FORBIDDEN) | N/A | （无报告） | 0/0 | 1 | 0 | 0 | 0 | 6 | ✅ |
| agent | 15 | 2 | FAILED(FORBIDDEN) | FAILED(FORBIDDEN) | N/A | （无报告） | 0/0 | 1 | 0 | 0 | 0 | 5 | ✅ |
| fixed | 16 | 2 | CANCELLED(PERMISSION_REVOKED) | COMPLETED | N/A | UNSUPPORTED | 0/0 | 1 | 0 | 0 | 0 | 18 | ❌ |
| agent | 16 | 2 | CANCELLED(PERMISSION_REVOKED) | COMPLETED | N/A | UNSUPPORTED | 0/0 | 1 | 1 | 1398 | 316 | 1327 | ❌ |
| fixed | 17 | 2 | INCOMPLETE(STATE_CHANGED) | COMPLETED | N/A | UNSUPPORTED | 0/0 | 1 | 0 | 0 | 0 | 15 | ❌ |
| agent | 17 | 2 | INCOMPLETE(STATE_CHANGED) | TIMED_OUT(RUN_BUDGET_EXCEEDED) | N/A | （无报告） | 0/0 | 5 | 1 | 1391 | 6197 | 60012 | ❌ |
| fixed | 18 | 2 | COMPLETED | COMPLETED | ORDER_STATUS | UNSUPPORTED | 0/3 | 1 | 0 | 0 | 0 | 18 | ❌ |
| agent | 18 | 2 | COMPLETED | COMPLETED | ORDER_STATUS | UNSUPPORTED | 0/3 | 1 | 1 | 1398 | 556 | 2704 | ❌ |
| fixed | 19 | 2 | FAILED(MODEL_PROTOCOL_ERROR) | COMPLETED | N/A | UNSUPPORTED | 0/0 | 1 | 0 | 0 | 0 | 15 | ❌ |
| agent | 19 | 2 | FAILED(MODEL_PROTOCOL_ERROR) | FAILED(MODEL_PROTOCOL_ERROR) | N/A | （无报告） | 0/0 | 1 | 0 | 0 | 0 | 10 | ✅ |
| fixed | 20 | 2 | FAILED(NO_PROGRESS) | COMPLETED | N/A | UNSUPPORTED | 0/0 | 1 | 0 | 0 | 0 | 18 | ❌ |
| agent | 20 | 2 | FAILED(NO_PROGRESS) | FAILED(NO_PROGRESS) | N/A | （无报告） | 0/0 | 3 | 0 | 0 | 0 | 19 | ✅ |
| fixed | 21 | 2 | COMPLETED | COMPLETED | N/A | UNSUPPORTED | 0/0 | 1 | 0 | 0 | 0 | 20 | ✅ |
| agent | 21 | 2 | COMPLETED | FAILED(MODEL_PROTOCOL_ERROR) | N/A | （无报告） | 0/0 | 1 | 1 | 1386 | 950 | 5468 | ❌ |
| fixed | 22 | 2 | FAILED(MODEL_HTTP_ERROR) | COMPLETED | N/A | UNSUPPORTED | 0/0 | 1 | 0 | 0 | 0 | 13 | ❌ |
| agent | 22 | 2 | FAILED(MODEL_HTTP_ERROR) | FAILED(MODEL_HTTP_ERROR) | N/A | （无报告） | 0/0 | 1 | 0 | 0 | 0 | 9 | ✅ |
| fixed | 23 | 2 | COMPLETED | COMPLETED | N/A | UNSUPPORTED | 0/0 | 1 | 0 | 0 | 0 | 31 | ✅ |
| agent | 23 | 2 | COMPLETED | COMPLETED | N/A | UNSUPPORTED | 0/0 | 1 | 2 | 9514 | 11564 | 5188 | ✅ |
| fixed | 24 | 2 | INCOMPLETE(STATE_CHANGED) | COMPLETED | N/A | UNSUPPORTED | 0/0 | 1 | 0 | 0 | 0 | 17 | ❌ |
| agent | 24 | 2 | INCOMPLETE(STATE_CHANGED) | INCOMPLETE(STATE_CHANGED) | N/A | （无报告） | 0/0 | 3 | 2 | 3529 | 2338 | 10635 | ✅ |
| fixed | 01 | 3 | COMPLETED | COMPLETED | ORDER_STATUS | UNSUPPORTED | 0/3 | 1 | 0 | 0 | 0 | 16 | ❌ |
| agent | 01 | 3 | COMPLETED | COMPLETED | ORDER_STATUS | ORDER_STATUS | 3/3 | 3 | 2 | 3798 | 1012 | 4519 | ✅ |
| fixed | 02 | 3 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | TIMEOUT_SITUATION | 4/4 | 2 | 0 | 0 | 0 | 15 | ✅ |
| agent | 02 | 3 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | ORDER_STATUS | 3/4 | 1 | 1 | 1389 | 722 | 3297 | ❌ |
| fixed | 03 | 3 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | UNSUPPORTED | 0/5 | 1 | 0 | 0 | 0 | 21 | ❌ |
| agent | 03 | 3 | COMPLETED | TIMED_OUT(MODEL_TIMEOUT) | TIMEOUT_SITUATION | （无报告） | 0/5 | 4 | 2 | 3365 | 344 | 47693 | ❌ |
| fixed | 04 | 3 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | ORDER_STATUS | 3/5 | 1 | 0 | 0 | 0 | 26 | ❌ |
| agent | 04 | 3 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | TIMEOUT_SITUATION | 5/5 | 3 | 3 | 5680 | 12449 | 4864 | ✅ |
| fixed | 05 | 3 | COMPLETED | COMPLETED | REASSIGN_HISTORY | REASSIGN_HISTORY | 2/2 | 2 | 0 | 0 | 0 | 20 | ✅ |
| agent | 05 | 3 | COMPLETED | COMPLETED | REASSIGN_HISTORY | REASSIGN_HISTORY | 2/2 | 1 | 1 | 1387 | 704 | 3102 | ✅ |
| fixed | 06 | 3 | COMPLETED | COMPLETED | REASSIGN_HISTORY | UNSUPPORTED | 0/2 | 1 | 0 | 0 | 0 | 15 | ❌ |
| agent | 06 | 3 | COMPLETED | COMPLETED | REASSIGN_HISTORY | REASSIGN_HISTORY | 2/2 | 2 | 2 | 3331 | 831 | 4143 | ✅ |
| fixed | 07 | 3 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | TIMEOUT_SITUATION | 3/6 | 2 | 0 | 0 | 0 | 30 | ❌ |
| agent | 07 | 3 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | TIMEOUT_SITUATION | 6/6 | 2 | 2 | 3095 | 845 | 4737 | ✅ |
| fixed | 08 | 3 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | UNSUPPORTED | 0/3 | 1 | 0 | 0 | 0 | 32 | ❌ |
| agent | 08 | 3 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | TIMEOUT_SITUATION | 3/3 | 2 | 2 | 3151 | 828 | 5325 | ✅ |
| fixed | 09 | 3 | COMPLETED | COMPLETED | UNSUPPORTED | UNSUPPORTED | 0/0 | 1 | 0 | 0 | 0 | 22 | ✅ |
| agent | 09 | 3 | COMPLETED | COMPLETED | UNSUPPORTED | UNSUPPORTED | 0/0 | 1 | 1 | 1389 | 253 | 1794 | ✅ |
| fixed | 10 | 3 | COMPLETED | COMPLETED | UNSUPPORTED | UNSUPPORTED | 0/0 | 1 | 0 | 0 | 0 | 16 | ✅ |
| agent | 10 | 3 | COMPLETED | FAILED(MODEL_PROTOCOL_ERROR) | UNSUPPORTED | （无报告） | 0/0 | 1 | 1 | 1389 | 574 | 2831 | ❌ |
| fixed | 11 | 3 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | UNSUPPORTED | 0/3 | 1 | 0 | 0 | 0 | 13 | ❌ |
| agent | 11 | 3 | COMPLETED | COMPLETED | TIMEOUT_SITUATION | UNSUPPORTED | 0/3 | 1 | 1 | 1393 | 499 | 2338 | ❌ |
| fixed | 12 | 3 | COMPLETED | COMPLETED | ORDER_STATUS | ORDER_STATUS | 3/3 | 1 | 0 | 0 | 0 | 21 | ✅ |
| agent | 12 | 3 | COMPLETED | COMPLETED | ORDER_STATUS | ORDER_STATUS | 3/3 | 1 | 1 | 1389 | 359 | 1894 | ✅ |
| fixed | 13 | 3 | FAILED(FORBIDDEN) | FAILED(FORBIDDEN) | N/A | （无报告） | 0/0 | 1 | 0 | 0 | 0 | 5 | ✅ |
| agent | 13 | 3 | FAILED(FORBIDDEN) | FAILED(FORBIDDEN) | N/A | （无报告） | 0/0 | 1 | 0 | 0 | 0 | 4 | ✅ |
| fixed | 14 | 3 | FAILED(FORBIDDEN) | FAILED(FORBIDDEN) | N/A | （无报告） | 0/0 | 1 | 0 | 0 | 0 | 4 | ✅ |
| agent | 14 | 3 | FAILED(FORBIDDEN) | FAILED(FORBIDDEN) | N/A | （无报告） | 0/0 | 1 | 0 | 0 | 0 | 4 | ✅ |
| fixed | 15 | 3 | FAILED(FORBIDDEN) | FAILED(FORBIDDEN) | N/A | （无报告） | 0/0 | 1 | 0 | 0 | 0 | 4 | ✅ |
| agent | 15 | 3 | FAILED(FORBIDDEN) | FAILED(FORBIDDEN) | N/A | （无报告） | 0/0 | 1 | 0 | 0 | 0 | 5 | ✅ |
| fixed | 16 | 3 | CANCELLED(PERMISSION_REVOKED) | COMPLETED | N/A | UNSUPPORTED | 0/0 | 1 | 0 | 0 | 0 | 20 | ❌ |
| agent | 16 | 3 | CANCELLED(PERMISSION_REVOKED) | COMPLETED | N/A | UNSUPPORTED | 0/0 | 1 | 1 | 1398 | 416 | 2322 | ❌ |
| fixed | 17 | 3 | INCOMPLETE(STATE_CHANGED) | COMPLETED | N/A | UNSUPPORTED | 0/0 | 1 | 0 | 0 | 0 | 14 | ❌ |
| agent | 17 | 3 | INCOMPLETE(STATE_CHANGED) | COMPLETED | N/A | UNSUPPORTED | 0/0 | 1 | 1 | 1391 | 1073 | 5387 | ❌ |
| fixed | 18 | 3 | COMPLETED | COMPLETED | ORDER_STATUS | UNSUPPORTED | 0/3 | 1 | 0 | 0 | 0 | 14 | ❌ |
| agent | 18 | 3 | COMPLETED | COMPLETED | ORDER_STATUS | UNSUPPORTED | 0/3 | 1 | 1 | 1398 | 789 | 3644 | ❌ |
| fixed | 19 | 3 | FAILED(MODEL_PROTOCOL_ERROR) | COMPLETED | N/A | UNSUPPORTED | 0/0 | 1 | 0 | 0 | 0 | 17 | ❌ |
| agent | 19 | 3 | FAILED(MODEL_PROTOCOL_ERROR) | FAILED(MODEL_PROTOCOL_ERROR) | N/A | （无报告） | 0/0 | 1 | 0 | 0 | 0 | 12 | ✅ |
| fixed | 20 | 3 | FAILED(NO_PROGRESS) | COMPLETED | N/A | UNSUPPORTED | 0/0 | 1 | 0 | 0 | 0 | 15 | ❌ |
| agent | 20 | 3 | FAILED(NO_PROGRESS) | FAILED(NO_PROGRESS) | N/A | （无报告） | 0/0 | 3 | 0 | 0 | 0 | 20 | ✅ |
| fixed | 21 | 3 | COMPLETED | COMPLETED | N/A | UNSUPPORTED | 0/0 | 1 | 0 | 0 | 0 | 15 | ✅ |
| agent | 21 | 3 | COMPLETED | FAILED(MODEL_PROTOCOL_ERROR) | N/A | （无报告） | 0/0 | 1 | 1 | 1386 | 779 | 3893 | ❌ |
| fixed | 22 | 3 | FAILED(MODEL_HTTP_ERROR) | COMPLETED | N/A | UNSUPPORTED | 0/0 | 1 | 0 | 0 | 0 | 22 | ❌ |
| agent | 22 | 3 | FAILED(MODEL_HTTP_ERROR) | FAILED(MODEL_HTTP_ERROR) | N/A | （无报告） | 0/0 | 1 | 0 | 0 | 0 | 8 | ✅ |
| fixed | 23 | 3 | COMPLETED | COMPLETED | N/A | UNSUPPORTED | 0/0 | 1 | 0 | 0 | 0 | 14 | ✅ |
| agent | 23 | 3 | COMPLETED | COMPLETED | N/A | UNSUPPORTED | 0/0 | 3 | 2 | 4002 | 1089 | 6079 | ✅ |
| fixed | 24 | 3 | INCOMPLETE(STATE_CHANGED) | COMPLETED | N/A | UNSUPPORTED | 0/0 | 1 | 0 | 0 | 0 | 15 | ❌ |
| agent | 24 | 3 | INCOMPLETE(STATE_CHANGED) | INCOMPLETE(STATE_CHANGED) | N/A | （无报告） | 0/0 | 4 | 3 | 6703 | 1638 | 8618 | ✅ |

## 3. 完整失败清单（63 条，不删难例）

> **失败全部保留**（下面每条都在）。真实失败来自：模型没按契约收尾 / 事实覆盖不足 /
> 该槽本身是**故障注入槽**（其 `question` 是场景描述、不是业务问题）。

| 方案 | 例 | 次 | 期望 → 实际（终态） | 期望 → 实际（类型） | 覆盖 | 根因归类 |
| --- | --- | --- | --- | --- | --- | --- |
| fixed | 01 | 1 | COMPLETED → COMPLETED | ORDER_STATUS → UNSUPPORTED | 0/3 | 类型不符（fixed=透明关键词表 / agent=真模型的选择） |
| agent | 01 | 1 | COMPLETED → TIMED_OUT(MODEL_TIMEOUT) | ORDER_STATUS → （无报告） | 0/3 | 终态不符（实现/驱动） |
| agent | 02 | 1 | COMPLETED → COMPLETED | TIMEOUT_SITUATION → ORDER_STATUS | 3/4 | 类型不符（fixed=透明关键词表 / agent=真模型的选择） |
| fixed | 03 | 1 | COMPLETED → COMPLETED | TIMEOUT_SITUATION → UNSUPPORTED | 0/5 | 类型不符（fixed=透明关键词表 / agent=真模型的选择） |
| agent | 03 | 1 | COMPLETED → COMPLETED | TIMEOUT_SITUATION → REASSIGN_HISTORY | 4/5 | 类型不符（fixed=透明关键词表 / agent=真模型的选择） |
| fixed | 04 | 1 | COMPLETED → COMPLETED | TIMEOUT_SITUATION → ORDER_STATUS | 3/5 | 类型不符（fixed=透明关键词表 / agent=真模型的选择） |
| fixed | 06 | 1 | COMPLETED → COMPLETED | REASSIGN_HISTORY → UNSUPPORTED | 0/2 | 类型不符（fixed=透明关键词表 / agent=真模型的选择） |
| fixed | 07 | 1 | COMPLETED → COMPLETED | TIMEOUT_SITUATION → TIMEOUT_SITUATION | 3/6 | 必需事实未被引用（证据覆盖不足） |
| fixed | 08 | 1 | COMPLETED → COMPLETED | TIMEOUT_SITUATION → UNSUPPORTED | 0/3 | 类型不符（fixed=透明关键词表 / agent=真模型的选择） |
| fixed | 11 | 1 | COMPLETED → COMPLETED | TIMEOUT_SITUATION → UNSUPPORTED | 0/3 | 类型不符（fixed=透明关键词表 / agent=真模型的选择） |
| agent | 11 | 1 | COMPLETED → COMPLETED | TIMEOUT_SITUATION → UNSUPPORTED | 0/3 | 类型不符（fixed=透明关键词表 / agent=真模型的选择） |
| fixed | 16 | 1 | CANCELLED(PERMISSION_REVOKED) → COMPLETED | N/A → UNSUPPORTED | 0/0 | 终态不符（实现/驱动） |
| agent | 16 | 1 | CANCELLED(PERMISSION_REVOKED) → COMPLETED | N/A → UNSUPPORTED | 0/0 | 终态不符（实现/驱动） |
| fixed | 17 | 1 | INCOMPLETE(STATE_CHANGED) → COMPLETED | N/A → UNSUPPORTED | 0/0 | 终态不符（实现/驱动） |
| agent | 17 | 1 | INCOMPLETE(STATE_CHANGED) → COMPLETED | N/A → UNSUPPORTED | 0/0 | 终态不符（实现/驱动） |
| fixed | 18 | 1 | COMPLETED → COMPLETED | ORDER_STATUS → UNSUPPORTED | 0/3 | 类型不符（fixed=透明关键词表 / agent=真模型的选择） |
| agent | 18 | 1 | COMPLETED → FAILED(MODEL_PROTOCOL_ERROR) | ORDER_STATUS → （无报告） | 0/3 | 终态不符（实现/驱动） |
| fixed | 19 | 1 | FAILED(MODEL_PROTOCOL_ERROR) → COMPLETED | N/A → UNSUPPORTED | 0/0 | 终态不符（实现/驱动） |
| fixed | 20 | 1 | FAILED(NO_PROGRESS) → COMPLETED | N/A → UNSUPPORTED | 0/0 | 终态不符（实现/驱动） |
| fixed | 22 | 1 | FAILED(MODEL_HTTP_ERROR) → COMPLETED | N/A → UNSUPPORTED | 0/0 | 终态不符（实现/驱动） |
| fixed | 24 | 1 | INCOMPLETE(STATE_CHANGED) → COMPLETED | N/A → UNSUPPORTED | 0/0 | 终态不符（实现/驱动） |
| fixed | 01 | 2 | COMPLETED → COMPLETED | ORDER_STATUS → UNSUPPORTED | 0/3 | 类型不符（fixed=透明关键词表 / agent=真模型的选择） |
| agent | 02 | 2 | COMPLETED → COMPLETED | TIMEOUT_SITUATION → ORDER_STATUS | 3/4 | 类型不符（fixed=透明关键词表 / agent=真模型的选择） |
| fixed | 03 | 2 | COMPLETED → COMPLETED | TIMEOUT_SITUATION → UNSUPPORTED | 0/5 | 类型不符（fixed=透明关键词表 / agent=真模型的选择） |
| fixed | 04 | 2 | COMPLETED → COMPLETED | TIMEOUT_SITUATION → ORDER_STATUS | 3/5 | 类型不符（fixed=透明关键词表 / agent=真模型的选择） |
| fixed | 06 | 2 | COMPLETED → COMPLETED | REASSIGN_HISTORY → UNSUPPORTED | 0/2 | 类型不符（fixed=透明关键词表 / agent=真模型的选择） |
| fixed | 07 | 2 | COMPLETED → COMPLETED | TIMEOUT_SITUATION → TIMEOUT_SITUATION | 3/6 | 必需事实未被引用（证据覆盖不足） |
| fixed | 08 | 2 | COMPLETED → COMPLETED | TIMEOUT_SITUATION → UNSUPPORTED | 0/3 | 类型不符（fixed=透明关键词表 / agent=真模型的选择） |
| fixed | 11 | 2 | COMPLETED → COMPLETED | TIMEOUT_SITUATION → UNSUPPORTED | 0/3 | 类型不符（fixed=透明关键词表 / agent=真模型的选择） |
| agent | 11 | 2 | COMPLETED → COMPLETED | TIMEOUT_SITUATION → UNSUPPORTED | 0/3 | 类型不符（fixed=透明关键词表 / agent=真模型的选择） |
| fixed | 16 | 2 | CANCELLED(PERMISSION_REVOKED) → COMPLETED | N/A → UNSUPPORTED | 0/0 | 终态不符（实现/驱动） |
| agent | 16 | 2 | CANCELLED(PERMISSION_REVOKED) → COMPLETED | N/A → UNSUPPORTED | 0/0 | 终态不符（实现/驱动） |
| fixed | 17 | 2 | INCOMPLETE(STATE_CHANGED) → COMPLETED | N/A → UNSUPPORTED | 0/0 | 终态不符（实现/驱动） |
| agent | 17 | 2 | INCOMPLETE(STATE_CHANGED) → TIMED_OUT(RUN_BUDGET_EXCEEDED) | N/A → （无报告） | 0/0 | 终态不符（实现/驱动） |
| fixed | 18 | 2 | COMPLETED → COMPLETED | ORDER_STATUS → UNSUPPORTED | 0/3 | 类型不符（fixed=透明关键词表 / agent=真模型的选择） |
| agent | 18 | 2 | COMPLETED → COMPLETED | ORDER_STATUS → UNSUPPORTED | 0/3 | 类型不符（fixed=透明关键词表 / agent=真模型的选择） |
| fixed | 19 | 2 | FAILED(MODEL_PROTOCOL_ERROR) → COMPLETED | N/A → UNSUPPORTED | 0/0 | 终态不符（实现/驱动） |
| fixed | 20 | 2 | FAILED(NO_PROGRESS) → COMPLETED | N/A → UNSUPPORTED | 0/0 | 终态不符（实现/驱动） |
| agent | 21 | 2 | COMPLETED → FAILED(MODEL_PROTOCOL_ERROR) | N/A → （无报告） | 0/0 | 终态不符（实现/驱动） |
| fixed | 22 | 2 | FAILED(MODEL_HTTP_ERROR) → COMPLETED | N/A → UNSUPPORTED | 0/0 | 终态不符（实现/驱动） |
| fixed | 24 | 2 | INCOMPLETE(STATE_CHANGED) → COMPLETED | N/A → UNSUPPORTED | 0/0 | 终态不符（实现/驱动） |
| fixed | 01 | 3 | COMPLETED → COMPLETED | ORDER_STATUS → UNSUPPORTED | 0/3 | 类型不符（fixed=透明关键词表 / agent=真模型的选择） |
| agent | 02 | 3 | COMPLETED → COMPLETED | TIMEOUT_SITUATION → ORDER_STATUS | 3/4 | 类型不符（fixed=透明关键词表 / agent=真模型的选择） |
| fixed | 03 | 3 | COMPLETED → COMPLETED | TIMEOUT_SITUATION → UNSUPPORTED | 0/5 | 类型不符（fixed=透明关键词表 / agent=真模型的选择） |
| agent | 03 | 3 | COMPLETED → TIMED_OUT(MODEL_TIMEOUT) | TIMEOUT_SITUATION → （无报告） | 0/5 | 终态不符（实现/驱动） |
| fixed | 04 | 3 | COMPLETED → COMPLETED | TIMEOUT_SITUATION → ORDER_STATUS | 3/5 | 类型不符（fixed=透明关键词表 / agent=真模型的选择） |
| fixed | 06 | 3 | COMPLETED → COMPLETED | REASSIGN_HISTORY → UNSUPPORTED | 0/2 | 类型不符（fixed=透明关键词表 / agent=真模型的选择） |
| fixed | 07 | 3 | COMPLETED → COMPLETED | TIMEOUT_SITUATION → TIMEOUT_SITUATION | 3/6 | 必需事实未被引用（证据覆盖不足） |
| fixed | 08 | 3 | COMPLETED → COMPLETED | TIMEOUT_SITUATION → UNSUPPORTED | 0/3 | 类型不符（fixed=透明关键词表 / agent=真模型的选择） |
| agent | 10 | 3 | COMPLETED → FAILED(MODEL_PROTOCOL_ERROR) | UNSUPPORTED → （无报告） | 0/0 | 终态不符（实现/驱动） |
| fixed | 11 | 3 | COMPLETED → COMPLETED | TIMEOUT_SITUATION → UNSUPPORTED | 0/3 | 类型不符（fixed=透明关键词表 / agent=真模型的选择） |
| agent | 11 | 3 | COMPLETED → COMPLETED | TIMEOUT_SITUATION → UNSUPPORTED | 0/3 | 类型不符（fixed=透明关键词表 / agent=真模型的选择） |
| fixed | 16 | 3 | CANCELLED(PERMISSION_REVOKED) → COMPLETED | N/A → UNSUPPORTED | 0/0 | 终态不符（实现/驱动） |
| agent | 16 | 3 | CANCELLED(PERMISSION_REVOKED) → COMPLETED | N/A → UNSUPPORTED | 0/0 | 终态不符（实现/驱动） |
| fixed | 17 | 3 | INCOMPLETE(STATE_CHANGED) → COMPLETED | N/A → UNSUPPORTED | 0/0 | 终态不符（实现/驱动） |
| agent | 17 | 3 | INCOMPLETE(STATE_CHANGED) → COMPLETED | N/A → UNSUPPORTED | 0/0 | 终态不符（实现/驱动） |
| fixed | 18 | 3 | COMPLETED → COMPLETED | ORDER_STATUS → UNSUPPORTED | 0/3 | 类型不符（fixed=透明关键词表 / agent=真模型的选择） |
| agent | 18 | 3 | COMPLETED → COMPLETED | ORDER_STATUS → UNSUPPORTED | 0/3 | 类型不符（fixed=透明关键词表 / agent=真模型的选择） |
| fixed | 19 | 3 | FAILED(MODEL_PROTOCOL_ERROR) → COMPLETED | N/A → UNSUPPORTED | 0/0 | 终态不符（实现/驱动） |
| fixed | 20 | 3 | FAILED(NO_PROGRESS) → COMPLETED | N/A → UNSUPPORTED | 0/0 | 终态不符（实现/驱动） |
| agent | 21 | 3 | COMPLETED → FAILED(MODEL_PROTOCOL_ERROR) | N/A → （无报告） | 0/0 | 终态不符（实现/驱动） |
| fixed | 22 | 3 | FAILED(MODEL_HTTP_ERROR) → COMPLETED | N/A → UNSUPPORTED | 0/0 | 终态不符（实现/驱动） |
| fixed | 24 | 3 | INCOMPLETE(STATE_CHANGED) → COMPLETED | N/A → UNSUPPORTED | 0/0 | 终态不符（实现/驱动） |

## 4. 复现性（同一批夹具 × 3 次）

- 逐例（方案 × 例）三次终态与 problemType **完全一致**的：**42 / 48**
- ⚠ 真模型**不保证**逐次一致（temperature=0 也不保证可复现）——不一致的行**如实保留**，不做断言。

## 5. 失败保留证明（故意留一条失败用例）

- 除上面自然产生的失败外，harness 还做了一次**负向自检**：把某例的 `expect_terminal` 故意改成
  `FAILED(SENTINEL_DELIBERATE)`，断言判分器**判它失败**、且它**出现在失败清单里**。
  该自检**不进 144 的分母**——它证明的是判分器不吞失败，不是用例结果。**已通过**（见运行日志）。

## 6. 注入槽是怎么被驱动的（场景驱动，不是自然语言提问）

| 槽 | 场景 | 驱动方式 |
| --- | --- | --- |
| 16 | 运行中撤权限 | 首次模型轮**之前**删掉调用者的 `DEPT_ADMIN` 绑定 → 模型照常回，`PermissionRecheck` 在工具调用前发现 → `CANCELLED(PERMISSION_REVOKED)` |
| 17 | 对照单调出部门 | 首次模型轮**之前**把对照单提交人调出部门 → 真模型继续跑（是否引用对照单由**模型**决定） |
| 19 | 非法批次 | **代理合成**一轮（同 callId 不同参数）→ `MODEL_PROTOCOL_ERROR`；**不调供应商**（纯协议故障） |
| 20 | 无进展重复 | **代理合成**（每轮同工具同参数）→ `NO_PROGRESS`；**不调供应商**（纯协议故障） |
| 21 | 429 可恢复 | 代理首次回 **429** → `HttpAgentModel` 有界重试 → **转发真供应商**收尾 |
| 22 | 401 | **代理合成** 401 → 非 429 的 4xx **不重试** → `MODEL_HTTP_ERROR`；**不调供应商** |
| 23 | 工具故障 / 池饱和 | **本轮未驱动**：没有可注入故障的工具边界；本槽的「名额不提前释放」由 `InvestigationConcurrencyTest`（5 条）覆盖，「与成功空集区分」由 `TOOL_FAILED` 路径覆盖——真机端到端留 S6 后段 |
| 24 | 运行中状态变化 | 首次模型轮**之前**改 `sla_deadline` → 真模型照常收尾 → `FinalReview` 逐字段比对发现 → `INCOMPLETE(STATE_CHANGED)` |

> ⚠ **B 层 / C 层分开**（手册 L176）：19/20/22 是**协议故障**（`question` 是注入描述、不构成业务问题），
> 由代理合成、**不调真供应商**——它们**不混作「模型答错」**；C 层（质量）看的是其余槽。

## 7. 结论：业务默认方案（真模型实测）

| 维度 | fixed | agent |
| --- | --- | --- |
| 通过 / 计划 run | 30 / 72 | 51 / 72 |
| 物理模型调用合计 | 0 | 82 |
| token（输入+输出） | 0 | 141217 + 86036 |
| 单次耗时 median | 21 ms | 3102 ms |

> **判据**（手册 L219 / L222）：质量**持平或更差**、而 agent 调用更多/更慢 → **业务默认走固定流程**；
> 20% 只是**小样本探索阈值**，**不得**写成统计显著或已测改善；反向（agent 更差）也要如实写。
> **本轮的默认方案结论**：agent 通过 51/72，fixed 30/72；agent 比 fixed **多**，且 agent 明显更慢、更贵 → 见 D 条目的最终裁决。
> **与 S5 的分工**：S5 验的是**资源与主业务影响**（不泄漏、有界、主业务错误 0）；
> S6 验的是**质量与收益**（保留全部失败的成对对照）。两者证据不能互相替代。

## 8. D19 清理留痕（先按最宽口径统计再 DROP）

统计口径 = **专用库里每一张表都数一遍**：

| 表 | 行数 |
| --- | --- |
| t_consume_record | 0 |
| t_event_outbox | 0 |
| t_message_retry | 0 |
| t_notification | 35 |
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
