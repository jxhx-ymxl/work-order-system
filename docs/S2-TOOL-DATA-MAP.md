# S2 第一个真实工具：数据映射核实（**只读轮，2026-10-06**）

> 目的：确定第一个真实工具**能查什么、查不到什么**。只读轮：未改业务代码、未改契约。
> 依据全部是"文件:行 + 原文"；结论若与 §3.1 的假设冲突，在"可否支撑"列直接写明。
>
> **⚠ 2026-10-06 更正（v2）**：v1 关于 `@OrderAction` 的结论**是错的**——注解以**全限定名**
> `@com.workorder.common.aop.OrderAction(...)` 写在 `WorkOrderServiceImpl`，共 **9 个应用点**
> （L195 ACCEPT / L226 START / L252 COMPLETE / L274 APPROVE / L296 REJECT / L341 RELEASE /
> L364 ASSIGN / L397 MANAGE / L423 CLOSE），且 `pom.xml:38` 有 `spring-boot-starter-aop`。
> v1 用**不带限定名**的 `@OrderAction` 检索 → 全部漏读，于是错判“切面是死代码、接单事件不可支撑”。
> 检索坑与处置已写进 `CLAUDE.md` §6 结构层。

## 1. 事实 → 来源映射

| 事实 | 来源（文件:行） | 空值语义 | 可否支撑 |
| --- | --- | --- | --- |
| `order.exists` | `t_work_order` 行是否存在（`sql/init.sql:10-32`）；读点 `WorkOrderServiceImpl:198` `selectById` | **记录不存在** = 工单不存在（`BizException(NOT_FOUND)`），与"字段为空"不同义 | ✅ |
| `order.status` | `t_work_order.status`（`sql/init.sql:17`，`NOT NULL DEFAULT 'PENDING'`）；读点 `WorkOrderServiceImpl:637` `String status = order.getStatus()` | 无 NULL | ✅ |
| `order.assignee` | `t_work_order.assignee_id`（`sql/init.sql:19` `DEFAULT NULL COMMENT '处理人ID(接单后赋值)'`）；可见性 `WorkOrderServiceImpl:651-658` | **NULL = 未分配**（结构性成立：`grabOrder` 仅在 `assignee_id IS NULL AND status='PENDING'` 时赋值，`WorkOrderMapper.java:24-27`） | ✅（只有**当前**处理人；显示名需联 `t_user` 且只能给脱敏名，§4.4） |
| `order.sla_deadline` | `t_work_order.sla_deadline`（`sql/init.sql:23` `DATETIME DEFAULT NULL`） | **NULL = 无 SLA 截止**；⚠ 还有一层历史歧义：I4/D10 记过"查不到 `t_sla_config` 就落 NULL、而 NULL 永不进 SLA 扫描"（现有兜底 + 启动自检降低但未证明消除） | ✅（NULL 必须当"无 SLA"显式呈现） |
| `order.alert_count` | **没有列**：`t_work_order` 无告警计数字段 | 唯一持久痕迹 = `t_notification` 行（`SlaEscalationScheduler:151-152` 发 `"工单 <orderNo> SLA 超时"`）+ Redis 去重键 `sla_notified:{orderId}`（`:63-64`，TTL 24h，会过期）；且该链路 `ref_id/event_id` 为 NULL（`sql/init.sql:160` 注释） | ❌ 精确"告警次数"；只能按 `title` 近似 `COUNT(*)` |
| `order.accept_events` | `t_work_order_log.action`（`sql/init.sql:42`），由切面写：`OrderLogAspect.java:39-44`（状态变化时 `saveLog(..., orderAction.action(), oldStatus, newStatus, remark)`）；`acceptOrder` 上的注解在 `WorkOrderServiceImpl.java:195`（`action = "ACCEPT"`） | 0 行 **不能**推出"从未接单"——见下条"语义边界" | ✅（**接单/指派/释放都各有 action**；"谁处理过"必须把 `ASSIGN` 一并算上，主管指派**不产生** `ACCEPT` 行） |

### 1.1 `accept_events` 的**语义边界**（不是"数据不可能"）

- 写入路径：`OrderLogAspect.java:28-45` 环绕 `@OrderAction` 标注的方法，**只在状态确实变化时**写一行
  （L39 `if (oldStatus != null && !oldStatus.equals(newStatus))`），`action` 取自注解（`orderAction.action()`，L43）。
  9 个应用点见文首更正；另有两处**显式** `saveLog`：`WorkOrderServiceImpl:172-173`（`SUBMIT`）、
  `OrderTriageConsumeService:209`（`TRIAGE`）。
- **边界（方法论提醒，保留）**：`action='ACCEPT'` 的 0 行**不能**推出"从未被处理"——
  ① 主管指派走 `action='ASSIGN'`（L364），不产生 `ACCEPT` 行；
  ② 失败/重复的抢单（`grabOrder` 返回 0 → `BizException`，`WorkOrderServiceImpl:205-208`）**不留行**（状态没变）；
  ③ 释放后再次被接单会有**第二行** `ACCEPT`（`version` 递增），所以"转过几手"要看**行序列**而不是当前 `assignee_id`。
- ⇒ **`REASSIGN_HISTORY` 可支撑**：读 `t_work_order_log` 里 `action ∈ {ACCEPT, ASSIGN, RELEASE, MANAGE}` 的行序列；
  但**不能**只用 `ACCEPT` 一个 action（会漏掉指派路径），也不能把"当前 `assignee_id` 为空"当成"从未被处理"。

## 2. 部门过滤（新工具必须复用的口径）

- 列表接口的部门过滤：`WorkOrderServiceImpl.applyRoleFilters:523-537` —— 取当前用户的 `dept_id`（`t_user.dept_id`），
  取**同部门用户 id 集合**，再 `in(WorkOrder::getSubmitterId, deptUserIds)`。
  ⇒ **部门 = 提交人所属部门**（`t_work_order` **没有部门列**）。
- 无部门时的降级：`:540-542` `if (!hasFilter) rbac.eq(getSubmitterId, currentUserId)`（静默退化为"只看自己"）。
- **升级单的特殊分支只存在于 `canViewDetail:641-643`**（`ESCALATED_ADMIN → roles.contains("DEPT_ADMIN")`），
  `applyRoleFilters` **没有**该分支。按 §1「升级状态不扩大范围」：新工具复用 `applyRoleFilters` 口径，**不得**复用 `canViewDetail` 的升级分支。

## 3. 三种"没有"的区分

| 形态 | 例子 | 语义 |
| --- | --- | --- |
| 列 NULL | `assignee_id` / `sla_deadline` / 老链路 `notification.ref_id` | 未分配 / 无 SLA / 未回填（**是值**） |
| 查到 0 行 | `t_work_order_log` 无 `ACCEPT` 行 | ⚠ 不等于"从未接单"（该行根本不会被写） |
| 记录不存在 | `t_work_order` 无该 id | 工单不存在（NOT_FOUND），与字段为空不同义 |

## 4. "想查但查不到"清单

| 想查 | 结论 | 依据 |
| --- | --- | --- |
| 维修过程 / 解决方案 / 处理说明 | **不采集** | `WorkOrderService:35/37/39`：`startOrder` / `completeOrder` / `approveOrder` 都**没有文本入参**；只有 `rejectOrder(..., String remark)`（`:41`）带备注 |
| 接单 / 转手历史 | **可查**（`t_work_order_log` 的 action 序列；见 §1.1 的三种边界） | 见 §1.1 |
| 告警次数（精确） | 只能按 `t_notification.title` 近似 | 见 §1 |
| "是否已提醒过" | 只在 Redis（TTL 24h），过期不可恢复 | `SlaEscalationScheduler:63-64,134` |
| 处理人姓名原值 | 表里有（`t_user`），但 §4.4 白名单**禁止外发** | `sql/init.sql:59+`；§4.4 白名单 |

## 5. 登记清单（与本轮无关的缺陷，**只登记不修**）

1. ~~**C2 裁决未落到代码**~~ —— **已落地**：`7a0d205`（C1/C2/C3 收口）已删掉 `ToolContext.canSeeAllDepts`，
   `callerDeptId` 成为唯一范围载体且构造期必须非空。**本轮只读核实时 HEAD = 7a0d205**，
   故本项不再是缺口（原先按 833b878 记的"字段仍在"已作废）。
2. **切面与 `@Transactional` 的先后顺序未定义**：`OrderLogAspect` 只声明 `@Around`（L28），
   **没有 `@Order`、也没有 `@Transactional`**；Spring 的事务拦截器默认最低优先级 ⇒ "先提交业务、再写日志"
   与"日志并入业务事务"两种语义都可能，取决于运行时顺序。**本轮只登记，不改代码**（首版工具不需要它确定）。
4. 老链路的 `t_notification.ref_id/event_id` 为 NULL（`sql/init.sql:160`）→ 按单号检索通知只能靠 `title`。
