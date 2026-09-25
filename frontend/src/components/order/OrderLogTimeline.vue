<script setup lang="ts">
import { computed } from 'vue'
import type { WorkOrderLogVO } from '@/types/order'
import { ACTION_MAP, STATUS_MAP } from '@/types/order'

const props = defineProps<{
  logs: WorkOrderLogVO[]
}>()

/** 按 created_at 正序排列（最早在上） */
const sortedLogs = computed(() =>
  [...props.logs].sort((a, b) => {
    const ta = a.createdAt ? new Date(a.createdAt).getTime() : 0
    const tb = b.createdAt ? new Date(b.createdAt).getTime() : 0
    return ta - tb
  }),
)

/** 最新一条日志的 ID */
const latestId = computed(() => {
  const list = sortedLogs.value
  return list.length > 0 ? list[list.length - 1].id : null
})

/** 操作类型中文映射 */
function actionLabel(action: string): string {
  return ACTION_MAP[action] ?? action
}

/**
 * 操作人显示（P5 步骤 3 收口）。
 *
 * `operator_id = 0` 是**系统**（分诊写回、超时释放这类无人触发的动作），它没有对应的 `t_user` 行，
 * 所以 `operatorName` 为 null——旧写法会退化成 **"用户0"**，让人以为有个 ID 为 0 的用户，也埋没了
 * "这是系统干的"这条信息（例如 AI 分诊改过类型，本该一眼看出是机器改的）。
 */
function operatorLabel(log: WorkOrderLogVO): string {
  if (log.operatorName) return log.operatorName
  return log.operatorId === 0 ? '系统' : `用户${log.operatorId}`
}

/** 状态中文映射 */
function statusLabel(status: string | null | undefined): string {
  if (!status) return '—'
  return STATUS_MAP[status]?.label ?? status
}

/** 格式化时间 */
function fmtTime(val: string | undefined | null): string {
  if (!val) return '-'
  try {
    return new Date(val).toLocaleString('zh-CN', { hour12: false })
  } catch {
    return val
  }
}
</script>

<template>
  <el-card shadow="never" class="log-panel">
    <template #header>
      <span class="panel-title">操作日志</span>
    </template>

    <el-empty v-if="sortedLogs.length === 0" description="暂无操作日志" />

    <el-timeline v-else>
      <el-timeline-item
        v-for="log in sortedLogs"
        :key="log.id"
        :timestamp="fmtTime(log.createdAt)"
        placement="top"
        :color="log.id === latestId ? 'var(--el-color-primary)' : 'var(--el-color-info)'"
        :class="{ 'latest-log': log.id === latestId }"
      >
        <div class="log-item">
          <div class="log-action">
            <el-tag type="primary" size="small" disable-transitions>
              {{ actionLabel(log.action) }}
            </el-tag>
            <span class="log-operator">
              操作人：{{ operatorLabel(log) }}
            </span>
          </div>

          <div v-if="log.oldStatus || log.newStatus" class="log-status-change">
            <template v-if="log.oldStatus">
              <span class="status-from">{{ statusLabel(log.oldStatus) }}</span>
              <span class="status-arrow">→</span>
            </template>
            <span class="status-to">{{ statusLabel(log.newStatus) }}</span>
          </div>

          <div v-if="log.remark" class="log-remark">
            {{ log.remark }}
          </div>
        </div>
      </el-timeline-item>
    </el-timeline>
  </el-card>
</template>

<style scoped>
.log-panel {
  margin-top: 16px;
}

.panel-title {
  font-size: 16px;
  font-weight: 600;
}

.log-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.log-action {
  display: flex;
  align-items: center;
  gap: 8px;
}

.log-operator {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.log-status-change {
  font-size: 13px;
  color: var(--el-text-color-regular);
}

.status-from {
  color: var(--el-text-color-secondary);
}

.status-arrow {
  margin: 0 6px;
  color: var(--el-text-color-secondary);
}

.status-to {
  font-weight: 500;
}

.log-remark {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  padding: 4px 8px;
  background: var(--el-bg-color-page);
  border-radius: 4px;
  border-left: 2px solid var(--el-color-info);
}

.latest-log .log-action {
  font-weight: 600;
}
</style>
