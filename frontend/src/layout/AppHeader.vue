<script setup lang="ts">
import { onMounted, onUnmounted, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { ArrowDown, SwitchButton, Bell } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import { useNotificationStore } from '@/stores/notification'

const auth = useAuthStore()
const notificationStore = useNotificationStore()
const router = useRouter()
const route = useRoute()

// ──── 60 秒定时轮询未读计数 ────
const POLL_INTERVAL = 60_000
let pollTimer: ReturnType<typeof setInterval> | null = null

/** 启动轮询 */
function startPolling(): void {
  // 先立刻获取一次
  if (auth.isLoggedIn) {
    notificationStore.fetchUnreadCount()
  }
  // 每 60 秒轮询
  pollTimer = setInterval(() => {
    if (auth.isLoggedIn) {
      notificationStore.fetchUnreadCount()
    }
  }, POLL_INTERVAL)
}

/** 停止轮询 */
function stopPolling(): void {
  if (pollTimer !== null) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

/** 点击铃铛 → 跳转站内信中心 */
function handleBellClick(): void {
  router.push('/notifications')
}

/** 退出登录 */
function handleLogout(): void {
  ElMessageBox.confirm('确定要退出登录吗？', '提示', {
    type: 'warning',
  })
    .then(() => {
      stopPolling() // 登出时停止轮询，防内存泄漏
      auth.logout()
    })
    .catch(() => {
      // 用户取消
    })
}

onMounted(() => {
  startPolling()
})

onUnmounted(() => {
  stopPolling()
})

// 路由切换到 /notifications 时，重新获取未读计数
watch(
  () => route.path,
  (path) => {
    if (path === '/notifications') {
      notificationStore.fetchUnreadCount()
    }
  },
)
</script>

<template>
  <el-header class="app-header">
    <div class="header-right">
      <!-- 站内信铃铛 + 未读角标 -->
      <div class="header-bell" @click="handleBellClick">
        <el-badge
          :value="notificationStore.unreadCount"
          :hidden="notificationStore.unreadCount === 0"
          :max="99"
        >
          <el-icon :size="22">
            <Bell />
          </el-icon>
        </el-badge>
      </div>

      <!-- 用户下拉 -->
      <el-dropdown trigger="click">
        <span class="header-user-area">
          <el-avatar :size="32" icon="UserFilled" />
          <span class="header-username">{{ auth.username || '未登录' }}</span>
          <el-icon class="header-arrow">
            <ArrowDown />
          </el-icon>
        </span>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item @click="handleLogout">
              <el-icon><SwitchButton /></el-icon>
              退出登录
            </el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
  </el-header>
</template>

<style scoped>
.app-header {
  background: #fff;
  border-bottom: 1px solid var(--el-border-color-light);
  display: flex;
  align-items: center;
  justify-content: flex-end;
  padding: 0 20px;
  height: 60px;
  flex-shrink: 0;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 20px;
}

.header-bell {
  display: flex;
  align-items: center;
  cursor: pointer;
  padding: 4px;
  border-radius: 4px;
  transition: background-color 0.2s;
}

.header-bell:hover {
  background-color: var(--el-fill-color-light);
}

.header-user-area {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  padding: 4px 8px;
  border-radius: 4px;
  transition: background-color 0.2s;
}

.header-user-area:hover {
  background-color: var(--el-fill-color-light);
}

.header-username {
  font-size: 14px;
  color: var(--el-text-color-primary);
}

.header-arrow {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
