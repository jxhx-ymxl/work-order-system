# 演示脚本（10 步，每步一句判据）

**与 `BUSINESS-SCOPE.md` §6.1 的关系**：那边是**业务动线原文**（5 分钟版，含每步的界面/后台观测点）；
本文件是它的**可执行版**——每步只留一句**判据**（能当场勾对/勾错的那种），并补上改造完成后的现状
（异步分诊、延迟释放、调度中心、日报/归档）。技术向的故障注入动线在**附录 A**。

---

## 0.1 两个注意事项（**先读，别踩**）

1. **`888` / `902` 是历史实证单，不是演示数据。**
   它们是"LLM 读超时 → 落账本 → 阶梯重投 → 自愈"的**两条独立实证**（D68；`888` 2 字符、`902` 17 字符长文本），
   演示时**不要拿它们当素材、更不要删**（`CLEANUP-BEFORE-DEMO.md` §2 已把这两张列入保留集）。
   演示要用的普通单，按下面第 2 步现场新建。
2. **"首次告警"要在现场改 `sla_deadline`。**
   整治后保留集的 `sla_deadline` 都被推到**未来**（`CLEANUP-BEFORE-DEMO.md` §4），
   所以现场**没有**天然逾期的单。要演出 SLA 告警（第 10 步），**必须当场改一张单的 `sla_deadline`**，
   并且**先确认它没有 `sla_notified:<id>` 幂等键**——否则扫描只会打"已发送过、跳过重复通知"。

## 0.2 演示前的**完整页面走查**（约 3 分钟，**别省**）

> **为什么这一步不能省**：本项目的探针、`handle_code`、业务日志**全都是绿的**，但**页面可能仍然是错的**——
> 最典型的就是**种子数据双编码**（D73）：角色名/权限名在库里存成了双编码字节，**后端逻辑完全不受影响**
> （权限判断照常），所以**没有任何自动化判据会报警**。**这次的双编码问题就是靠这一步走查抓到的。**

**走查清单**（每一步都点开看一眼，**凡乱码 / 空值 / 错位都记下来**——记下"哪一页 + 哪一列 + 截图"，别只记"有乱码"）：

| 顺序 | 页面 / 动作 | 这一眼看什么（判据） |
| --- | --- | --- |
| 1 | **登录页** | 登录框、按钮、提示文案**没有乱码**；登录后跳转正常 |
| 2 | **工单列表** | 表头、类型列、状态列**中文正常**；行数与库里一致（演示库应为保留集） |
| 3 | **提交工单** | 表单标签、下拉项（类型/优先级）**中文正常**；提交后**列表出现新单** |
| 4 | **"分类中"** | 新单的类型列显示**「分类中」**（不是兜底值、不是空白） |
| 5 | **写回后刷新** | 类型变**具体中文值**（如「网络故障」）、SLA 截止**收缩** |
| 6 | **详情页时间线** | 时间线的动作名 / 操作人 / 备注**中文正常**（这里最容易看出乱码） |
| 7 | **用户管理** | **角色名、用户名**显示正常（**D73 的双编码就是在这类页面上暴露的**） |
| 8 | **角色弹窗 / 权限勾选** | **权限名称**中文正常；勾选状态与库里一致（不是空白） |
| 9 | **SLA 配置页** | 类型列（`NETWORK/UTILITY/DORM/OTHER`）、分钟数正常；**8 条配置齐全** |
| 10 | **通知 / 站内信** | 标题与正文中文正常；未读数是数字（不是 NaN/空） |

**若第 7/8 步出现乱码**：按 `docs/DECISIONS.md` **D73** 排查——先在**带 `--default-character-set=utf8mb4` 的客户端**下看
（**仍乱码 = 数据坏**，不是显示问题），并查 `SHOW VARIABLES LIKE 'character_set_client'`（期望 `utf8mb4`；
显示 `latin1` 说明客户端默认字符集被 locale 带偏，`C.UTF-8` 这类 locale 才会给出 utf8 默认）。
**数据坏了要改数据，首选"定向 `UPDATE`"**：按 `role_code` / `perm_code` 定位、修复值取 `sql/init.sql` 的种子原文——
**实测 4 条角色 + 15 条权限一次清零**（可重跑脚本：`sql/hotfix-seed-encoding-repair.sql`）。
只有**污染范围未知或很大**时才考虑重建/重导。⚠ 改参数救不回来，`ALTER TABLE … CONVERT` 也无效（那是**字节语义**错误）。

## 前置（一次，约 1 分钟）

```bash
# ① 就绪（主判据 = 真实请求，见 deploy/DEPLOY-RUNBOOK.md §4）
curl -s -o /dev/null -w 'login HTTP %{http_code}\n' -X POST http://127.0.0.1:9000/api/login \
  -H 'Content-Type: application/json' -d '{"username":"admin","password":"admin123"}'      # 期望 200
# ② 任务都启动着（5 个自有任务 trigger_status=1）
# ③ 单号计数器对齐（**只在当天第一次建单之前**；当天还没有单时不用动，见 runbook §5⑥）
```

---

## 10 步

| # | 操作（谁/在哪） | **判据（一句话）** | 后台/旁证 |
| --- | --- | --- | --- |
| 1 | **登录**（提交人） | 登录返回 **200**，首页加载出列表 | `POST /api/login` 的 `code=200`；token 写入 Redis |
| 2 | **提交工单**（提交人，**只填标题 + 内容，不选类型**） | **百毫秒级返回**（实测 **P50 149.4ms / P99 264.4ms**——三行同参数、正向/反向各一遍，取数 **2026-09-25**，出处 **README §9.1**；设计目标 <100ms 是初值、不是现状；**不出现"转圈 5 秒"**）；列表出现新单、类型显示**「分类中」** | `t_work_order` 先以兜底值落库、`triage_status='PENDING'`；**响应不等 LLM**（这是"同步→异步"改造的核心现象） |
| 3 | **数秒后刷新/重开详情**（提交人） | 类型变成**具体值**（如 网络故障/紧急）、不再显示"分类中"，且 **SLA 截止时间比第 2 步更早**（**必须前后对比**：先记下第 2 步按兜底值算出的截止时间） | `triage_status='DONE'`、`type/priority` 被写回、`sla_deadline` 按 H4（`created_at` + 新 `finish_minutes`）**重算并收缩**；`t_work_order_log` 有分类修正记录。<br>**变体 A（信息不足 → 保守）**：把标题也写得很短（例："空调"）→ 判据：类型落 **`OTHER`/普通**、理由写明"依据不足"，**不瞎猜**。<br>**变体 B（手填不被覆盖）**：这次**手动选**类型与优先级提交 → 判据：分诊结果写回后**手填值不被覆盖**（用户填的字段一律优先） |
| 4 | **处理人登录 → 打开站内信** | 收到**「新工单待抢单：WO-…」**，未读 +1 | `t_notification` 该行 **`ref_type='ORDER'` / `ref_id` / `event_id` 三者非空**（与老链路不同）；来源是消费端 `ORDER_SUBMITTED` 事件 |
| 5 | **抢单**（处理人） | 状态从**待分配 → 已接单**，处理人显示为自己 | `assignee_id` 写入、`status='ACCEPTED'`、`version+1`；`t_work_order_log` 新增 `ACCEPT` |
| 6 | **开始处理**（处理人） | 状态变**处理中** | `status='IN_PROGRESS'`；日志 `START`；该单**不再**被超时释放 |
| 7 | **提交验收**（处理人） | 状态变**待验收**，提交人侧出现"验收通过/驳回" | `status='AWAIT_APPROVAL'`；日志 `COMPLETE` |
| 8 | **驳回**（提交人，填理由） | 状态回到**处理中**，时间线出现驳回理由 | `reject_count` +1；日志 `REJECT` 且 `remark` 非空。重复点同一次提交 → 提示**幂等**（"请勿重复提交或 Token 已过期"） |
| 9 | **再走两轮直到第 3 次驳回**（提交人 + 处理人） | 状态变**已升级**，普通操作按钮消失；**超管收到"驳回次数已达上限"** | `status='ESCALATED_ADMIN'`、`reject_count=3`；`t_notification` 新增 SYS_ADMIN 一行 |
| 10 | **SLA 告警**（超管：现场改 `sla_deadline` → 等扫描） | **超管收到「工单 WO-… SLA 超时」**，且该单**状态不变**（只告警不改状态） | 现场执行（见下方 SQL）；判据还包括 Redis 出现 `sla_notified:<id>` |

### 第 10 步的现场操作（唯一需要改数据的一步）

```bash
mysqlq() { docker compose exec -T mysql sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 "$@"' _ "$@"; }

# ① 选一张演示单（**别选 888/902**），看它有没有被通知过
mysqlq -N -B -e "SELECT id, status, sla_deadline FROM t_work_order WHERE id=<演示单id>;"
docker compose exec -T redis redis-cli EXISTS sla_notified:<演示单id>      # 期望 0（0 才会真的发告警）

# ② 现场把它改成"已逾期"（改的是数据，不是配置）
mysqlq -e "UPDATE t_work_order SET sla_deadline = NOW() - INTERVAL 5 MINUTE WHERE id=<演示单id>;"

# ③ 触发一次 SLA 扫描（调度中心手动"执行一次"，或等本地兜底 300s 一轮）
#    判据：超管站内信出现"工单 WO-… SLA 超时"；工单 status 不变；Redis 出现 sla_notified:<id>
```

> **收尾**：第 10 步把某张单改成逾期了——演示结束后**要么把它改回未来**，要么在讲的时候明说"这张是现场造的"。
> **别动** `order:seq:*`（单号计数器），也**别删** `888/902`。

---

## 调查助手（只读调查 agent）· **API 版**演示（**前端未接**）

> ⚠ **开关默认关**：`agent.investigation.enabled=false`、模式默认 `fixed`。**演示前必须先开**——
> 开关关着时该路径**表现 404**（不注册空壳），不是"返回 501 的占位"。
> 演示账号必须是**部门主管（`DEPT_ADMIN`）**（其它角色会被受理层拒为 `FORBIDDEN`）。

**前置**（后端进程环境变量；**key 只放环境或仓库外文件，别写进任何受版本控制的文件**）：

```bash
AGENT_INVESTIGATION_ENABLED=true
AGENT_INVESTIGATION_MODE=agent        # 想演示固定流程基线就设 fixed（默认）
LLM_API_URL=<真供应商> LLM_API_KEY=<env> LLM_MODEL=deepseek-flash
```

**调用**（先用**部门主管**账号登录拿 token；`orderNo` 是**结构化入参**，`question` 只用于分类）：

```bash
TOKEN=$(curl -s -XPOST localhost:9000/api/login -H 'Content-Type: application/json' \
        -d '{"username":"<部门主管账号>","password":"<口令>"}' | jq -r '.data.token')

curl -s -XPOST localhost:9000/api/agent/investigations \
  -H "Content-Type: application/json" -H "satoken: $TOKEN" \
  -d '{"orderNo":"WO-20261007-00001","question":"这张单现在到哪一步了？"}'
```

**预期返回**（沿用项目 `Result<T>` 约定，把 `Outcome` 的 `status` / `failureCode` / `report` / `renderedText` 如实映射）：

```json
{"code":200,"data":{"status":"COMPLETED","failureCode":null,
  "report":{"problemType":"ORDER_STATUS","evidenceIds":["E1","E2","E3"],"suggestionIds":["CONTACT_ASSIGNEE"]},
  "renderedText":"【已核实事实】…\n【证据缺口】…\n【下一步核实建议】…"}}
```

| # | 判据（一句话） |
| --- | --- |
| 1 | `data.status = COMPLETED`，且 `data.report` **只含编号**（`problemType`/`evidenceIds`/`suggestionIds`，**无自由文本**） |
| 2 | `data.renderedText` **三段齐全**：`【已核实事实】` / `【证据缺口】` / `【下一步核实建议】` |
| 3 | **越权**：换**非 `DEPT_ADMIN`** 账号、或对**跨部门**单调用 → `status=FAILED` + `failureCode=FORBIDDEN`，且**无** `report`/`renderedText` |
| 4 | **失败不伪装**：`status != COMPLETED` 时 `report` 必为 `null`（只有 `INCOMPLETE` 才给 `renderedText`，且顶部标"未完成"） |
| 5 | **开关关**（默认）→ 该路径 **404**；开着才通 |

> ⚠ **这不是生产数字**：上面演示的是**功能**；耗时/资源见 `docs/agent-eval/` 的 S5/S6 记录
> （本机 + 桩 / 本机 + 真模型，**都不是生产**）。**"agent 优于固定流程"未经证实**（单次、24 例）。

---

## 附录 A · 技术动线（面试官追问"可靠性怎么证明"时用）

来源：`BUSINESS-SCOPE.md` §6.2；**这里每条都标出已经在仓库里的实测凭证**，可以说"我们跑过"，而不是"设计上应该"。

| 序 | 故障注入 | 判据 | 凭证 |
| --- | --- | --- | --- |
| ① | 停机接单（`outbox` 有 `PENDING`）→ `kill -9` → 重启 | 重启后消息仍被投出：`PENDING → SENT`，重试计数不增；工单最终被释放 | `ASYNC-SCHEDULING-PLAN.md` §P1（服务器三验证） |
| ② | 手工重投同一条消息 | 只产生一条站内信、一条消费记录 | `t_consume_record` 的 `UNIQUE(event_id, consumer)`；D69（并发收尾实测） |
| ③ | **停 broker** 后**提交一张单**并等 | **提交仍成功**（`t_event_outbox` 留 `PENDING` 行，不报错）；随后工单**仍被兜底扫描释放**（`触发来源=local`） | `ASYNC-SCHEDULING-PLAN.md` §P1（"停 MQ 仍能释放"的服务器三验证）；D72 附录 ⑥ |
| ④ | **停 admin**（`docker compose stop xxl-job-admin`） | 后端日志**只有 `触发来源=local`、没有 `触发来源=xxl`**，且本地节拍**不飘** | D72 附录 ⑥：四节拍、三次间隔**精确 60.000s** |
| ⑤ | **手动跑归档/日报**（控制台"执行一次"） | `t_archive_log` 出现留痕（含 03:30/03:45 的 CRON 落点）；`t_daily_report` 按日一行且**不含今天** | D70 / D71（分片一致性、预算用尽、自愈） |
| ⑥ | **双跑幂等**（讲，不要求现场复现） | 能说清 **D69 的行锁叠窗实验**：`local` 与 `xxl` 同窗口抢同一张单 → 一张 `RELEASED`、一张"状态守卫未命中"跳过；**B 段"自然时序下没撞上守卫"也要照说** | D69（含 `innodb_trx` 的 `LOCK WAIT` 快照原文） |
| ⑦ | **调度中心三项**（控制台 + `xxl_job_log`） | ① 执行器**在线**（`xxl_job_registry` 行 + `update_time` 30s 前进）；② 手动触发后 `xxl_job_log` 的 **`handle_code=200`**；③ **`handle_msg` 带业务摘要**（不是只有"执行成功"绿灯） | `deploy/UPGRADE-P2.md` §9.2；本轮服务器原文见 `ASYNC-SCHEDULING-PLAN.md` §P7 |

## 附录 B · 演示前的三项现场检查（各 10 秒）

```bash
mysqlq -N -B -e "SELECT COUNT(*) AS today_orders FROM t_work_order
                  WHERE order_no LIKE CONCAT('WO-', DATE_FORMAT(NOW(),'%Y%m%d'), '-%');"   # 与 order:seq 对齐
docker compose exec -T redis redis-cli EXISTS order:seq:$(date +%Y%m%d)                    # 见 runbook §5⑥
mysqlq -N -B -e "SELECT id, job_desc, trigger_status FROM xxl_job.xxl_job_info;"          # 5 自有任务 = 1
```

---

## 附录 C · 覆盖度核对（10 条演示要点 → 本脚本位置）

**覆盖度已核对**：下面 10 条要点在脚本里**都有对应步骤**（缺的三条已补、其中一条的判据按实测数字校准）：

| # | 演示要点 | 落在本脚本 |
| --- | --- | --- |
| ✅ | ① 提交即返回（百毫秒级）+ 页面"分类中" | **第 2 步**（判据按实测校准为"百毫秒级 / **P50 149.4ms、P99 264.4ms**"，出处 **README §9.1**，取数 2026-09-25；不再是"100ms 内"的硬说法） |
| ✅ | ② 写回具体类型 + SLA 收缩（**前后对比**） | **第 3 步**（明确要求先记第 2 步的兜底截止时间） |
| ✅ | ③ 信息不足 → `OTHER`/普通 保守 | **第 3 步 · 变体 A**（上一轮补入） |
| ✅ | ④ 手填 type/priority 不被覆盖 | **第 3 步 · 变体 B**（上一轮补入） |
| ✅ | ⑤ **首次** SLA 告警（现场改 `sla_deadline`；前置查 `sla_notified:<id>` 为 0） | **第 10 步**（含现场 SQL 与前置检查） |
| ✅ | ⑥ 调度中心：执行器在线 + `handle_code=200` + 业务摘要 | **附录 A · ⑦**（上一轮补入） |
| ✅ | ⑦ 停 admin → 日志**有 local、无 xxl**（60s 节拍不飘） | **附录 A · ④**（判据已扩为"只有 local、没有 xxl + 节拍不飘"） |
| ✅ | ⑧ 归档/日报**手动跑** → `t_archive_log` / `t_daily_report` | **附录 A · ⑤**（已写明"控制台执行一次"并给出两张表的判据） |
| ✅ | ⑨ 双跑幂等（能讲清行锁叠窗实验即可） | **附录 A · ⑥**（上一轮补入；含"B 段也要照说"） |
| ✅ | ⑩ 停 broker → 提交仍成功、释放由兜底接住 | **附录 A · ③**（判据已扩为"提交仍成功 + outbox 留 PENDING + 兜底释放"） |

> **两条注意事项仍在开头**（§0）：`888/902` 是历史实证单不是演示数据；第 10 步的首次告警要**现场改 `sla_deadline`**。

**关联**：`BUSINESS-SCOPE.md` §6.1/§6.2（动线原文）、`deploy/DEPLOY-RUNBOOK.md`（就绪门/冒烟/巡检/计数器对齐）、
`deploy/CLEANUP-BEFORE-DEMO.md`（保留集与逾期整治口径）、`docs/DECISIONS.md` D68/D70/D71/D72（实测凭证）。
