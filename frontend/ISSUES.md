【状态：活跃任务清单 · 未废弃】本文件是前端工程的任务来源（17 个 Issue，已勾选 16 个，仅 Issue #17「全局集成回归」未完成）。
与仓库根目录的 `ISSUES.md`（已废弃、仅作历史归档）状态不同，两份文件不冲突：根目录那份对应后端改造前的工作流，本文件对应前端切片的交付进度。

# 企业工单流转平台 — 前端工程待办清单

> **拆解方法论：** to-issues — 垂直切片（Tracer Bullet），每个 Issue 穿透全部层次（组件→Store→API→路由），独立可演示。
> **拆解范围：** 第 1 天 ~ 第 10 天（共 17 个 Issue）。
> **关联后端：** API 基座 `http://localhost:9000`，29 个端点，Sa-Token 鉴权。
> **里程碑 1（Day 1-2, Issue #1~#4）：** 工程基石 + 登录注册
> **里程碑 2（Day 3-5, Issue #5~#10）：** 应用布局 + 工单核心全流程
> **里程碑 3（Day 6-8, Issue #11~#14）：** 管理后台
> **里程碑 4（Day 9-10, Issue #15~#17）：** 站内信 + 全局集成

---

## 里程碑 1：工程基石 + 登录注册

### - [x] Issue #1: 前端工程化完善 — Vite 配置 + Element Plus 自动导入 + 目录结构 + CSS 变量

**What to build**

搭建前端项目的工程化地基：配置 Element Plus 组件/图标的按需自动导入（unplugin-vue-components + unplugin-auto-import）、建立标准目录结构（views/stores/api/router/utils/types）、定义 Element Plus 主题 CSS 变量、配置 Vite 代理以解决开发环境跨域。完成后 `pnpm dev` 可正常启动，Element Plus 组件可在任意 `.vue` 文件中免导入直接使用。

**涉及文件：**
- `vite.config.ts`（追加 auto-import 插件配置 + devServer proxy → localhost:9000）
- `src/App.vue`（替换 HelloWorld 为 `<router-view />` + Element Plus `<el-config-provider>`）
- `src/main.ts`（注册 router + pinia）
- `src/style.css`（Element Plus CSS 变量覆盖 + 全局样式）
- 新建目录：`src/views/`、`src/stores/`、`src/api/`、`src/router/`、`src/utils/`、`src/types/`

**验收标准：**
- [x] `unplugin-vue-components` 配置 ElementPlusResolver，`.vue` 文件中使用 `<el-button>` 等组件无需手动 import
- [x] `unplugin-auto-import` 配置 `vue`、`vue-router`、`pinia` 的 API 自动导入（ref/reactive/computed/watch 等免 import）
- [x] Vite proxy `/api` → `http://localhost:9000`，开发环境无跨域问题
- [x] 目录结构清晰：`api/`（按模块拆分）、`views/`（按页面拆分）、`stores/`（Pinia）、`router/`（路由配置）、`types/`（TS 类型定义）、`utils/`（工具函数）
- [x] `npm run dev` 启动无报错，浏览器访问 `http://localhost:5173` 显示空白页面（`<router-view />` 尚未配置路由）

**Blocked by:** 无 — 可立即开始

---

### - [x] Issue #2: HTTP 客户端与 API 层基础设施 — Axios 实例 + 拦截器 + 类型定义

**What to build**

封装 Axios 实例，实现请求拦截器（自动注入 Sa-Token Authorization header）、响应拦截器（统一解包 `Result<T>` 的 `.data` 字段 + 非 200 code 时抛错 + 401 时清除登录态跳转登录页）。定义与后端 DTO 对齐的 TypeScript 接口类型。为所有 29 个后端端点编写 API 函数签名，按模块拆分文件。

**涉及文件：**
- `src/utils/request.ts`（Axios 实例 + 拦截器）
- `src/utils/token.ts`（Token 存取工具：`getToken()` / `setToken()` / `removeToken()`，基于 localStorage）
- `src/types/common.ts`（`Result<T>`、`PageResult<T>` 等通用响应类型）
- `src/types/user.ts`（`LoginReq`、`RegisterReq`、`LoginVO`、`User`、`UserDetailVO`）
- `src/types/order.ts`（`SubmitOrderReq`、`WorkOrderVO`、`WorkOrderDetailVO`、`WorkOrderLogVO`、`PageQuery`、`RejectReq`、`AssignReq`）
- `src/types/admin.ts`（`Role`、`RoleVO`、`RoleCreateReq`、`RoleUpdateReq`、`PermissionTreeVO`、`SlaConfig`、`SlaConfigUpdateReq`、`StatsVO`、`UserRoleAssignReq`、`RolePermissionAssignReq`）
- `src/types/notification.ts`（`Notification` 类型）
- `src/api/user.ts`（register、login、getByUsername）
- `src/api/order.ts`（list、submit、detail、logs、actionToken、accept、start、complete、approve、reject、assign）
- `src/api/admin.ts`（listUsers、getUserDetail、assignUserRoles、listSlaConfig、updateSlaConfig、orderStats）
- `src/api/role.ts`（listRoles、getRoleDetail、createRole、updateRole、deleteRole、assignPermissions、permissionTree）
- `src/api/notification.ts`（list、unreadCount、markRead）

**验收标准：**
- [x] Axios 实例 `baseURL` 使用 `/api` 前缀，开发环境通过 Vite proxy 转发
- [x] 请求拦截器：从 localStorage 读取 token，自动设置 `Authorization` header（Sa-Token 格式）
- [x] 响应拦截器：自动提取 `response.data.data`；当 `code !== 200` 时 `Promise.reject` 并 `ElMessage.error(message)`
- [x] 响应拦截器：`code === 401` 时清除 token 并重定向到 `/login`
- [x] 所有 TypeScript 类型严格对齐 `api-docs.json` 中的 Schema 定义，字段名用 camelCase（后端驼峰下划线自动映射已由 MyBatis-Plus 处理）
- [x] 每个 API 函数有明确的参数类型和返回类型（带 `Promise<Result<T>>` 泛型）
- [x] API 文件按后端 Tag 分组：用户管理(`api/user.ts`)、工单管理(`api/order.ts`)、管理员(`api/admin.ts`)、角色管理(`api/role.ts`)、站内信(`api/notification.ts`)

**Blocked by:** Issue #1（需 Vite proxy 配置就绪）

---

### - [x] Issue #3: 登录注册页面 — 表单校验 + API 对接 + 路由跳转

**What to build**

实现登录页（`/login`）和注册页（`/register`）：Element Plus 表单 + 前端校验（用户名必填、密码最小长度、手机号格式）+ 调用 Issue #2 的 API + 成功后存储 Token 并跳转到首页。未登录用户访问任何受保护路由时自动重定向到登录页。登录页自身有基本的视觉居中布局。

**涉及文件：**
- `src/views/login/LoginView.vue`
- `src/views/register/RegisterView.vue`
- `src/router/index.ts`（路由表初始配置 + 全局前置守卫 `beforeEach`）
- `src/stores/auth.ts`（Pinia auth store：`login()` / `register()` / `logout()` actions + token/user state）

**验收标准：**
- [x] `/login` 路由渲染登录页：用户名输入框 + 密码输入框 + "登录"按钮 + "没有账号？去注册"链接
- [x] 表单校验：用户名必填（`required`）、密码必填（`required`），校验失败时输入框下方红色提示
- [x] 登录成功：调用 `POST /api/login` → 存储 token 到 localStorage → 存储用户信息到 Pinia → `router.push('/')` 跳转首页
- [x] 登录失败：`ElMessage.error` 显示后端返回的错误信息（如"用户名或密码错误"）
- [x] "登录中"loading 状态：按钮显示 loading 动画 + 禁用重复点击
- [x] `/register` 路由渲染注册页：用户名 + 密码 + 手机号（可选）+ 部门 ID（可选）+ "注册"按钮
- [x] 注册成功：`ElMessage.success` 提示 → 跳转登录页
- [x] 路由守卫：未登录（无 token）访问任何非 `/login`、`/register` 路由 → 重定向 `/login`
- [x] 已登录访问 `/login` → 重定向 `/`

**Blocked by:** Issue #2（需 API 函数就绪）

---

### - [x] Issue #4: 认证状态管理完善 — Token 持久化 + 用户信息获取 + 角色权限缓存

**What to build**

完善 Pinia auth store：页面刷新后从 localStorage 恢复 token 和用户信息；登录后调用 `GET /api/users/{username}` 获取完整用户信息（含 deptId）；提供 `hasPermission(permCode)` 和 `hasRole(roleCode)` 方法供全局权限判断；提供计算属性 `isAdmin`、`currentUser` 等。

**涉及文件：**
- `src/stores/auth.ts`（扩展：persist 逻辑 + `fetchUserInfo()` + `hasPermission()` + `hasRole()` + getters）
- `src/utils/token.ts`（确认 getToken/setToken/removeToken 逻辑完善）

**验收标准：**
- [x] 页面刷新后，auth store 从 localStorage 恢复 token，调用 `GET /api/users/{username}` 获取最新用户信息
- [x] `authStore.hasPermission('order:accept')` 返回 boolean — 前端按钮权限判断的基础
- [x] `authStore.hasRole('SYS_ADMIN')` 返回 boolean — 管理菜单显示判断的基础
- [x] `authStore.logout()` 清除 token + 清除用户状态 + 跳转 `/login`
- [x] 如果 token 过期（API 返回 401），自动触发 logout（已在 Issue #2 拦截器中处理，此处确保 store 同步清理）
- [x] `authStore.userInfo` 包含：id、username、phone、deptId、roles（角色列表）、permCodes（权限码列表）

**Blocked by:** Issue #3（需登录流程打通）

---

## 里程碑 2：应用布局 + 工单核心全流程

### - [x] Issue #5: 应用布局壳 — Sidebar 导航 + Header + 角色菜单动态渲染

**What to build**

实现管理后台标准布局：左侧可折叠 Sidebar 菜单（根据用户角色动态显示菜单项）、顶部 Header（Logo + 用户信息下拉 + 退出登录）、中央 `<router-view />` 主内容区。菜单项使用 Element Plus `<el-menu>` 组件，路由与菜单联动（`router` 模式）。不同角色看到不同菜单项。

**涉及文件：**
- `src/layout/AppLayout.vue`（主布局容器：el-container > el-aside + el-container > el-header + el-main）
- `src/layout/AppSidebar.vue`（侧边栏菜单组件）
- `src/layout/AppHeader.vue`（顶部栏组件）
- `src/router/index.ts`（路由重构：`/` → AppLayout 嵌套路由）

**验收标准：**
- [x] 整体布局：左侧 220px 可折叠侧边栏 + 右侧（顶部 60px header + 主内容区）
- [x] Sidebar 菜单项根据 `authStore.userInfo.permCodes` 动态渲染：
  - [x] 提交人角色：工单列表、创建工单
  - [x] 处理人角色：工单列表（含待分配池）、我的工单
  - [x] 管理员角色：工单管理、用户管理、角色管理、SLA 配置、统计报表
  - [x] 所有角色可见：站内信
- [x] 菜单项点击跳转对应路由，当前路由高亮（`router` 模式）
- [x] Sidebar 折叠/展开动画，折叠后显示图标
- [x] Header 右侧：用户头像/姓名下拉菜单（退出登录）
- [x] 退出登录：确认弹窗 → `authStore.logout()` → 跳转 `/login`
- [x] 路由结构：/ → AppLayout → children（/dashboard 已挂载，后续页面可直接追加）

**Blocked by:** Issue #4（需 `hasRole`/`hasPermission` 可用）

---

### - [x] Issue #6: 工单列表页 — 分页表格 + 多条件筛选 + RBAC 视图

**What to build**

实现工单列表页（`/orders`）：Element Plus 分页表格展示工单数据（orderNo、title、type、priority、status、submitterId、assigneeId、slaDeadline、createdAt），支持按状态下拉筛选、工单编号模糊搜索、页码/pageSize 切换。状态用不同颜色 Tag 展示，优先级用"普通/紧急"标签区分。点击行或"详情"按钮跳转工单详情页。表格上方有"创建工单"按钮（仅提交人可见）。

**涉及文件：**
- `src/views/orders/OrderListView.vue`
- `src/stores/order.ts`（Pinia order store：`fetchOrders()` action + list/pagination/filters state）
- `src/api/order.ts`（调用 `GET /api/orders` 的 `listOrders` 函数）

**验收标准：**
- [x] 页面加载时自动请求 `GET /api/orders?page=1&size=20`，渲染分页表格
- [x] 表格列：工单编号（可点击跳详情）、标题、类型、优先级（Tag：普通/紧急）、状态（彩色 Tag：PENDING 灰 / ACCEPTED 蓝 / IN_PROGRESS 橙 / AWAIT_APPROVAL 黄 / CLOSED 绿 / RELEASED 红 / ESCALATED_ADMIN 紫）、SLA 截止时间、创建时间
- [x] 筛选栏：状态下拉框（全部/PENDING/ACCEPTED/IN_PROGRESS/AWAIT_APPROVAL/CLOSED/RELEASED/ESCALATED_ADMIN）+ 编号搜索输入框 + 搜索按钮
- [x] 分页：支持切换每页条数（10/20/50/100）+ 页码跳转
- [x] 点击工单编号或"详情"按钮 → `router.push('/orders/{id}')`
- [x] "创建工单"按钮：仅当 `authStore.hasRole('SUBMITTER')` 或用户有提交权限时显示，点击跳转 `/orders/create`
- [x] 空数据状态：`<el-empty>` 组件显示"暂无工单"
- [x] 加载状态：表格区域显示 `<el-skeleton>` 或 `v-loading` 遮罩

**Blocked by:** Issue #5（需布局路由就绪）

---

### - [x] Issue #7: 工单创建页 — 表单 + 类型优先级选择 + 提交反馈

**What to build**

实现工单创建页（`/orders/create`）：表单包含标题（必填）、内容（必填、多行文本）、工单类型（下拉选择：REPAIR/LEAVE/REIMBURSE/OTHER）、优先级（普通/紧急 单选）。提交时调用 `POST /api/orders`，成功后提示并跳转到工单详情页。表单校验与后端 `@Valid` 注解对齐。

**涉及文件：**
- `src/views/orders/OrderCreateView.vue`
- `src/stores/order.ts`（新增 `submitOrder()` action）

**验收标准：**
- [x] 表单字段：标题（`el-input`，必填，maxlength=200）+ 内容（`el-input type=textarea`，必填，rows=5）+ 工单类型（`el-select`，必填，选项：报修/请假/报销/其他）+ 优先级（`el-radio-group`，默认"普通"）
- [x] 前端校验：标题非空 + 内容非空 + 类型必选 → 校验不通过时按钮 disabled 或点击时 `ElMessage.warning`
- [x] 提交按钮点击 → loading + disabled 防重复 → 调用 `submitOrder()` → 成功 `ElMessage.success('工单提交成功！工单编号: WO-...')` → `router.push('/orders/{id}')`
- [x] 提交失败 → `ElMessage.error` 显示后端错误信息
- [x] "取消"按钮 → `router.back()` 或跳转工单列表
- [x] 提交成功返回的 `WorkOrderVO` 中 `orderNo` 格式为 `WO-YYYYMMDD-XXXXX`

**Blocked by:** Issue #6（共享 order store 基础设施）

---

### - [x] Issue #8: 工单详情页 — 信息面板 + 操作日志时间线

**What to build**

实现工单详情页（`/orders/:id`）：上半部分为工单信息面板（所有字段以描述列表展示），下半部分为操作日志时间线（`<el-timeline>` 组件，按时间正序排列，每条显示操作类型、操作人、状态变化、备注、时间）。页面顶部有返回按钮。

**涉及文件：**
- `src/views/orders/OrderDetailView.vue`
- `src/components/order/OrderInfoPanel.vue`（工单信息面板子组件）
- `src/components/order/OrderLogTimeline.vue`（操作日志时间线子组件）
- `src/stores/order.ts`（新增 `fetchOrderDetail()` + `fetchOrderLogs()` actions）

**验收标准：**
- [x] 页面加载时调用 `GET /api/orders/{id}` 获取工单详情，调用 `GET /api/orders/{id}/logs` 获取操作日志
- [x] 信息面板（`<el-descriptions>`）：工单编号、标题、内容、类型、优先级（彩色 Tag）、状态（彩色 Tag）、提交人、处理人、驳回次数（如 rejectCount>0 标红）、SLA 截止时间（如已超时标红）、创建时间、更新时间
- [x] 操作日志时间线（`<el-timeline>`）：每条展示操作类型（中文映射：SUBMIT→提交、ACCEPT→接单、START→开始处理、COMPLETE→提交验收、APPROVE→验收通过、REJECT→驳回、ASSIGN→分配、RELEASE→释放）+ 操作人姓名 + 状态变化箭头（oldStatus → newStatus）+ 备注（如有）+ 时间
- [x] 日志按 `created_at` 正序排列（最早在上，最新在下），最新一条高亮
- [x] 顶部面包屑或返回按钮 → 回到工单列表
- [x] 工单不存在时（404）→ 显示 `<el-result status="404">` + 返回按钮

**Blocked by:** Issue #6（需列表页跳转入口）

---

### - [x] Issue #9: 工单状态流转操作 — 条件按钮渲染 + 确认弹窗 + 权限控制

**What to build**

在工单详情页根据当前工单状态 + 当前用户角色 + 用户身份（是否为提交人/处理人/管理员），动态渲染可执行的操作按钮（抢单/开始处理/提交验收/验收通过/验收驳回/管理员分配）。每个按钮点击后弹出确认弹窗（部分需填写备注/选择被指派人），调用对应 API。操作成功后刷新详情页数据。

这是最核心的前端交互 Issue，需要精确对齐后端状态机（TECHNICAL-PLAN.md 第 2 节）。

**涉及文件：**
- `src/components/order/OrderActions.vue`（操作按钮组 + 各弹窗组件）
- `src/stores/order.ts`（新增 accept/start/complete/approve/reject/assign actions）
- `src/views/orders/OrderDetailView.vue`（集成 OrderActions 组件）

**验收标准：**
- [x] 操作按钮可见性规则（严格对齐后端状态转移表）：
  - [x] **PENDING 状态** → 显示"抢单"按钮（需 `order:accept` 权限） + "分配"按钮（需 `order:assign` 权限，管理员可见）
  - [x] **ACCEPTED 状态** → 显示"开始处理"按钮（仅当前处理人可见）
  - [x] **IN_PROGRESS 状态** → 显示"提交验收"按钮（仅当前处理人可见）
  - [x] **AWAIT_APPROVAL 状态** → 显示"验收通过"按钮（仅提交人可见）+ "驳回"按钮（仅提交人可见，需 `order:reject` 权限）
  - [x] **其他终态**（CLOSED/RELEASED/ESCALATED_ADMIN）→ 不显示操作按钮
- [x] "抢单"：点击 → `ElMessageBox.confirm('确认抢单？')` → 调用 `POST /api/orders/{id}/accept` → 成功刷新详情
- [x] "分配"：点击 → 弹窗含用户选择器（下拉搜索/选择处理人）→ 调用 `POST /api/orders/{id}/assign`（带 `assigneeId`）
- [x] "开始处理"：点击 → confirm → 调用 `POST /api/orders/{id}/start`
- [x] "提交验收"：点击 → confirm → 调用 `POST /api/orders/{id}/complete`
- [x] "验收通过"：点击 → confirm → 调用 `POST /api/orders/{id}/approve`
- [x] "驳回"：点击 → 弹窗含备注输入框（`el-input type=textarea`，必填）+ 携带 action-token → 调用 `POST /api/orders/{id}/reject`（见 Issue #10）
- [x] 操作成功 → `ElMessage.success` + 重新加载详情数据（info + logs）
- [x] 操作失败 → `ElMessage.error` 显示具体错误（如"工单已被抢走"、"非法的状态转移"）
- [x] 操作期间按钮显示 loading 状态，禁止重复点击
- [x] 如果工单已进入 `ESCALATED_ADMIN` 状态 → 信息面板顶部显示醒目的 Warning Alert（"此工单驳回次数已达上限，已升级至管理员处理"）

**Blocked by:** Issue #8（需详情页就绪，且需了解当前状态 + 用户身份）

---

### - [x] Issue #10: 驳回 Token 防重交互 — action-token 获取 + Token 携带 + 重复提交拦截

**What to build**

驳回操作的特殊流程：用户点击"驳回"时，前端先调用 `GET /api/orders/{id}/action-token` 获取一次性 Token → 弹出驳回弹窗（备注 + 隐藏携带 Token）→ 提交时 `POST /api/orders/{id}/reject` 携带 `{token, remark}`。如果后端返回"请勿重复提交"错误，前端提示用户并刷新页面状态。Token 30 秒过期提示。

**涉及文件：**
- `src/components/order/RejectDialog.vue`（驳回弹窗组件，封装 Token 获取 + 提交逻辑）
- `src/components/order/OrderActions.vue`（集成 RejectDialog）

**验收标准：**
- [x] 点击"驳回"按钮 → 调用 `GET /api/orders/{id}/action-token` → 成功后弹出驳回弹窗
- [x] 驳回弹窗：备注输入框（必填，maxlength=500）+ 当前驳回次数提示（如"第 1 次驳回，最多 3 次"）+ 确认/取消按钮
- [x] 确认驳回 → `POST /api/orders/{id}/reject` body 含 `{token, remark}` → 成功 `ElMessage.success('已驳回')` + 刷新详情
- [x] 重复点击确认：如果同一 Token 第二次提交 → 后端返回 code≠200 → `ElMessage.error('请勿重复提交或 Token 已过期')` + 关闭弹窗 + 刷新详情
- [x] Token 获取失败（无 `order:reject` 权限）→ 按钮直接不显示（已在 Issue #9 处理）
- [x] "取消"按钮关闭弹窗，不做任何操作
- [x] 如果驳回后状态变为 `ESCALATED_ADMIN`（达到最大次数），弹窗关闭后详情页显示升级警告

**Blocked by:** Issue #9（需驳回操作框架就绪）

---

## 里程碑 3：管理后台

### - [x] Issue #11: 用户管理页 — 分页列表 + 搜索筛选 + 角色分配弹窗

**What to build**

实现管理员用户管理页（`/admin/users`）：分页表格（用户名、手机号、部门、状态、角色标签、创建时间）+ 用户名搜索 + 部门筛选 + "分配角色"按钮。点击"分配角色"弹出对话框，显示全部角色列表（多选），提交时调用 `PUT /api/admin/users/{id}/roles`。用户状态用开关或 Tag 展示。

**涉及文件：**
- `src/views/admin/UserManageView.vue`
- `src/components/admin/UserRoleDialog.vue`（角色分配弹窗）
- `src/stores/admin.ts`（新增 `fetchUsers()` + `assignUserRoles()` actions）

**验收标准：**
- [x] 页面需 `system:user:manage` 权限 + `SYS_ADMIN` 角色，否则显示 403 或菜单隐藏（路由守卫拦截）
- [x] 表格列：ID、用户名、手机号、部门 ID、状态（启用/禁用 Tag）、角色标签（多个 `<el-tag>` 展示 roleName）、创建时间
- [x] 搜索栏：用户名模糊搜索 + 部门 ID 输入框 + 搜索/重置按钮
- [x] 分页：支持 pageSize 切换和页码跳转
- [x] "分配角色"按钮：点击 → 弹窗展示全部角色列表（`GET /api/admin/roles`）+ 当前用户已有角色默认勾选（`GET /api/admin/users/{id}` 获取详情）
- [x] 角色分配弹窗：多选 checkbox 列表 + 确认/取消按钮 → 调用 `PUT /api/admin/users/{id}/roles` → 成功刷新表格
- [x] admin 用户不允许移除所有角色（前端提示 + 后端保护双保险）
- [x] 加载/空数据/错误状态处理

**Blocked by:** Issue #5（需管理路由 + 布局），Issue #4（需权限判断）

---

### - [x] Issue #12: 角色管理页 — 角色 CRUD + 权限树分配

**What to build**

实现角色管理页（`/admin/roles`）：表格展示全部角色（角色编码、角色名称、备注）+ 新建/编辑/删除按钮 + 权限分配按钮。权限分配使用 Element Plus `<el-tree>` 组件展示权限树（`GET /api/admin/permissions/tree`），支持全选/取消 + 父子联动。保护 4 个种子角色不允许删除。

**涉及文件：**
- `src/views/admin/RoleManageView.vue`
- `src/components/admin/RoleFormDialog.vue`（创建/编辑角色弹窗）
- `src/components/admin/PermissionTreeDialog.vue`（权限分配弹窗，含 `<el-tree>`）
- `src/stores/admin.ts`（新增角色 CRUD + 权限相关 actions）

**验收标准：**
- [x] 页面需 `system:role:manage` 权限，否则 403
- [x] 角色表格：角色编码、角色名称、备注、操作列（编辑/删除/权限分配按钮）
- [x] "新建角色"按钮 → 弹窗：角色编码（必填）+ 角色名称（必填）+ 备注 → 调用 `POST /api/admin/roles`
- [x] "编辑"按钮 → 弹窗预填当前数据 → 调用 `PUT /api/admin/roles/{id}`
- [x] "删除"按钮 → `ElMessageBox.confirm` → 调用 `DELETE /api/admin/roles/{id}` → 成功刷新
- [x] 4 个种子角色（SUBMITTER/HANDLER/DEPT_ADMIN/SYS_ADMIN）的删除按钮 disabled + tooltip 提示"种子角色不允许删除"
- [x] "权限分配"按钮 → 弹窗：`<el-tree>` 展示 `GET /api/admin/permissions/tree` 返回的树形数据 + 默认勾选已有权限（`GET /api/admin/roles/{id}` 获取 permIds）
- [x] 权限树支持：展开/折叠全部、父子联动（`check-strictly=false` 或手动处理半选状态）、全选/取消全选
- [x] 确认分配 → 调用 `PUT /api/admin/roles/{id}/permissions` → 成功 `ElMessage.success`
- [x] 空状态/加载/错误处理

**Blocked by:** Issue #11（共享 admin store 基础设施）

---

### - [x] Issue #13: SLA 配置管理页 — 配置表格 + 行内编辑

**What to build**

实现 SLA 配置页（`/admin/sla`）：表格展示全部 SLA 配置（按 type 分组显示 REPAIR/LEAVE/REIMBURSE/OTHER 四种类型 × 普通/紧急两个优先级的 8 条配置），每条显示接单时限（分钟）和完成时限（分钟）。支持行内编辑（`<el-input>` 直接修改数字）或弹窗编辑，保存时调用 `PUT /api/admin/sla/config`（根据 type+priority 定位）。

**涉及文件：**
- `src/views/admin/SlaConfigView.vue`
- `src/stores/admin.ts`（新增 `fetchSlaConfig()` + `updateSlaConfig()` actions）

**验收标准：**
- [x] 页面需 `sla:config:manage` 权限，否则 403
- [x] 表格：工单类型（REPAIR→报修 / LEAVE→请假 / REIMBURSE→报销 / OTHER→其他）+ 优先级（普通/紧急 Tag）+ 接单时限（分钟）+ 完成时限（分钟）
- [x] 每条配置有"编辑"按钮 → 该行切换为编辑模式（接单时限 + 完成时限变为 `<el-input-number>`）
- [x] 编辑模式：确认（保存）/ 取消按钮 → 确认调用 `PUT /api/admin/sla/config` body `{type, priority, acceptMinutes, finishMinutes}` → 成功刷新 + 退出编辑模式
- [x] `acceptMinutes` 和 `finishMinutes` 为正整数，`finishMinutes >= acceptMinutes` 前端校验
- [x] 保存 loading 状态，错误提示

**Blocked by:** Issue #5（需路由就绪），Issue #4（需权限判断）

---

### - [x] Issue #14: 工单统计仪表盘 — 状态分布统计 + 部门/全局切换

**What to build**

实现统计仪表盘页（`/admin/stats`）：调用 `GET /api/admin/orders/stats?scope=DEPT` 或 `scope=ALL` 获取各状态工单数量，以卡片 + 统计图表形式展示。管理员可切换部门/全局视图。用 Element Plus 或简易 CSS 卡片实现状态分布可视化（每种状态一个统计卡片，显示状态名 + 数量 + 占比进度条）。

**涉及文件：**
- `src/views/admin/StatsDashboardView.vue`
- `src/stores/admin.ts`（新增 `fetchStats()` action）

**验收标准：**
- [x] 页面需 `order:stats`（部门级）或 `order:stats:all`（全局）权限
- [x] 顶部切换：`<el-radio-group>` "本部门统计" / "全局统计"（全局统计仅当有 `order:stats:all` 权限时可选）
- [x] 统计卡片区：每种状态一个卡片，显示：
  - [x] 状态名称（中文映射 + 对应颜色）
  - [x] 数量（大数字）
  - [x] 占总数百分比（`<el-progress>` 进度条）
- [x] 卡片排列：PENDING / ACCEPTED / IN_PROGRESS / AWAIT_APPROVAL / CLOSED / RELEASED / ESCALATED_ADMIN
- [x] 切换范围时带 loading 过渡
- [x] 总数汇总显示在顶部
- [x] 数据为空时各卡片显示 0

**Blocked by:** Issue #11（共享 admin store）

---

## 里程碑 4：站内信 + 全局集成

### - [x] Issue #15: 站内信中心 — 通知列表 + 未读计数 + 标记已读

**What to build**

实现站内信中心页（`/notifications`）：分页列表展示当前用户的通知（标题、内容摘要、关联类型、时间、已读/未读状态）。未读通知左侧蓝色标记点。点击通知行标记为已读（调用 `PUT /api/notifications/{id}/read`）。支持"全部标为已读"批量操作。

**涉及文件：**
- `src/views/notifications/NotificationListView.vue`
- `src/stores/notification.ts`（Pinia notification store：`fetchNotifications()` + `markAsRead()` + `markAllAsRead()` + `fetchUnreadCount()` actions）

**验收标准：**
- [x] 页面路由 `/notifications`，所有角色可访问
- [x] 通知列表：每条显示标题（加粗未读/正常已读）+ 内容摘要（截断50字符）+ 关联类型 Tag（ORDER/SYSTEM）+ 时间 + 已读/未读状态（蓝色圆点标记）
- [x] 未读通知左侧有蓝色竖线标记，已读通知无标记
- [x] 点击通知行 → 调用 `PUT /api/notifications/{id}/read` 标记已读 → 刷新列表样式（标记消失、标题变正常）+ 全局未读计数 -1
- [x] "全部标为已读"按钮 → `ElMessageBox.confirm` → 逐条调用 markAsRead → 刷新列表
- [x] 分页支持
- [x] 空状态：`<el-empty description="暂无通知" />`
- [x] 如果通知有 `refId`（关联工单 ID），点击标题可跳转到对应工单详情

**Blocked by:** Issue #5（需布局路由）

---

### - [x] Issue #16: Header 未读通知角标 — 轮询计数 + 快捷入口

**What to build**

在 AppHeader 的铃铛图标上显示未读通知数量角标（`<el-badge>`）。进入应用后定时轮询 `GET /api/notifications/unread-count`（每 60 秒一次）。点击铃铛跳转站内信中心。未读数为 0 时不显示角标或显示灰色 0。

**涉及文件：**
- `src/layout/AppHeader.vue`（追加铃铛图标 + 未读角标）
- `src/stores/notification.ts`（已有 `fetchUnreadCount()`，添加定时轮询逻辑）

**验收标准：**
- [x] Header 铃铛图标（`<el-icon><Bell /></el-icon>`）+ `<el-badge :value="unreadCount" :hidden="unreadCount === 0">`
- [x] 页面加载后自动获取未读计数，每 60 秒轮询刷新
- [x] 点击铃铛 → `router.push('/notifications')`
- [x] 当 `unreadCount > 99` 时显示 "99+"
- [x] 用户退出登录后停止轮询（clearInterval）
- [x] 路由切换到 `/notifications` 页面时，列表加载后重新获取未读计数
- [x] 用户在 Issue #15 中标记已读后，store 中的 unreadCount 实时更新，角标数字同步变化

**Blocked by:** Issue #15（需 notification store 就绪）

---

### - [ ] Issue #17: 全局集成回归 — 端到端流程验证 + 边界状态 + UI 打磨 + 构建验证

> 状态说明(2026-09-04)：核心业务路径已端到端验证(后端 HTTP + 前端 UI 实测)；管理后台/部分边界 UI 尚未逐项浏览器实测，待补。勾选项均标注验证方式。

**What to build**

执行前端全部页面和交互的端到端集成验证，覆盖 4 条核心用户路径。修复发现的问题，补充遗漏的边界状态处理（403 页面、404 页面、网络错误页面、Token 过期体验）。统一 Element Plus 中文国际化配置。确保 `pnpm build` 无 TypeScript 错误。

**涉及文件：**
- 全部已有文件（修复 + 打磨）
- `src/views/error/ForbiddenView.vue`（403 无权限页面）
- `src/views/error/NotFoundView.vue`（404 页面）
- `src/router/index.ts`（追加 403/404 路由 + 全局错误捕获）
- `src/main.ts`（Element Plus 中文语言包注册）

**验收标准：**
- [x] **黄金路径（提交人 → 处理人 → 提交人）**：（后端 HTTP 全链 + 前端 UI 抢单/开始处理实测）
  1. 注册 submitter01 → 登录 → 提交工单（title="空调报修"）→ 列表可见新工单 PENDING
  2. handler01 登录 → 工单列表可见 PENDING 池 → 点击抢单 → status 变 ACCEPTED
  3. handler01 点击开始处理 → IN_PROGRESS → 提交验收 → AWAIT_APPROVAL
  4. submitter01 登录 → 进入工单详情 → 验收通过 → CLOSED
  5. 操作日志时间线完整：SUBMIT→ACCEPT→START→COMPLETE→APPROVE（5 条）
- [x] **驳回升级路径**：（后端 HTTP 实测：3 次驳回→ESCALATED_ADMIN→通知管理员；详情页升级警告待 UI 补验）
  1. submitter01 提交工单 → handler01 accept → start → complete → AWAIT_APPROVAL
  2. submitter01 获取 action-token → 驳回（备注"第1次驳回"） → IN_PROGRESS, rejectCount=1
  3. 重复上述流程至第 3 次驳回 → 状态变为 ESCALATED_ADMIN → 详情页显示升级警告
  4. 管理员站内信可见驳回升级通知
- [x] **并发抢单路径**：两个处理人同时抢同一工单 → 一个成功一个提示"工单已被抢走"（后端 10 线程并发单测验证：恰好 1 成功）
- [ ] **管理后台路径**：
  1. admin 登录 → 用户管理：创建角色"测试角色" + 分配 order:accept/order:start 权限
  2. admin 为用户分配"测试角色" → handler01 登录验证仅可见抢单和开始处理按钮
  3. admin 修改 SLA 配置（REPAIR+普通：接单 60min，完成 240min）
  4. admin 查看统计仪表盘 → 状态分布数据正确
- [ ] 边界状态覆盖：
  - [ ] 403 页面：无权限用户直接访问 `/admin/users` → 显示"无权限访问" + 返回首页按钮
  - [ ] 404 页面：访问不存在的路由 → 显示"页面不存在" + 返回首页按钮
  - [ ] 网络错误：API 请求超时或网络断开 → `ElMessage.error('网络异常，请稍后重试')`
  - [ ] Token 过期：操作时 API 返回 401 → 自动跳转登录页 + 提示"登录已过期，请重新登录"
  - [ ] 工单不存在：访问 `/orders/99999` → 显示 404 结果组件
- [x] Element Plus 全局中文语言包（main.ts 注册 `locale: zhCn`），分页/弹窗等组件文本为中文
- [x] `pnpm build` 无 TypeScript 编译错误（`npm run build` 实测 EXIT 0）
- [ ] 构建产物大小合理（非核心关注，但不应出现明显异常如 >5MB 的单 chunk）

**Blocked by:** Issue #1~#16 全部

---

> **里程碑 1（Day 1-2, Issue #1~#4）：工程基石 + 登录注册** ⬅ 当前冲刺
> **里程碑 2（Day 3-5, Issue #5~#10）：应用布局 + 工单核心全流程**
> **里程碑 3（Day 6-8, Issue #11~#14）：管理后台**
> **里程碑 4（Day 9-10, Issue #15~#17）：站内信 + 全局集成**

---

## 附录 A：前端路由总表

| 路径 | 页面 | 所需权限 | 对应 Issue |
|---|---|---|---|
| `/login` | 登录页 | 无（公开） | #3 |
| `/register` | 注册页 | 无（公开） | #3 |
| `/` | 首页/工单列表 | 登录即可 | #6 |
| `/orders` | 工单列表 | 登录即可 | #6 |
| `/orders/create` | 创建工单 | 登录即可 | #7 |
| `/orders/:id` | 工单详情 | 登录即可（RBAC 数据过滤在后端） | #8, #9, #10 |
| `/notifications` | 站内信中心 | 登录即可 | #15 |
| `/admin/users` | 用户管理 | `system:user:manage` | #11 |
| `/admin/roles` | 角色管理 | `system:role:manage` | #12 |
| `/admin/sla` | SLA 配置 | `sla:config:manage` | #13 |
| `/admin/stats` | 统计仪表盘 | `order:stats` 或 `order:stats:all` | #14 |
| `/403` | 无权限页面 | 无（公开） | #17 |
| `/:pathMatch(.*)*` | 404 页面 | 无（公开） | #17 |

## 附录 B：后端 API 端点速查

| 方法 | 路径 | 说明 | 前端 Issue |
|---|---|---|---|
| POST | `/api/login` | 用户登录 | #3 |
| POST | `/api/users/register` | 用户注册 | #3 |
| GET | `/api/users/{username}` | 根据用户名查用户 | #4 |
| GET | `/api/orders` | 工单列表（分页+筛选） | #6 |
| POST | `/api/orders` | 提交工单 | #7 |
| GET | `/api/orders/{id}` | 工单详情（含日志） | #8 |
| GET | `/api/orders/{id}/logs` | 工单操作日志 | #8 |
| GET | `/api/orders/{id}/action-token` | 获取驳回 Token | #10 |
| POST | `/api/orders/{id}/accept` | 抢单 | #9 |
| POST | `/api/orders/{id}/start` | 开始处理 | #9 |
| POST | `/api/orders/{id}/complete` | 提交验收 | #9 |
| POST | `/api/orders/{id}/approve` | 验收通过 | #9 |
| POST | `/api/orders/{id}/reject` | 验收驳回 | #9, #10 |
| POST | `/api/orders/{id}/assign` | 管理员分配工单 | #9 |
| GET | `/api/admin/users` | 用户列表分页 | #11 |
| GET | `/api/admin/users/{id}` | 用户详情（含角色权限） | #11 |
| PUT | `/api/admin/users/{id}/roles` | 分配用户角色 | #11 |
| GET | `/api/admin/roles` | 角色列表 | #12 |
| GET | `/api/admin/roles/{id}` | 角色详情 | #12 |
| POST | `/api/admin/roles` | 创建角色 | #12 |
| PUT | `/api/admin/roles/{id}` | 更新角色 | #12 |
| DELETE | `/api/admin/roles/{id}` | 删除角色 | #12 |
| PUT | `/api/admin/roles/{id}/permissions` | 为角色分配权限 | #12 |
| GET | `/api/admin/permissions/tree` | 权限树 | #12 |
| GET | `/api/admin/sla/config` | 查看全部 SLA 配置 | #13 |
| PUT | `/api/admin/sla/config` | 更新 SLA 配置 | #13 |
| GET | `/api/admin/orders/stats` | 工单统计 | #14 |
| GET | `/api/notifications` | 站内信列表 | #15 |
| PUT | `/api/notifications/{id}/read` | 标记已读 | #15 |
| GET | `/api/notifications/unread-count` | 未读计数 | #16 |
