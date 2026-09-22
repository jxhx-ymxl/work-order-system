<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import {
  List,
  DataBoard,
  Plus,
  User,
  Setting,
  Bell,
  TrendCharts,
  Timer,
} from '@element-plus/icons-vue'

const route = useRoute()
const auth = useAuthStore()
const isCollapse = ref(false)

interface MenuItem {
  path: string
  title: string
  icon: string
  visible: boolean
  children?: MenuItem[]
}

/** 根据用户权限码动态构建菜单（权限驱动，不再硬编码角色） */
const menuItems = computed<MenuItem[]>(() => {
  const items: MenuItem[] = []

  // 工单大厅 — 所有登录用户可见
  items.push({
    path: '/orders',
    title: '工单大厅',
    icon: 'List',
    visible: true,
  })

  // 创建工单 — 所有登录用户可见
  items.push({
    path: '/orders/create',
    title: '创建工单',
    icon: 'Plus',
    visible: true,
  })

  // 系统看板 — 需统计权限（部门级或全局）
  items.push({
    path: '/dashboard',
    title: '系统看板',
    icon: 'DataBoard',
    visible: auth.hasPermission('order:stats') || auth.hasPermission('order:stats:all'),
  })

  // 管理后台 — 权限驱动：子菜单按权限码逐个判定，全部不可见则父级自动隐藏
  const adminAllChildren: MenuItem[] = [
    {
      path: '/admin/users',
      title: '用户管理',
      icon: 'User',
      visible: auth.hasPermission('system:user:manage'),
    },
    {
      path: '/admin/roles',
      title: '角色管理',
      icon: 'Setting',
      visible: auth.hasPermission('system:role:manage'),
    },
    {
      path: '/admin/sla',
      title: 'SLA 配置',
      icon: 'Timer',
      visible: auth.hasPermission('sla:config:manage'),
    },
    {
      path: '/admin/stats',
      title: '统计报表',
      icon: 'TrendCharts',
      visible: auth.hasPermission('order:stats') || auth.hasPermission('order:stats:all'),
    },
  ]

  const visibleAdminChildren = adminAllChildren.filter((c) => c.visible)

  if (visibleAdminChildren.length > 0) {
    items.push({
      path: '/admin',
      title: '管理后台',
      icon: 'Setting',
      visible: true,
      children: visibleAdminChildren,
    })
  }

  // 站内信 — 所有登录用户可见
  items.push({
    path: '/notifications',
    title: '站内信',
    icon: 'Bell',
    visible: true,
  })

  return items.filter((item) => item.visible)
})

const activeMenu = computed(() => {
  // 对管理后台子路由，默认激活父级以便子菜单展开并高亮
  if (route.path.startsWith('/admin')) {
    return route.path
  }
  return route.path
})

/** 根据当前路径自动展开父级子菜单 */
const defaultOpeneds = computed(() => {
  if (route.path.startsWith('/admin')) {
    return ['/admin']
  }
  return []
})

/** 图标组件映射 */
function iconComponent(name: string) {
  const map: Record<string, object> = {
    List,
    DataBoard,
    Plus,
    User,
    Setting,
    Bell,
    TrendCharts,
    Timer,
  }
  return map[name]
}
</script>

<template>
  <el-aside :width="isCollapse ? '64px' : '220px'" class="app-sidebar">
    <div class="sidebar-logo" @click="isCollapse = !isCollapse">
      <span v-if="!isCollapse" class="sidebar-logo-text">工单流转平台</span>
      <span v-else class="sidebar-logo-mini">工单</span>
    </div>

    <el-menu
      :default-active="activeMenu"
      :default-openeds="defaultOpeneds"
      :collapse="isCollapse"
      router
      background-color="#304156"
      text-color="#bfcbd9"
      active-text-color="#409EFF"
    >
      <template v-for="item in menuItems" :key="item.path">
        <!-- 无子菜单 -->
        <el-menu-item v-if="!item.children" :index="item.path">
          <el-icon>
            <component :is="iconComponent(item.icon)" />
          </el-icon>
          <template #title>{{ item.title }}</template>
        </el-menu-item>

        <!-- 有子菜单 -->
        <el-sub-menu v-else :index="item.path">
          <template #title>
            <el-icon>
              <component :is="iconComponent(item.icon)" />
            </el-icon>
            <span>{{ item.title }}</span>
          </template>
          <el-menu-item
            v-for="child in item.children"
            :key="child.path"
            :index="child.path"
          >
            <el-icon>
              <component :is="iconComponent(child.icon)" />
            </el-icon>
            <template #title>{{ child.title }}</template>
          </el-menu-item>
        </el-sub-menu>
      </template>
    </el-menu>
  </el-aside>
</template>

<style scoped>
.app-sidebar {
  background-color: #304156;
  transition: width 0.3s;
  overflow: hidden;
  flex-shrink: 0;
}

.sidebar-logo {
  height: 60px;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
  user-select: none;
}

.sidebar-logo-text {
  color: #fff;
  font-size: 18px;
  font-weight: 600;
  white-space: nowrap;
}

.sidebar-logo-mini {
  color: #fff;
  font-size: 16px;
  font-weight: 600;
}

/* 覆盖 el-menu 边框 */
:deep(.el-menu) {
  border-right: none;
}
</style>
