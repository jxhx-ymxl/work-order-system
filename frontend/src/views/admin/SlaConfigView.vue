<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useAdminStore } from '@/stores/admin'
import { useAuthStore } from '@/stores/auth'
import { ORDER_TYPE_MAP } from '@/types/order'
import { ElMessage } from 'element-plus'
import type { SlaConfig } from '@/types/admin'

const adminStore = useAdminStore()
const authStore = useAuthStore()

/** 优先级映射 */
const PRIORITY_MAP: Record<number, { label: string; tagType: undefined | 'danger' }> = {
  0: { label: '普通', tagType: undefined },
  1: { label: '紧急', tagType: 'danger' },
}

/** 权限校验 */
const hasPermission = computed(() => authStore.hasPermission('sla:config:manage'))

// ──── 行内编辑状态 ────
const editingType = ref<string | null>(null)
const editingPriority = ref<number | null>(null)
const editAcceptMinutes = ref(0)
const editFinishMinutes = ref(0)

/** 判断某行是否处于编辑模式 */
function isEditing(type: string, priority: number): boolean {
  return editingType.value === type && editingPriority.value === priority
}

/** 进入编辑模式 */
function startEdit(config: SlaConfig): void {
  editingType.value = config.type
  editingPriority.value = config.priority
  editAcceptMinutes.value = config.acceptMinutes
  editFinishMinutes.value = config.finishMinutes
}

/** 取消编辑 */
function cancelEdit(): void {
  editingType.value = null
  editingPriority.value = null
}

/** 前端校验 */
function validateEdit(): string | null {
  if (editAcceptMinutes.value <= 0 || !Number.isInteger(editAcceptMinutes.value)) {
    return '接单时限必须为正整数'
  }
  if (editFinishMinutes.value <= 0 || !Number.isInteger(editFinishMinutes.value)) {
    return '完成时限必须为正整数'
  }
  if (editFinishMinutes.value < editAcceptMinutes.value) {
    return '完成时限不能小于接单时限'
  }
  return null
}

/** 保存编辑 */
async function saveEdit(): Promise<void> {
  const error = validateEdit()
  if (error) {
    ElMessage.warning(error)
    return
  }
  if (editingType.value === null || editingPriority.value === null) return

  try {
    await adminStore.performUpdateSlaConfig({
      type: editingType.value,
      priority: editingPriority.value,
      acceptMinutes: editAcceptMinutes.value,
      finishMinutes: editFinishMinutes.value,
    })
    ElMessage.success('SLA 配置已更新')
    cancelEdit()
  } catch {
    // 错误由响应拦截器统一处理
  }
}

onMounted(() => {
  if (hasPermission.value) {
    adminStore.fetchSlaConfig()
  }
})
</script>

<template>
  <div class="sla-config-page">
    <div class="page-header">
      <h2>SLA 配置管理</h2>
      <p class="page-desc">
        配置不同工单类型与优先级下的接单时限和完成时限（单位：分钟）
      </p>
    </div>

    <!-- 无权限 -->
    <el-result
      v-if="!hasPermission"
      status="403"
      title="无权限访问"
      sub-title="您没有 SLA 配置管理权限"
    >
      <template #extra>
        <el-button type="primary" @click="$router.push('/dashboard')">返回首页</el-button>
      </template>
    </el-result>

    <!-- 有权限：表格 -->
    <el-table
      v-else
      :data="adminStore.slaConfigs"
      v-loading="adminStore.slaLoading"
      border
      stripe
      style="width: 100%"
      empty-text="暂无 SLA 配置数据"
    >
      <el-table-column label="工单类型" width="150">
        <template #default="{ row }">
          {{ ORDER_TYPE_MAP[row.type] ?? row.type }}
        </template>
      </el-table-column>

      <el-table-column label="优先级" width="120">
        <template #default="{ row }">
          <el-tag :type="PRIORITY_MAP[row.priority]?.tagType">
            {{ PRIORITY_MAP[row.priority]?.label ?? row.priority }}
          </el-tag>
        </template>
      </el-table-column>

      <el-table-column label="接单时限（分钟）" width="200">
        <template #default="{ row }">
          <template v-if="isEditing(row.type, row.priority)">
            <el-input-number
              v-model="editAcceptMinutes"
              :min="1"
              :step="5"
              size="small"
              style="width: 140px"
            />
          </template>
          <template v-else>
            {{ row.acceptMinutes }}
          </template>
        </template>
      </el-table-column>

      <el-table-column label="完成时限（分钟）" width="200">
        <template #default="{ row }">
          <template v-if="isEditing(row.type, row.priority)">
            <el-input-number
              v-model="editFinishMinutes"
              :min="1"
              :step="5"
              size="small"
              style="width: 140px"
            />
          </template>
          <template v-else>
            {{ row.finishMinutes }}
          </template>
        </template>
      </el-table-column>

      <el-table-column label="操作" width="180" fixed="right">
        <template #default="{ row }">
          <template v-if="isEditing(row.type, row.priority)">
            <el-button
              type="primary"
              size="small"
              :loading="adminStore.slaSaving"
              @click="saveEdit"
            >
              保存
            </el-button>
            <el-button
              size="small"
              :disabled="adminStore.slaSaving"
              @click="cancelEdit"
            >
              取消
            </el-button>
          </template>
          <template v-else>
            <el-button
              type="primary"
              size="small"
              link
              :disabled="editingType !== null"
              @click="startEdit(row as SlaConfig)"
            >
              编辑
            </el-button>
          </template>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<style scoped>
.sla-config-page {
  max-width: 960px;
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
</style>
