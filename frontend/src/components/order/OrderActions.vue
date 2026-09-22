<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { useOrderStore } from '@/stores/order'
import { listUsers } from '@/api/admin'
import { TERMINAL_STATUSES } from '@/types/order'
import RejectDialog from './RejectDialog.vue'
import type { WorkOrderVO } from '@/types/order'
import type { UserDetailVO } from '@/types/admin'

const props = defineProps<{
  order: WorkOrderVO
}>()

const emit = defineEmits<{
  (e: 'action-done'): void
}>()

const auth = useAuthStore()
const store = useOrderStore()

// ──── identity checks ────
const currentUserId = computed(() => auth.userInfo.id)
const isSubmitter = computed(() => props.order.submitterId === currentUserId.value)
const isAssignee = computed(() => props.order.assigneeId === currentUserId.value)

// ──── button visibility (triple control: status × permission × identity) ────

const showAccept = computed(() =>
  props.order.status === 'PENDING' && auth.hasPermission('order:accept'),
)

const showAssign = computed(() =>
  props.order.status === 'PENDING' && auth.hasPermission('order:assign'),
)

const showStart = computed(() =>
  props.order.status === 'ACCEPTED' && isAssignee.value,
)

const showComplete = computed(() =>
  props.order.status === 'IN_PROGRESS' && isAssignee.value,
)

const showApprove = computed(() =>
  props.order.status === 'AWAIT_APPROVAL' && isSubmitter.value,
)

const showReject = computed(() =>
  props.order.status === 'AWAIT_APPROVAL' &&
  isSubmitter.value &&
  auth.hasPermission('order:reject'),
)

/** 是否终态——不显示任何操作按钮 */
const isTerminal = computed(() => TERMINAL_STATUSES.has(props.order.status))

/** 是否有任何可见按钮 */
const hasAnyAction = computed(() =>
  !isTerminal.value && (
    showAccept.value ||
    showAssign.value ||
    showStart.value ||
    showComplete.value ||
    showApprove.value ||
    showReject.value
  ),
)

// ──── 操作 loading 状态 ────
const actionLoading = ref('')

// ──── 分配弹窗 ────
const assignVisible = ref(false)
const assignUsers = ref<UserDetailVO[]>([])
const assigneeId = ref<number | null>(null)
const assignUsersLoading = ref(false)
const assignSubmitting = ref(false)

/** 打开分配弹窗时加载用户列表 */
watch(assignVisible, (newVal) => {
  if (newVal) {
    assigneeId.value = null
    fetchAssignUsers()
  }
})

async function fetchAssignUsers(): Promise<void> {
  assignUsersLoading.value = true
  try {
    const result = await listUsers({ page: 1, size: 100 })
    assignUsers.value = result.records ?? []
  } catch {
    // 错误已在拦截器处理
  } finally {
    assignUsersLoading.value = false
  }
}

/** 确认分配 */
async function handleAssignConfirm(): Promise<void> {
  if (assigneeId.value === null) {
    ElMessage.warning('请选择处理人')
    return
  }
  assignSubmitting.value = true
  try {
    await store.performAssign(props.order.id, { assigneeId: assigneeId.value })
    ElMessage.success('分配成功')
    assignVisible.value = false
    emit('action-done')
  } catch {
    // 错误已在拦截器处理
  } finally {
    assignSubmitting.value = false
  }
}

// ──── 驳回弹窗 ────
const rejectVisible = ref(false)

// ──── 通用操作处理 ────

/** 抢单 */
async function handleAccept(): Promise<void> {
  try {
    await ElMessageBox.confirm('确认抢单？', '抢单', {
      confirmButtonText: '确认抢单',
      cancelButtonText: '取消',
      type: 'info',
    })
  } catch {
    return // 用户取消
  }

  actionLoading.value = 'accept'
  try {
    await store.performAccept(props.order.id)
    ElMessage.success('抢单成功')
    emit('action-done')
  } catch {
    // 错误已在拦截器处理（如"工单已被抢走"）
  } finally {
    actionLoading.value = ''
  }
}

/** 开始处理 */
async function handleStart(): Promise<void> {
  try {
    await ElMessageBox.confirm('确认开始处理此工单？', '开始处理', {
      confirmButtonText: '确认',
      cancelButtonText: '取消',
      type: 'info',
    })
  } catch {
    return
  }

  actionLoading.value = 'start'
  try {
    await store.performStart(props.order.id)
    ElMessage.success('已开始处理')
    emit('action-done')
  } catch {
    // 错误已在拦截器处理
  } finally {
    actionLoading.value = ''
  }
}

/** 提交验收 */
async function handleComplete(): Promise<void> {
  try {
    await ElMessageBox.confirm('确认提交验收？提交后将由工单提交人进行审核。', '提交验收', {
      confirmButtonText: '确认提交',
      cancelButtonText: '取消',
      type: 'info',
    })
  } catch {
    return
  }

  actionLoading.value = 'complete'
  try {
    await store.performComplete(props.order.id)
    ElMessage.success('已提交验收')
    emit('action-done')
  } catch {
    // 错误已在拦截器处理
  } finally {
    actionLoading.value = ''
  }
}

/** 验收通过 */
async function handleApprove(): Promise<void> {
  try {
    await ElMessageBox.confirm('确认验收通过？工单将关闭。', '验收通过', {
      confirmButtonText: '确认通过',
      cancelButtonText: '取消',
      type: 'success',
    })
  } catch {
    return
  }

  actionLoading.value = 'approve'
  try {
    await store.performApprove(props.order.id)
    ElMessage.success('验收通过，工单已关闭')
    emit('action-done')
  } catch {
    // 错误已在拦截器处理
  } finally {
    actionLoading.value = ''
  }
}

/** 驳回成功回调（由 RejectDialog 触发） */
function onRejectSuccess(): void {
  emit('action-done')
}
</script>

<template>
  <div v-if="hasAnyAction" class="order-actions">
    <el-card shadow="never">
      <template #header>
        <span class="actions-title">工单操作</span>
      </template>

      <div class="actions-group">
        <!-- PENDING: 抢单 -->
        <el-button
          v-if="showAccept"
          type="primary"
          :loading="actionLoading === 'accept'"
          :disabled="!!actionLoading"
          @click="handleAccept"
        >
          抢单
        </el-button>

        <!-- PENDING: 分配（管理员） -->
        <el-button
          v-if="showAssign"
          type="primary"
          :disabled="!!actionLoading"
          @click="assignVisible = true"
        >
          分配
        </el-button>

        <!-- ACCEPTED: 开始处理 -->
        <el-button
          v-if="showStart"
          type="primary"
          :loading="actionLoading === 'start'"
          :disabled="!!actionLoading"
          @click="handleStart"
        >
          开始处理
        </el-button>

        <!-- IN_PROGRESS: 提交验收 -->
        <el-button
          v-if="showComplete"
          type="success"
          :loading="actionLoading === 'complete'"
          :disabled="!!actionLoading"
          @click="handleComplete"
        >
          提交验收
        </el-button>

        <!-- AWAIT_APPROVAL: 验收通过 -->
        <el-button
          v-if="showApprove"
          type="success"
          :loading="actionLoading === 'approve'"
          :disabled="!!actionLoading"
          @click="handleApprove"
        >
          验收通过
        </el-button>

        <!-- AWAIT_APPROVAL: 驳回 -->
        <el-button
          v-if="showReject"
          type="danger"
          :disabled="!!actionLoading"
          @click="rejectVisible = true"
        >
          驳回
        </el-button>
      </div>
    </el-card>

    <!-- 分配弹窗 -->
    <el-dialog
      v-model="assignVisible"
      title="分配工单"
      width="480px"
      :close-on-click-modal="false"
      destroy-on-close
    >
      <el-form label-width="80px">
        <el-form-item label="处理人">
          <el-select
            v-model="assigneeId"
            placeholder="请选择处理人"
            filterable
            :loading="assignUsersLoading"
            style="width: 100%"
          >
            <el-option
              v-for="user in assignUsers"
              :key="user.id"
              :label="`${user.username}${user.phone ? ' (' + user.phone + ')' : ''} — 部门${user.deptId ?? '—'}`"
              :value="user.id"
            />
          </el-select>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="assignVisible = false" :disabled="assignSubmitting">取消</el-button>
        <el-button
          type="primary"
          :loading="assignSubmitting"
          @click="handleAssignConfirm"
        >
          确认分配
        </el-button>
      </template>
    </el-dialog>

    <!-- 驳回弹窗 (Issue #10: Token 防重) -->
    <RejectDialog
      v-model:visible="rejectVisible"
      :order-id="order.id"
      :reject-count="order.rejectCount"
      :max-reject="order.maxReject"
      @success="onRejectSuccess"
    />
  </div>
</template>

<style scoped>
.order-actions {
  margin-bottom: 16px;
}

.actions-title {
  font-size: 16px;
  font-weight: 600;
}

.actions-group {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}
</style>
