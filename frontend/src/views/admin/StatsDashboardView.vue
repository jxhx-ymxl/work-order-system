<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useAdminStore } from '@/stores/admin'
import { useAuthStore } from '@/stores/auth'
import { STATUS_MAP } from '@/types/order'

const adminStore = useAdminStore()
const authStore = useAuthStore()

/** 卡片展示顺序 */
const STATUS_ORDER = [
  'PENDING',
  'ACCEPTED',
  'IN_PROGRESS',
  'AWAIT_APPROVAL',
  'CLOSED',
  'RELEASED',
  'ESCALATED_ADMIN',
]

/** 权限 */
const hasDeptStats = computed(() => authStore.hasPermission('order:stats'))
const hasGlobalStats = computed(() => authStore.hasPermission('order:stats:all'))
const hasAnyStats = computed(() => hasDeptStats.value || hasGlobalStats.value)

/** 当前统计范围 */
const scope = ref<'DEPT' | 'ALL'>('DEPT')

/** 可用范围选项 */
interface ScopeOption {
  label: string
  value: 'DEPT' | 'ALL'
  disabled: boolean
}

const scopeOptions = computed<ScopeOption[]>(() => [
  { label: '本部门统计', value: 'DEPT', disabled: !hasDeptStats.value },
  { label: '全局统计', value: 'ALL', disabled: !hasGlobalStats.value },
])

/** 总工单数 */
const totalCount = computed(() =>
  adminStore.statsData.reduce((sum, s) => sum + s.count, 0),
)

/** 状态→数量的映射表 */
const statusCountMap = computed(() => {
  const map: Record<string, number> = {}
  for (const s of adminStore.statsData) {
    map[s.status] = s.count
  }
  return map
})

/** 切换统计范围 */
async function switchScope(newScope: string | number | boolean | undefined): Promise<void> {
  if (newScope !== 'DEPT' && newScope !== 'ALL') return
  scope.value = newScope
  await adminStore.fetchStats(newScope)
}

/** 进度条颜色 */
function progressColor(status: string): string {
  const colorMap: Record<string, string> = {
    PENDING: '#909399',
    ACCEPTED: '#409eff',
    IN_PROGRESS: '#e6a23c',
    AWAIT_APPROVAL: '#e6a23c',
    CLOSED: '#67c23a',
    RELEASED: '#f56c6c',
    ESCALATED_ADMIN: '#f56c6c',
  }
  return colorMap[status] ?? '#409eff'
}

/** 格式化百分比 */
function formatPercent(count: number): string {
  if (totalCount.value === 0) return '0'
  return ((count / totalCount.value) * 100).toFixed(1)
}

onMounted(() => {
  if (!hasAnyStats.value) return
  // 若仅有全局权限则默认选全局
  if (hasGlobalStats.value && !hasDeptStats.value) {
    scope.value = 'ALL'
  }
  adminStore.fetchStats(scope.value)
})
</script>

<template>
  <div class="stats-dashboard">
    <div class="page-header">
      <h2>工单统计仪表盘</h2>
      <p class="page-desc">按状态分布查看工单数量与占比</p>
    </div>

    <!-- 无权限 -->
    <el-result
      v-if="!hasAnyStats"
      status="403"
      title="无权限访问"
      sub-title="您没有查看工单统计的权限"
    >
      <template #extra>
        <el-button type="primary" @click="$router.push('/dashboard')">返回首页</el-button>
      </template>
    </el-result>

    <template v-else>
      <!-- 范围切换 + 总数 -->
      <div class="stats-toolbar">
        <el-radio-group
          :model-value="scope"
          size="default"
          @change="switchScope"
        >
          <el-radio-button
            v-for="opt in scopeOptions"
            :key="opt.value"
            :value="opt.value"
            :disabled="opt.disabled"
          >
            {{ opt.label }}
          </el-radio-button>
        </el-radio-group>

        <div class="total-summary">
          共计 <strong>{{ totalCount }}</strong> 个工单
        </div>
      </div>

      <!-- 统计卡片网格 -->
      <div v-loading="adminStore.statsLoading" class="stats-grid">
        <div
          v-for="status in STATUS_ORDER"
          :key="status"
          class="stat-card"
        >
          <div class="stat-card-header">
            <el-tag :type="STATUS_MAP[status]?.color ?? 'info'" size="default">
              {{ STATUS_MAP[status]?.label ?? status }}
            </el-tag>
          </div>

          <div class="stat-card-count">
            {{ statusCountMap[status] ?? 0 }}
          </div>

          <div class="stat-card-progress">
            <el-progress
              :percentage="Number(formatPercent(statusCountMap[status] ?? 0))"
              :color="progressColor(status)"
              :stroke-width="8"
              :show-text="false"
            />
          </div>

          <div class="stat-card-percent">
            {{ formatPercent(statusCountMap[status] ?? 0) }}%
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.stats-dashboard {
  max-width: 1200px;
}

.page-header {
  margin-bottom: 24px;
}

.page-header h2 {
  margin: 0 0 8px 0;
  font-size: 20px;
  font-weight: 600;
}

.page-desc {
  margin: 0;
  color: var(--el-text-color-secondary);
  font-size: 14px;
}

.stats-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 24px;
  flex-wrap: wrap;
  gap: 16px;
}

.total-summary {
  font-size: 16px;
  color: var(--el-text-color-regular);
}

.total-summary strong {
  font-size: 24px;
  color: var(--el-color-primary);
  margin: 0 2px;
}

.stats-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 16px;
}

.stat-card {
  background: #fff;
  border-radius: 8px;
  padding: 20px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.08);
  transition: box-shadow 0.2s;
}

.stat-card:hover {
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.12);
}

.stat-card-header {
  margin-bottom: 12px;
}

.stat-card-count {
  font-size: 36px;
  font-weight: 700;
  line-height: 1.2;
  color: var(--el-text-color-primary);
  margin-bottom: 12px;
}

.stat-card-progress {
  margin-bottom: 6px;
}

.stat-card-percent {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
</style>
