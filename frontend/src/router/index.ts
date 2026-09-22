import { createRouter, createWebHistory } from 'vue-router'
import { getToken } from '@/utils/token'

/** 路由权限元数据 */
interface RoutePermMeta {
  /** 所需权限码（单个字符串或字符串数组——数组表示"拥有任一即可"） */
  permission?: string | string[]
  /** 是否公开路由（无需登录） */
  public?: boolean
  /** 页面标题 */
  title?: string
}

// 扩展 vue-router 的 RouteMeta 类型，使 TypeScript 识别自定义 meta 字段
declare module 'vue-router' {
  interface RouteMeta extends RoutePermMeta {}
}

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'Login',
      component: () => import('@/views/login/LoginView.vue'),
      meta: { public: true, title: '登录' },
    },
    {
      path: '/register',
      name: 'Register',
      component: () => import('@/views/login/RegisterView.vue'),
      meta: { public: true, title: '注册' },
    },
    {
      path: '/',
      component: () => import('@/layout/AppLayout.vue'),
      redirect: '/dashboard',
      children: [
        {
          path: 'dashboard',
          name: 'Dashboard',
          component: () => import('@/views/DashboardView.vue'),
          meta: { title: '系统看板' },
        },
        {
          path: 'orders',
          name: 'OrderList',
          component: () => import('@/views/orders/OrderListView.vue'),
          meta: { title: '工单列表' },
        },
        {
          path: 'orders/create',
          name: 'OrderCreate',
          component: () => import('@/views/orders/OrderCreateView.vue'),
          meta: { title: '创建工单' },
        },
        {
          path: 'orders/:id',
          name: 'OrderDetail',
          component: () => import('@/views/orders/OrderDetailView.vue'),
          meta: { title: '工单详情' },
        },
        // ──── 管理后台：权限码驱动鉴权 ────
        {
          path: 'admin/users',
          name: 'UserManage',
          component: () => import('@/views/admin/UserManageView.vue'),
          meta: { title: '用户管理', permission: 'system:user:manage' },
        },
        {
          path: 'admin/roles',
          name: 'RoleManage',
          component: () => import('@/views/admin/RoleManageView.vue'),
          meta: { title: '角色管理', permission: 'system:role:manage' },
        },
        {
          path: 'admin/sla',
          name: 'SlaConfig',
          component: () => import('@/views/admin/SlaConfigView.vue'),
          meta: { title: 'SLA 配置', permission: 'sla:config:manage' },
        },
        {
          path: 'admin/stats',
          name: 'StatsDashboard',
          component: () => import('@/views/admin/StatsDashboardView.vue'),
          meta: {
            title: '统计报表',
            // 拥有任一统计权限即可访问（具体范围在页面内切换）
            permission: ['order:stats', 'order:stats:all'],
          },
        },
        {
          path: 'notifications',
          name: 'Notifications',
          component: () => import('@/views/notifications/NotificationListView.vue'),
          meta: { title: '站内信中心' },
        },
      ],
    },
    {
      path: '/403',
      name: 'Forbidden',
      component: () => import('@/views/error/ForbiddenView.vue'),
      meta: { public: true, title: '无权限' },
    },
    {
      path: '/:pathMatch(.*)*',
      name: 'NotFound',
      component: () => import('@/views/error/NotFoundView.vue'),
      meta: { public: true, title: '页面不存在' },
    },
  ],
})

// 白名单路由：无需登录即可访问
const WHITE_LIST = ['/login', '/register', '/403']

router.beforeEach(async (to) => {
  const token = getToken()

  // ── 公开路由直接放行 ──
  if (to.meta.public || WHITE_LIST.includes(to.path)) {
    if (token && (to.path === '/login' || to.path === '/register')) {
      return '/dashboard'
    }
    return true
  }

  // ── 未登录 → 跳转登录页（携带 redirect 参数，登录后回跳） ──
  if (!token) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }

  // ── 已登录：首次进入应用时静默同步后端最新权限 ──
  //     解决"超管修改目标用户权限后，目标用户 F5 刷新仍读旧缓存"问题
  const { useAuthStore } = await import('@/stores/auth')
  const authStore = useAuthStore()

  if (!authStore.isInitialized) {
    await authStore.initFromServer()
  }

  // ── 权限码鉴权：meta.permission 为 string 或 string[] ──
  const required = to.meta.permission
  if (required) {
    const perms = Array.isArray(required) ? required : [required]
    const hasAny = perms.some((p) => authStore.hasPermission(p))
    if (!hasAny) {
      return '/403'
    }
  }

  return true
})

export default router
