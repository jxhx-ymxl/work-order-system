<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessageBox, ElMessage } from 'element-plus'
import { Bell } from '@element-plus/icons-vue'
import { useNotificationStore } from '@/stores/notification'
import type { Notification } from '@/types/notification'

const router = useRouter()
const store = useNotificationStore()

/** 站内信关联类型映射 */
const REF_TYPE_MAP: Record<string, { label: string; color: string }> = {
  ORDER: { label: '工单', color: '' },
  SYSTEM: { label: '系统', color: 'info' },
}

/** 内容摘要截断（50 字符） */
function contentSummary(content: string): string {
  if (!content) return ''
  return content.length > 50 ? content.slice(0, 50) + '...' : content
}

/** 格式化时间 */
function formatTime(dateStr: string): string {
  if (!dateStr) return ''
  const date = new Date(dateStr)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

/** 点击通知行 → 标记已读 + 跳转 */
async function handleRowClick(row: Notification): Promise<void> {
  // 未读通知：标记已读
  if (row.isRead === 0) {
    try {
      await store.markAsRead(row.id)
    } catch {
      ElMessage.error('标记已读失败')
    }
  }
  // 有关联工单 ID → 跳转工单详情
  if (row.refId) {
    router.push(`/orders/${row.refId}`)
  }
}

/** 全部标为已读 */
async function handleMarkAllRead(): Promise<void> {
  try {
    await ElMessageBox.confirm('确定将所有通知标记为已读？', '提示', { type: 'warning' })
  } catch {
    return // 用户取消
  }
  try {
    await store.markAllAsRead()
    ElMessage.success('已全部标记为已读')
  } catch {
    ElMessage.error('操作失败，请稍后重试')
  }
}

/** 分页变化 */
function handlePageChange(page: number): void {
  store.fetchNotifications(page)
}

function handleSizeChange(size: number): void {
  store.fetchNotifications(1, size)
}

onMounted(() => {
  store.fetchNotifications()
})
</script>

<template>
  <div class="notification-center">
    <div class="notification-header">
      <h2 class="page-title">
        <el-icon><Bell /></el-icon>
        站内信中心
      </h2>
      <el-button
        type="primary"
        :disabled="store.notifications.every((n) => n.isRead === 1)"
        @click="handleMarkAllRead"
      >
        全部标为已读
      </el-button>
    </div>

    <!-- 空状态 -->
    <el-empty
      v-if="!store.loading && store.notifications.length === 0"
      description="暂无通知"
    />

    <!-- 通知列表 -->
    <div
      v-else
      v-loading="store.loading"
      class="notification-list"
    >
      <div
        v-for="item in store.notifications"
        :key="item.id"
        class="notification-item"
        :class="{ 'is-unread': item.isRead === 0 }"
        @click="handleRowClick(item)"
      >
        <!-- 未读蓝色竖线标记 -->
        <div class="notification-marker">
          <span v-if="item.isRead === 0" class="unread-dot" />
        </div>

        <div class="notification-content">
          <!-- 标题行：标题 + 关联类型 Tag + 时间 -->
          <div class="notification-title-row">
            <span
              class="notification-title"
              :class="{ 'is-bold': item.isRead === 0 }"
            >
              {{ item.title }}
            </span>
            <el-tag
              v-if="item.refType"
              :type="(REF_TYPE_MAP[item.refType]?.color as 'info' | '' | 'success' | 'warning' | 'danger') || 'info'"
              size="small"
            >
              {{ REF_TYPE_MAP[item.refType]?.label || item.refType }}
            </el-tag>
            <span class="notification-time">{{ formatTime(item.createdAt) }}</span>
          </div>

          <!-- 内容摘要 -->
          <div class="notification-summary" v-if="item.content">
            {{ contentSummary(item.content) }}
          </div>

          <!-- 关联提示 -->
          <div v-if="item.refId" class="notification-ref">
            关联工单 #{{ item.refId }} — 点击查看详情
          </div>
        </div>
      </div>

      <!-- 分页 -->
      <div v-if="store.pagination.total > 0" class="notification-pagination">
        <el-pagination
          v-model:current-page="store.pagination.current"
          :page-size="store.pagination.size"
          :total="store.pagination.total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          background
          @current-change="handlePageChange"
          @size-change="handleSizeChange"
        />
      </div>
    </div>
  </div>
</template>

<style scoped>
.notification-center {
  max-width: 900px;
  margin: 0 auto;
}

.notification-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 20px;
}

.page-title {
  font-size: 20px;
  font-weight: 600;
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
}

.notification-list {
  min-height: 200px;
  background: var(--el-bg-color);
  border-radius: 8px;
  padding: 0;
}

.notification-item {
  display: flex;
  align-items: stretch;
  padding: 16px 20px;
  cursor: pointer;
  border-bottom: 1px solid var(--el-border-color-lighter);
  transition: background-color 0.15s;
}

.notification-item:hover {
  background-color: var(--el-fill-color-light);
}

.notification-item.is-unread {
  background-color: #f0f7ff;
}

.notification-item.is-unread:hover {
  background-color: #e6f2ff;
}

.notification-marker {
  display: flex;
  align-items: flex-start;
  padding-top: 4px;
  margin-right: 12px;
  flex-shrink: 0;
  width: 10px;
}

.unread-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background-color: var(--el-color-primary);
  flex-shrink: 0;
}

.notification-content {
  flex: 1;
  min-width: 0;
}

.notification-title-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.notification-title {
  font-size: 15px;
  color: var(--el-text-color-regular);
  line-height: 1.5;
}

.notification-title.is-bold {
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.notification-time {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
  margin-left: auto;
  white-space: nowrap;
}

.notification-summary {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  margin-top: 6px;
  line-height: 1.5;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.notification-ref {
  font-size: 12px;
  color: var(--el-color-primary);
  margin-top: 6px;
  cursor: pointer;
}

.notification-pagination {
  display: flex;
  justify-content: center;
  padding: 20px 0;
}
</style>
