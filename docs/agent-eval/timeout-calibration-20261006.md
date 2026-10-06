# 超时链校准采样（真供应商，2026-10-06）

> **真实供应商（deepseek-flash）+ 本机库**；样本 **6** 次（两种问题类型各 3 次），**样本小，不是分布结论**——只够把 §4.1 的「待测初值」改成「有数字依据的初值」。

| 项 | 值 |
| --- | --- |
| 专用库 | `work_order_e2e`（结构克隆自 `work_order_test`；跑完 DROP） |
| 模型 | `deepseek-flash` |
| 采样次数 | **6** 次调查 |
| 模型调用总数 | **17** 次（真实计费） |
| 4xx/5xx | **无** |

## 逐次结果

| # | 问题 | 终态 | problemType | 模型调用 | 每次调用耗时(ms) | 端到端(ms) | 触发翻页 | 触发对照 | 证据数 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Q1-1 | 到哪一步 | COMPLETED | ORDER_STATUS | 4 | [1759, 3391, 5804, 2260] | 13389 | 是 | 否 | 11 |
| Q1-2 | 到哪一步 | COMPLETED | ORDER_STATUS | 4 | [2652, 2048, 11079, 4474] | 20360 | 是 | 否 | 7 |
| Q1-3 | 到哪一步 | COMPLETED | REASSIGN_HISTORY | 3 | [2182, 1149, 26099] | 29597 | 是 | 否 | 7 |
| Q2-1 | 为什么没处理完 | COMPLETED | TIMEOUT_SITUATION | 2 | [1796, 6656] | 8534 | 否 | 否 | 11 |
| Q2-2 | 为什么没处理完 | COMPLETED | TIMEOUT_SITUATION | 2 | [1484, 2310] | 3853 | 否 | 否 | 11 |
| Q2-3 | 为什么没处理完 | COMPLETED | TIMEOUT_SITUATION | 2 | [2614, 3631] | 6355 | 是 | 否 | 14 |

## 分布（样本 6 次调查 / 17 次模型调用）

| 口径 | n | min | median | max |
| --- | --- | --- | --- | --- |
| **单次模型调用**耗时(ms) | 17 | 1149 | 2614 | 26099 |
| **端到端**耗时(ms) | 6 | 3853 | 10961 | 29597 |
| 每次调查的模型调用数 | 6 | 2 | 2 | 4 |

## D19 清理留痕（先按最宽口径统计再 DROP）

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

- 清理动作：`DROP DATABASE `work_order_e2e`；共享的 `work_order_test` 与业务库未被写入（只读结构 + `t_role`）
