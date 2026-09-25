<script setup lang="ts">
import { computed } from 'vue'
import { WarningFilled } from '@element-plus/icons-vue'
import type { WorkOrderVO } from '@/types/order'
import { STATUS_MAP, ORDER_TYPE_MAP } from '@/types/order'
import { TRIAGE_STATUS_MAP } from '@/types/order'

const props = defineProps<{
  order: WorkOrderVO
}>()

/** 类型中文名 */
const typeLabel = computed(() => ORDER_TYPE_MAP[props.order.type] ?? props.order.type)

/**
 * 分诊状态（P5 步骤 3）：**只有后端真的返回了 `triageStatus` 才渲染**。
 *
 * 为什么不"猜"：兜底落库的 `OTHER/普通` 与"用户自己选了其他/普通"在数据上完全一样，
 * 前端若靠 type/priority 是否变化来推断，会在"AI 也判 OTHER/普通"时**永久显示分类中**（假状态）。
 * 详见 docs/DECISIONS.md D61。
 */
const triageInfo = computed(() =>
  props.order.triageStatus ? TRIAGE_STATUS_MAP[props.order.triageStatus] : null,
)

/** 分类中：类型/优先级这一格显示"分类中"而不是兜底值（否则用户以为系统判错了） */
const triagePending = computed(() => props.order.triageStatus === 'PENDING')

/** 优先级 */
const priorityLabel = computed(() => (props.order.priority === 1 ? '紧急' : '普通'))
const priorityColor = computed(() => (props.order.priority === 1 ? 'danger' : 'info'))

/** 状态 */
const statusInfo = computed(() => STATUS_MAP[props.order.status] ?? { label: props.order.status, color: 'info' })

/** 驳回次数是否异常 */
const rejectWarn = computed(() => props.order.rejectCount > 0)

/** SLA 是否超时（仅对未完结状态判断） */
const slaExpired = computed(() => {
  if (!props.order.slaDeadline) return false
  const terminalStatuses = new Set(['CLOSED', 'RELEASED', 'ESCALATED_ADMIN'])
  if (terminalStatuses.has(props.order.status)) return false
  return new Date(props.order.slaDeadline).getTime() < Date.now()
})

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
  <el-card shadow="never" class="info-panel">
    <template #header>
      <div class="panel-header">
        <span class="panel-title">工单信息</span>
        <el-tag
          v-if="order.status === 'ESCALATED_ADMIN'"
          type="danger"
          size="large"
        >
          <el-icon><WarningFilled /></el-icon>
          此工单驳回次数已达上限，已升级至管理员处理
        </el-tag>
      </div>
    </template>

    <el-descriptions :column="2" border>
      <el-descriptions-item label="工单编号" :span="2">
        <strong>{{ order.orderNo }}</strong>
      </el-descriptions-item>

      <el-descriptions-item label="标题" :span="2">
        {{ order.title }}
      </el-descriptions-item>

      <el-descriptions-item label="内容" :span="2">
        <div class="content-text">{{ order.content }}</div>
      </el-descriptions-item>

      <el-descriptions-item label="工单类型">
        <template v-if="triagePending">
          <el-tag :type="triageInfo?.color ?? 'info'" size="small" disable-transitions>
            {{ triageInfo?.label }}
          </el-tag>
          <span class="triage-hint">系统正在自动判断类型与优先级，稍后刷新即可</span>
        </template>
        <template v-else>
          {{ typeLabel }}
          <el-tag
            v-if="triageInfo && order.triageStatus === 'FAILED'"
            :type="triageInfo.color"
            size="small"
            class="triage-hint"
            disable-transitions
          >
            {{ triageInfo.label }}（当前为兜底值）
          </el-tag>
        </template>
      </el-descriptions-item>

      <el-descriptions-item label="优先级">
        <el-tag :type="priorityColor" size="small" disable-transitions>
          {{ priorityLabel }}
        </el-tag>
      </el-descriptions-item>

      <el-descriptions-item label="当前状态">
        <el-tag :type="statusInfo.color" size="small" disable-transitions>
          {{ statusInfo.label }}
        </el-tag>
      </el-descriptions-item>

      <el-descriptions-item label="提交人ID">
        {{ order.submitterId }}
      </el-descriptions-item>

      <el-descriptions-item label="处理人ID">
        {{ order.assigneeId ?? '—' }}
      </el-descriptions-item>

      <el-descriptions-item label="驳回次数">
        <span :class="{ 'reject-warn': rejectWarn }">
          {{ order.rejectCount }} / {{ order.maxReject }}
        </span>
      </el-descriptions-item>

      <el-descriptions-item label="SLA 截止时间">
        <span :class="{ 'sla-expired': slaExpired }">
          {{ fmtTime(order.slaDeadline) }}
          <el-tag v-if="slaExpired" type="danger" size="small" class="sla-tag">已超时</el-tag>
        </span>
      </el-descriptions-item>

      <el-descriptions-item label="创建时间">
        {{ fmtTime(order.createdAt) }}
      </el-descriptions-item>

      <el-descriptions-item label="更新时间">
        {{ fmtTime(order.updatedAt) }}
      </el-descriptions-item>
    </el-descriptions>
  </el-card>
</template>

<style scoped>
.info-panel {
  margin-bottom: 16px;
}

.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px;
}

.panel-title {
  font-size: 16px;
  font-weight: 600;
}

.content-text {
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.6;
}

.reject-warn {
  color: var(--el-color-danger);
  font-weight: 600;
}

.sla-expired {
  color: var(--el-color-danger);
  font-weight: 600;
}

.sla-tag {
  margin-left: 8px;
}

.triage-hint {
  margin-left: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
