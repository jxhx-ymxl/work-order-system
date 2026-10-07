# 真供应商端到端（本机第一手记录）

| 项 | 值 |
| --- | --- |
| 日期 | 2026-10-06 |
| 模型 | `deepseek-flash`（真 endpoint，非桩） |
| endpoint | `https://api.deepseek.com/v1/chat/completions`（**key 不回显**） |
| 链路 | 受理层 `AgentInvestigationService.investigate(userId, orderNo, question)` → `mode=agent` → 真工具（本机库）→ 校验 → 渲染 |
| 专用库 | `work_order_e2e`（结构克隆自 `work_order_test`；跑完按 D19 最宽口径统计后 DROP） |
| 问题原文 | 工单 WO-20261006-70001 现在到哪一步了？之前是谁处理过？最近几条记录里没有那次转手，帮我看看更早的记录。 |
| 终态 | **COMPLETED** |
| 证据条数 | **8** |
| 报告字段 | problemType=`REASSIGN_HISTORY`、evidenceIds=[E1, E2, E3, E6, E7, E8, E10, E11]、suggestionIds=[CONTACT_ASSIGNEE] |
| 渲染三段 | 齐全 |
| 模型调用次数 | **3** |
| 4xx/5xx | **无** |
| 端到端耗时 | **18918 ms**（真实供应商 + 本机库；**不是**桩延迟，也不是生产环境数字） |

## 逐次模型调用（第一手）

| # | 结果 | 该轮 tool_calls 数 | 响应字节 | 耗时(ms) | 失败码 |
| --- | --- | --- | --- | --- | --- |
| 1 | OK | 2 | 2802 | 2922 | — |
| 2 | OK | 1 | 1508 | 2229 | — |
| 3 | OK | 1 | 12249 | 13582 | — |

## 渲染文本（原文照录）

> **更正行（2026-10-08；上面与下面的原文一律保留，不重写）**：本节是 **2026-10-06** 的**第一手照录**，
> 当时正文用的是**机器字段名**（`order.status：IN_PROGRESS`）。2026-10-08 起渲染器改成**中文显示名**
> （`工单状态：处理中`），**三段标题不变**——所以本节的字样**不再代表当前输出**，只作为那一次运行的证据保留。
> 新样板与口径见 `deploy/DEMO-SCRIPT.md`（预期返回）与 `docs/DECISIONS.md` **D106**。

```
【已核实事实】
- order.exists：true
- order.status：IN_PROGRESS
- order.assignee：e*
- order.accept_events：ASSIGN@2026-09-27 23:51 by e*；ACCEPT@2026-09-28 00:51 by e*；ASSIGN@2026-09-28 01:51 by e*；ACCEPT@2026-09-28 02:51 by e*；RELEASE@2026-09-28 03:51 by 系统操作；ACCEPT@2026-09-28 04:51 by e*；ASSIGN@2026-09-28 05:51 by e*；ACCEPT@2026-09-28 06:51 by e*；ASSIGN@2026-09-28 07:51 by e*；RELEASE@2026-09-28 08:51 by 系统操作；ASSIGN@2026-09-28 09:51 by e*；ACCEPT@2026-09-28 10:51 by e*；ASSIGN@2026-09-28 11:51 by e*；ACCEPT@2026-09-28 12:51 by e*；RELEASE@2026-09-28 13:51 by 系统操作；ACCEPT@2026-09-28 14:51 by e*；ASSIGN@2026-09-28 15:51 by e*；ACCEPT@2026-09-28 16:51 by e*；ASSIGN@2026-09-28 17:51 by e*；RELEASE@2026-09-28 18:51 by 系统操作；ASSIGN@2026-09-28 19:51 by e*；ACCEPT@2026-09-28 20:51 by e*；ASSIGN@2026-09-28 21:51 by e*；ACCEPT@2026-09-28 22:51 by e*；RELEASE@2026-09-28 23:51 by 系统操作
- order.logs_page：ACCEPT@2026-09-28 04:51 by e*；ASSIGN@2026-09-28 05:51 by e*；ACCEPT@2026-09-28 06:51 by e*；ASSIGN@2026-09-28 07:51 by e*；RELEASE@2026-09-28 08:51 by 系统操作；ASSIGN@2026-09-28 09:51 by e*；ACCEPT@2026-09-28 10:51 by e*；ASSIGN@2026-09-28 11:51 by e*；ACCEPT@2026-09-28 12:51 by e*；RELEASE@2026-09-28 13:51 by 系统操作；ACCEPT@2026-09-28 14:51 by e*；ASSIGN@2026-09-28 15:51 by e*；ACCEPT@2026-09-28 16:51 by e*；ASSIGN@2026-09-28 17:51 by e*；RELEASE@2026-09-28 18:51 by 系统操作；ASSIGN@2026-09-28 19:51 by e*；ACCEPT@2026-09-28 20:51 by e*；ASSIGN@2026-09-28 21:51 by e*；ACCEPT@2026-09-28 22:51 by e*；RELEASE@2026-09-28 23:51 by 系统操作
- order.logs_page_has_more：true
- order.logs_page：ASSIGN@2026-09-27 23:51 by e*；ACCEPT@2026-09-28 00:51 by e*；ASSIGN@2026-09-28 01:51 by e*；ACCEPT@2026-09-28 02:51 by e*；RELEASE@2026-09-28 03:51 by 系统操作
- order.logs_page_has_more：false

【证据缺口】
- 未核实：（无）

【下一步核实建议】
- 联系当前处理人确认进度

```

## D19 清理留痕（先按最宽口径统计再 DROP）

统计口径 = **专用库里每张表都数一遍**：

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
| t_user | 3 |
| t_user_role | 4 |
| t_work_order | 1 |
| t_work_order_log | 25 |

- 清理动作：`DROP DATABASE `work_order_e2e`（只作用于本轮自建的、名字带 `work_order_e2e` 前缀的库）
- 共享的 `work_order_test` 与业务库**未被写入**（只读了表结构 + `t_role`）
