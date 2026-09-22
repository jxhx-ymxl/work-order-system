<script setup lang="ts">
import { ref, watch, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getUserDetail } from '@/api/admin'
import { useAdminStore } from '@/stores/admin'
import { useAuthStore } from '@/stores/auth'
import type { Role, UserDetailVO } from '@/types/admin'

const props = defineProps<{
  modelValue: boolean
  userId: number
  username: string
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'success'): void
}>()

const store = useAdminStore()
const auth = useAuthStore()

/** 选中的角色 ID 列表 */
const checkedRoleIds = ref<number[]>([])
/** 弹窗内加载状态 */
const dialogLoading = ref(false)
/** 提交中 */
const submitting = ref(false)
/** 用户详情（含已有角色） */
const userDetail = ref<UserDetailVO | null>(null)

/** 是否正在编辑自己的角色（自我锁定检测） */
const isSelfEdit = computed(() => auth.userId !== null && auth.userId === props.userId)

/** SYS_ADMIN 角色 ID（用于禁用复选框） */
const sysAdminRoleId = computed(() => {
  const role = store.allRoles.find((r: Role) => r.roleCode === 'SYS_ADMIN')
  return role?.id ?? null
})

/** 目标用户当前是否拥有 SYS_ADMIN 角色 */
const targetHasSysAdmin = computed(() => {
  return userDetail.value?.roles?.some((r) => r.roleCode === 'SYS_ADMIN') ?? false
})

/** 选中的新角色是否包含 SYS_ADMIN */
const newRolesIncludeSysAdmin = computed(() => {
  if (sysAdminRoleId.value === null) return false
  return checkedRoleIds.value.includes(sysAdminRoleId.value)
})

/** 关闭弹窗 */
function handleClose(): void {
  emit('update:modelValue', false)
}

/** 弹窗打开时加载数据 */
watch(
  () => props.modelValue,
  async (visible) => {
    if (!visible) {
      checkedRoleIds.value = []
      userDetail.value = null
      submitting.value = false
      return
    }

    dialogLoading.value = true
    try {
      // 并行加载角色列表和用户详情
      const [, detail] = await Promise.all([
        store.fetchAllRoles(),
        getUserDetail(props.userId),
      ])
      userDetail.value = detail
      // 根据用户已有角色匹配 roleCode → roleId，默认勾选
      const existingRoleCodes = new Set(detail.roles?.map((r) => r.roleCode) ?? [])
      checkedRoleIds.value = store.allRoles
        .filter((r: Role) => existingRoleCodes.has(r.roleCode))
        .map((r: Role) => r.id)
    } finally {
      dialogLoading.value = false
    }
  },
)

/** 确认分配 */
async function handleConfirm(): Promise<void> {
  // 体验层防护：自我编辑时阻止移除 SYS_ADMIN
  if (isSelfEdit.value && targetHasSysAdmin.value && !newRolesIncludeSysAdmin.value) {
    ElMessage.warning('不允许移除自己的系统管理员角色。如需移除此角色，请让其他管理员操作。')
    return
  }

  // 体验层防护：移除最后一名超管时二次确认
  if (targetHasSysAdmin.value && !newRolesIncludeSysAdmin.value) {
    try {
      await ElMessageBox.confirm(
        '你正在移除该用户的系统管理员角色。如果这是系统中最后一名管理员，操作将被后端拒绝。确认继续？',
        '警告',
        { confirmButtonText: '确认', cancelButtonText: '取消', type: 'warning' },
      )
    } catch {
      return // 用户取消
    }
  }

  submitting.value = true
  try {
    await store.performAssignRoles(props.userId, checkedRoleIds.value)
    ElMessage.success('角色分配成功')
    emit('update:modelValue', false)
    emit('success')
  } catch {
    // 错误已在拦截器中统一提示
  } finally {
    submitting.value = false
  }
}

/** 弹窗标题 */
const dialogTitle = computed(() => {
  const suffix = isSelfEdit.value ? '（你自己）' : ''
  return `分配角色 - ${props.username}${suffix}`
})
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="dialogTitle"
    width="480px"
    :close-on-click-modal="false"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <!-- 自我编辑警告横幅 -->
    <el-alert
      v-if="isSelfEdit && targetHasSysAdmin"
      title="你正在编辑自己的角色"
      type="warning"
      :closable="false"
      show-icon
      style="margin-bottom: 16px"
    >
      <template #default>
        不允许移除自己的<strong>系统管理员</strong>角色。如需移除此角色，请让其他管理员操作。
      </template>
    </el-alert>

    <div v-loading="dialogLoading" style="min-height: 120px">
      <el-checkbox-group v-model="checkedRoleIds" class="role-checkbox-group">
        <div
          v-for="role in store.allRoles"
          :key="role.id"
          class="role-checkbox-item"
        >
          <el-checkbox
            :label="role.id"
            :value="role.id"
            :disabled="isSelfEdit && role.roleCode === 'SYS_ADMIN' && targetHasSysAdmin"
          >
            <span class="role-name">{{ role.roleName }}</span>
            <el-tag size="small" type="info" class="role-code-tag">
              {{ role.roleCode }}
            </el-tag>
            <el-tooltip
              v-if="isSelfEdit && role.roleCode === 'SYS_ADMIN' && targetHasSysAdmin"
              content="不允许移除自己的系统管理员角色"
              placement="top"
            >
              <span class="self-lock-icon">🔒</span>
            </el-tooltip>
          </el-checkbox>
        </div>
      </el-checkbox-group>

      <el-empty
        v-if="!dialogLoading && store.allRoles.length === 0"
        description="暂无角色数据"
      />
    </div>

    <template #footer>
      <el-button @click="handleClose" :disabled="submitting">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="handleConfirm">
        确认分配
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.role-checkbox-group {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.role-checkbox-item {
  padding: 8px 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;
  transition: border-color 0.2s;
}

.role-checkbox-item:hover {
  border-color: var(--el-color-primary);
}

.role-name {
  font-weight: 500;
  margin-right: 8px;
}

.role-code-tag {
  font-family: monospace;
  font-size: 11px;
}

.self-lock-icon {
  margin-left: 6px;
  font-size: 13px;
  cursor: help;
}
</style>
