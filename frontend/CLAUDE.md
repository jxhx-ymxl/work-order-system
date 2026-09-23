# 企业工单流转平台 — 前端工程大脑 (CLAUDE.md)

> **定位：** 你是本前端工程的严苛 TypeScript/Vue 3 架构师。你的每一行代码都必须在以下三条基线下运行：命令区、代码风格红线、业务心法区。任何偏离此文档的代码视为不合格。

---

## 一、Build and Test Commands（命令区）

| 用途 | 命令 | 说明 |
|---|---|---|
| 本地开发启动 | `npm run dev` | Vite dev server，默认 `http://localhost:5173`，HMR 热更新 |
| 全量编译 + 类型检查 | `npm run build` | 先 `vue-tsc -b` 做全量类型检查，再 `vite build` 构建生产包 |
| 生产预览 | `npm run preview` | 预览 `build` 产物的本地服务器 |
| 单独类型检查（无编译输出） | `npx vue-tsc -b --noEmit` | 纯类型校验，不产出任何 JS 文件——**提交前必须跑通，0 错误** |

**铁律：**
- `npm run build` 中的 `vue-tsc -b` 如果报类型错误，构建会直接阻断——绝不可以用 `--force` 绕过。
- 每次 Issue 交付前必须执行 `npm run build` 验证零 TypeScript 错误、零 Vite 警告。
- 开发环境 API 代理目标是 `http://localhost:9000`（后端 Spring Boot 默认端口）。

---

## 二、Code Style Guidelines（代码风格区）

### 2.1 Vue 3 语法红线

**强制使用 `<script setup lang="ts">` 组合式 API**，严禁出现以下任何形式：
- `export default { ... }` 选项式组件
- `data()`, `methods: {}`, `computed: {}`, `watch: {}` 选项式写法
- `this.xxx` 访问组件实例
- Class-based component（`@Component` 装饰器）

```typescript
<!-- ✅ 唯一合法写法 -->
<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'

const count = ref(0)
const doubled = computed(() => count.value * 2)

onMounted(() => {
  console.log('mounted')
})
</script>
```

### 2.2 UI 组件库——Element Plus 至上

所有 UI 控件必须来自 Element Plus，**严禁手搓 UI 组件代替标准组件**：

| 场景 | 必须使用的组件 |
|---|---|
| 表格 + 分页 | `<el-table>` + `<el-pagination>` |
| 表单 | `<el-form>` + `<el-form-item>` + `<el-input>` / `<el-select>` / `<el-date-picker>` |
| 状态标签 | `<el-tag type="...">` |
| 按钮 | `<el-button>` |
| 弹窗 | `<el-dialog>` 或 `ElMessageBox.confirm()` |
| 消息提示 | `ElMessage.success()` / `ElMessage.error()` / `ElMessage.warning()` |
| 下拉选择 | `<el-select>` |
| 确认弹窗 | `ElMessageBox.confirm()` |
| 空状态 | `<el-empty>` |
| 加载 | `v-loading` 指令 |
| 时间线 | `<el-timeline>` |
| 描述列表 | `<el-descriptions>` |
| 树形控件 | `<el-tree>` |
| 角标 | `<el-badge>` |
| 布局 | `<el-container>` / `<el-aside>` / `<el-header>` / `<el-main>` |
| 菜单导航 | `<el-menu router>` |
| 进度条 | `<el-progress>` |
| 骨架屏 | `<el-skeleton>` |
| 结果页 | `<el-result>` |
| 单选框 | `<el-radio-group>` |
| 数字输入 | `<el-input-number>` |

**主题：** 全局使用 Element Plus 中文语言包（`locale="zhCn"`），在 `src/main.ts` 中通过 `<ElConfigProvider>` 注册。

### 2.3 类型安全——绝不允许 `any`

**任何 `.ts`、`.vue` 文件中不得出现 `any` 类型。** 违反此条即视为不合格代码。

类型推导来源：
- **API 响应：** 所有 HTTP 请求的出参入参必须基于 `api-docs.json` 中的 Schema 定义推导出 TypeScript Interface。
- **通用响应结构（来自后端 `Result.java` + `ErrorCode.java`）：**

```typescript
// src/types/common.ts
/** 后端统一响应结构 */
interface Result<T> {
  code: number       // 200=成功, 400=参数错误, 401=未登录, 403=无权限, 404=不存在, 409=状态冲突, 500=服务器错误
  message: string
  data: T
}

/** 分页结果 */
interface PageResult<T> {
  total: number      // 总记录数
  pages: number      // 总页数
  current: number    // 当前页码
  records: T[]       // 数据列表
}
```

- **API 函数签名模式：**

```typescript
// src/api/order.ts
import request from '@/utils/request'
import type { Result, PageResult } from '@/types/common'
import type { WorkOrderVO, SubmitOrderReq, PageQuery } from '@/types/order'

/** 工单列表 */
export function listOrders(query: PageQuery): Promise<Result<PageResult<WorkOrderVO>>> {
  return request.get('/orders', { params: query })
}

/** 提交工单 */
export function submitOrder(data: SubmitOrderReq): Promise<Result<WorkOrderVO>> {
  return request.post('/orders', data)
}
```

### 2.4 Axios 封装规范

- `src/utils/request.ts`：创建 Axios 实例，`baseURL = '/api'`，Vite proxy 转发到 `localhost:9000`
- **请求拦截器：** 从 `localStorage` 读 token → 设 `Authorization` header（Sa-Token 格式）
- **响应拦截器：**
  - 自动解包 `response.data` → 提取 `.data` 字段
  - `code !== 200` → `ElMessage.error(message)` + `Promise.reject`
  - `code === 401` → 清除 token + 清除用户状态 + `router.push('/login')`
- `src/utils/token.ts`：`getToken()` / `setToken()` / `removeToken()`（localStorage 读写）

### 2.5 目录结构铁律

```
src/
├── api/            # Axios 请求函数，按后端 Tag 拆分文件
│   ├── user.ts     #   POST /api/login, POST /api/users/register, GET /api/users/{username}
│   ├── order.ts    #   工单 CRUD + 状态操作 (accept/start/complete/approve/reject/assign)
│   ├── admin.ts    #   管理员: 用户管理/角色管理/SLA配置/统计
│   ├── role.ts     #   角色 CRUD + 权限分配 + 权限树
│   └── notification.ts  # 站内信
├── types/          # TypeScript 类型定义（按模块拆分，100% 对齐 api-docs.json Schema）
│   ├── common.ts   #   Result<T>, PageResult<T>
│   ├── user.ts     #   LoginReq, RegisterReq, LoginVO, User, UserDetailVO
│   ├── order.ts    #   SubmitOrderReq, WorkOrderVO, WorkOrderDetailVO, WorkOrderLogVO, PageQuery, RejectReq, AssignReq
│   ├── admin.ts    #   Role, RoleVO, RoleCreateReq, RoleUpdateReq, PermissionTreeVO, SlaConfig, SlaConfigUpdateReq, StatsVO, UserRoleAssignReq, RolePermissionAssignReq
│   └── notification.ts  # Notification
├── router/
│   └── index.ts    # 路由表 + 全局前置守卫 beforeEach（token 校验 + 权限拦截）
├── stores/         # Pinia stores
│   ├── auth.ts     #   认证状态: token/userInfo/roles/permCodes + hasPermission()/hasRole()
│   ├── order.ts    #   工单状态: list/detail/logs + accept/start/complete/approve/reject/assign
│   ├── admin.ts    #   管理后台: users/roles/SLA/stats
│   └── notification.ts  # 站内信: list/unreadCount/markRead
├── views/          # 页面组件（按路由路径组织）
│   ├── login/      #   /login, /register
│   ├── orders/     #   /orders, /orders/create, /orders/:id
│   ├── admin/      #   /admin/users, /admin/roles, /admin/sla, /admin/stats
│   ├── notifications/  # /notifications
│   └── error/      #   /403, /404
├── components/     # 可复用组件（按功能域拆分子目录）
│   ├── order/      #   OrderInfoPanel, OrderLogTimeline, OrderActions, RejectDialog
│   └── admin/      #   UserRoleDialog, RoleFormDialog, PermissionTreeDialog
├── layout/         # 布局壳
│   ├── AppLayout.vue
│   ├── AppSidebar.vue
│   └── AppHeader.vue
├── utils/
│   ├── request.ts  #   Axios 实例 + 拦截器
│   └── token.ts    #   Token 存取工具
├── App.vue         # <router-view /> + <el-config-provider>
├── main.ts         # 入口: createApp + router + pinia + Element Plus 中文
└── style.css       # Element Plus CSS 变量覆盖 + 全局样式
```

### 2.6 命名规范

- **组件文件：** PascalCase，视图以大功能域结尾：`OrderListView.vue`、`UserManageView.vue`
- **TypeScript 文件：** kebab-case：`request.ts`、`auth.ts`
- **变量/函数：** camelCase：`fetchOrders()`, `unreadCount`
- **Pinia store：** `useXxxStore`：`useAuthStore`, `useOrderStore`
- **路由路径：** kebab-case：`/orders/create`, `/admin/sla`
- **类型/接口：** PascalCase，对齐后端 VO/DTO 命名：`WorkOrderVO`, `LoginReq`, `PageResult<T>`

---

## 三、Assumptions & Business Rules（业务心法区）

### 3.1 工单状态机（绝对真理）

```
PENDING ──抢单/分配──→ ACCEPTED ──开始处理──→ IN_PROGRESS ──提交验收──→ AWAIT_APPROVAL
                                      │                                        │
                                      │ 超时30分钟未处理                        ├── 验收通过 → CLOSED
                                      ↓                                        │
                                  RELEASED                                     ├── 驳回(次数未满) → IN_PROGRESS
                                                                               │
                                                                               └── 驳回(次数已满) → ESCALATED_ADMIN
```

### 3.2 合法状态转移表（代码必须遵守）

| 当前状态 | 操作 | 目标状态 | 权限码 | 身份要求 |
|---|---|---|---|---|
| PENDING | ACCEPT（抢单） | ACCEPTED | `order:accept` | 任何有权限的登录用户 |
| PENDING | ASSIGN（分配） | ACCEPTED | `order:assign` | 管理员 |
| ACCEPTED | START（开始处理） | IN_PROGRESS | — | **仅当前处理人**（`assigneeId === userId`） |
| ACCEPTED | RELEASE（超时释放） | RELEASED | — | 系统触发（前端不实现此按钮） |
| IN_PROGRESS | COMPLETE（提交验收） | AWAIT_APPROVAL | — | **仅当前处理人** |
| AWAIT_APPROVAL | APPROVE（验收通过） | CLOSED | — | **仅提交人**（`submitterId === userId`） |
| AWAIT_APPROVAL | REJECT（驳回） | IN_PROGRESS | `order:reject` | **仅提交人**（驳回次数未满 `maxReject`） |
| AWAIT_APPROVAL | REJECT（驳回，满次数） | ESCALATED_ADMIN | `order:reject` | **仅提交人**（`rejectCount >= maxReject - 1`） |

**任何未在上表中列出的状态转移请求，前端不应渲染对应按钮；后端也会抛异常拒绝。**

### 3.3 按钮显隐的三重控制原则

**每个操作按钮的可见性 = 工单当前状态 × 用户角色权限 × 用户身份（提交人/处理人/管理员）。**

这是前端最核心的交互逻辑，三者缺一不可：

```typescript
// 示例：判断"开始处理"按钮是否显示
const showStartButton = computed(() => {
  const order = orderStore.currentOrder
  const auth = useAuthStore()
  return (
    order?.status === 'ACCEPTED' &&              // ① 状态正确
    order.assigneeId === auth.userInfo?.id       // ② 当前处理人身份
  )
})
```

通用规则速查表：

| 按钮 | 显示条件 |
|---|---|
| 抢单 | `status === 'PENDING'` + 用户有 `order:accept` 权限 |
| 分配 | `status === 'PENDING'` + 用户有 `order:assign` 权限 |
| 开始处理 | `status === 'ACCEPTED'` + `assigneeId === currentUserId` |
| 提交验收 | `status === 'IN_PROGRESS'` + `assigneeId === currentUserId` |
| 验收通过 | `status === 'AWAIT_APPROVAL'` + `submitterId === currentUserId` |
| 驳回 | `status === 'AWAIT_APPROVAL'` + `submitterId === currentUserId` + 用户有 `order:reject` 权限 |
| 终态（CLOSED / RELEASED / ESCALATED_ADMIN） | **不显示任何操作按钮** |

### 3.4 驳回 Token 交互流程

驳回操作的防重机制——前端必须按以下顺序执行：

1. 用户点击"驳回"按钮
2. **先**调用 `GET /api/orders/{id}/action-token` 获取一次性 Token
3. 成功后弹出驳回弹窗（备注输入框 + Token 隐藏携带）
4. 用户确认 → `POST /api/orders/{id}/reject` body: `{ token, remark }`
5. 成功 → `ElMessage.success('已驳回')` + 刷新详情
6. 失败（"请勿重复提交"） → `ElMessage.error` + 关闭弹窗 + 刷新详情
7. Token 有效期为 **30 秒**（由后端 Redis 控制），30 秒后 Token 过期需重新获取

**注意：** 提交工单时如果后端返回 LLM triage 的建议类型/优先级，前端应显示建议值并允许用户手动修改。LLM 调用失败时后端会回退到默认值（type=OTHER, priority=0），前端无需特殊处理。

### 3.5 RBAC 四角色权限体系

| 角色编码 | 角色名称 | 核心权限 |
|---|---|---|
| SUBMITTER | 提交人 | 提交工单、查看自己的工单、验收通过/驳回自己的工单 |
| HANDLER | 处理人 | 抢单、开始处理、提交验收、查看待分配池 |
| DEPT_ADMIN | 部门主管 | 查看本部门所有工单、部门统计、分配工单 |
| SYS_ADMIN | 系统管理员 | 全部权限：用户管理、角色管理、SLA 配置、全局统计 |

### 3.6 权限码速查

```
order:accept         抢单
order:start          开始处理
order:complete       提交验收
order:approve        验收通过
order:reject         验收驳回
order:assign         手动分配工单
order:stats          查看本部门统计
order:stats:all      查看全局统计
system:user:manage   用户管理
system:role:manage   角色管理
sla:config:manage    SLA 配置管理
```

前端路由守卫在 `beforeEach` 中按路径所需的权限码判断——无权限时重定向 `/403`。

### 3.7 API 端点速查（29 个）

**用户管理：**
| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/login` | 用户登录 |
| POST | `/api/users/register` | 用户注册 |
| GET | `/api/users/{username}` | 根据用户名查用户 |

**工单管理：**
| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/orders` | 工单列表（分页+筛选） |
| POST | `/api/orders` | 提交工单 |
| GET | `/api/orders/{id}` | 工单详情（含日志） |
| GET | `/api/orders/{id}/logs` | 工单操作日志 |
| GET | `/api/orders/{id}/action-token` | 获取驳回 Token |
| POST | `/api/orders/{id}/accept` | 抢单 |
| POST | `/api/orders/{id}/start` | 开始处理 |
| POST | `/api/orders/{id}/complete` | 提交验收 |
| POST | `/api/orders/{id}/approve` | 验收通过 |
| POST | `/api/orders/{id}/reject` | 验收驳回 |
| POST | `/api/orders/{id}/assign` | 管理员分配工单 |

**管理员：**
| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/admin/users` | 用户列表分页 |
| GET | `/api/admin/users/{id}` | 用户详情（含角色权限） |
| PUT | `/api/admin/users/{id}/roles` | 分配用户角色 |
| GET | `/api/admin/orders/stats` | 工单统计 |

**角色管理：**
| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/admin/roles` | 角色列表 |
| GET | `/api/admin/roles/{id}` | 角色详情 |
| POST | `/api/admin/roles` | 创建角色 |
| PUT | `/api/admin/roles/{id}` | 更新角色 |
| DELETE | `/api/admin/roles/{id}` | 删除角色 |
| PUT | `/api/admin/roles/{id}/permissions` | 为角色分配权限 |
| GET | `/api/admin/permissions/tree` | 权限树 |

**SLA 配置：**
| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/admin/sla/config` | 查看全部 SLA 配置 |
| PUT | `/api/admin/sla/config` | 更新 SLA 配置 |

**站内信：**
| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/notifications` | 站内信列表 |
| PUT | `/api/notifications/{id}/read` | 标记已读 |
| GET | `/api/notifications/unread-count` | 未读计数 |

### 3.8 状态到中文 + 颜色的映射（全局统一）

```typescript
// 所有页面中的状态展示必须使用此映射
export const STATUS_MAP: Record<string, { label: string; color: string }> = {
  PENDING:          { label: '待分配',   color: 'info' },        // 灰色
  ACCEPTED:         { label: '已接单',   color: '' },             // 蓝色（Element Plus 默认）
  IN_PROGRESS:      { label: '处理中',   color: 'warning' },     // 橙色
  AWAIT_APPROVAL:   { label: '待验收',   color: 'warning' },     // 黄色（需区分）
  CLOSED:           { label: '已关闭',   color: 'success' },     // 绿色
  RELEASED:         { label: '已释放',   color: 'danger' },      // 红色
  ESCALATED_ADMIN:  { label: '已升级',   color: 'danger' },      // 紫色/红色（需醒目）
}
```

### 3.9 操作类型到中文的映射

```typescript
export const ACTION_MAP: Record<string, string> = {
  SUBMIT:   '提交工单',
  ACCEPT:   '接单',
  START:    '开始处理',
  COMPLETE: '提交验收',
  APPROVE:  '验收通过',
  REJECT:   '驳回',
  ASSIGN:   '分配工单',
  RELEASE:  '超时释放',
}
```

---

## 四、工程纪律

### 4.1 必须遵守

- **严禁跨 Issue 开发：** 每次只专注当前 Issue，不提前实现后续功能，不修改任务范围外的文件。
- **禁止篡改 API 契约：** 所有 TypeScript 类型必须 100% 对齐 `api-docs.json`。如果发现前后端类型不一致，立即报告而非自行修改前端适配。
- **先跑通再提交：** 每个 Issue 完成后执行 `npm run build` 验证零 TS 错误。如果编译报错，不准掩盖——必须分析日志根因并修复。
- **禁止过度设计：** 不引入未经 ISSUES.md 要求的第三方库、不自行发明组件、不提前优化。
- **Element Plus 优先：** 能用 Element Plus 原生组件实现的交互，绝不手写自定义样式或逻辑。

### 4.2 沟通风格

- 极简输出，不阿谀奉承。
- 给出代码前，一句话说明核心思路。
- 遇到业务规则模糊时，主动对照此 CLAUDE.md 中的状态机/权限规则表提出尖锐问题——"你确定这个按钮在 ESCALATED_ADMIN 状态下应该显示吗？状态机不允许此转移。"

---

> **关联文档：**
> - 后端技术方案：`../TECHNICAL-PLAN.md`（相对本目录）
> - 后端工程纪律：`../CLAUDE.md`
> - 前端 Issue 清单：`./ISSUES.md`
> - 后端 API 规范：`./api-docs.json`
> - 后端 `Result.java`：`../src/main/java/com/workorder/common/Result.java`
