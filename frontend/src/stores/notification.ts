import { defineStore } from 'pinia'
import { ref, reactive } from 'vue'
import { listNotifications, markAsRead as markAsReadApi, getUnreadCount } from '@/api/notification'
import type { Notification } from '@/types/notification'
import type { PageResult } from '@/types/common'

export const useNotificationStore = defineStore('notification', () => {
  // ──── List State ────
  const notifications = ref<Notification[]>([])
  const loading = ref(false)

  const pagination = reactive({
    total: 0,
    pages: 0,
    current: 1,
    size: 20,
  })

  // ──── Unread Count State ────
  const unreadCount = ref(0)
  const unreadLoading = ref(false)

  // ──── Actions ────

  /** 获取站内信分页列表 */
  async function fetchNotifications(page?: number, size?: number): Promise<void> {
    loading.value = true
    try {
      const params: { page: number; size: number } = {
        page: page ?? pagination.current,
        size: size ?? pagination.size,
      }
      const result: PageResult<Notification> = await listNotifications(params)
      notifications.value = result.records ?? []
      pagination.total = result.total
      pagination.pages = result.pages
      pagination.current = result.current
      if (size !== undefined) {
        pagination.size = size
      }
    } finally {
      loading.value = false
    }
  }

  /** 标记单条通知已读 */
  async function markAsRead(id: number): Promise<void> {
    await markAsReadApi(id)
    // 本地更新成功标记的项
    const target = notifications.value.find((n) => n.id === id)
    if (target) {
      target.isRead = 1
    }
    // 未读计数 -1
    if (unreadCount.value > 0) {
      unreadCount.value--
    }
  }

  /** 全部标为已读：逐条调用 markAsRead */
  async function markAllAsRead(): Promise<void> {
    const unreadIds = notifications.value
      .filter((n) => n.isRead === 0)
      .map((n) => n.id)

    if (unreadIds.length === 0) return

    // 逐条标记已读（后端无批量接口）
    const promises = unreadIds.map((id) => markAsReadApi(id))
    await Promise.all(promises)

    // 本地批量更新
    for (const n of notifications.value) {
      if (n.isRead === 0) {
        n.isRead = 1
      }
    }
    // 未读计数归零
    unreadCount.value = 0
  }

  /** 获取未读计数 */
  async function fetchUnreadCount(): Promise<void> {
    unreadLoading.value = true
    try {
      const data = await getUnreadCount()
      unreadCount.value = data.count ?? 0
    } finally {
      unreadLoading.value = false
    }
  }

  return {
    // state
    notifications,
    loading,
    pagination,
    unreadCount,
    unreadLoading,
    // actions
    fetchNotifications,
    markAsRead,
    markAllAsRead,
    fetchUnreadCount,
  }
})
