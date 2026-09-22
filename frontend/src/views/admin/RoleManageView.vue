<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Edit, Delete, Key } from '@element-plus/icons-vue'
import { useAdminStore } from '@/stores/admin'
import { SEED_ROLES } from '@/types/admin'
import type { Role } from '@/types/admin'
import RoleFormDialog from '@/components/admin/RoleFormDialog.vue'
import PermissionTreeDialog from '@/components/admin/PermissionTreeDialog.vue'

const store = useAdminStore()

/** 角色表单弹窗状态 */
const formDialogVisible = ref(false)
const editingRole = ref<Role | null>(null)

/** 权限分配弹窗状态 */
const permDialogVisible = ref(false)
const permRoleId = ref(0)
const permRoleName = ref('')

/** 页面挂载时加载角色列表 */
onMounted(() => {
  store.fetchRoles()
})

/** 打开新建弹窗 */
function openCreateDialog(): void {
  editingRole.value = null
  formDialogVisible.value = true
}

/** 打开编辑弹窗 */
function openEditDialog(role: Role): void {
  editingRole.value = { ...role }
  formDialogVisible.value = true
}

/** 角色创建/编辑成功后刷新列表 */
function onFormSuccess(): void {
  store.fetchRoles()
}

/** 删除角色 */
async function handleDelete(role: Role): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确定要删除角色「${role.roleName}」吗？删除后不可恢复。`,
      '删除确认',
      {
        confirmButtonText: '确认删除',
        cancelButtonText: '取消',
        type: 'warning',
      },
    )
  } catch {
    // 用户取消
    return
  }

  try {
    await store.performDeleteRole(role.id)
    ElMessage.success('角色删除成功')
    await store.fetchRoles()
  } catch {
    // 错误已在拦截器中统一提示
  }
}

/** 打开权限分配弹窗 */
function openPermDialog(role: Role): void {
  permRoleId.value = role.id
  permRoleName.value = role.roleName
  permDialogVisible.value = true
}

/** 权限分配成功后刷新 */
function onPermSuccess(): void {
  // 权限分配不影响角色列表数据，无需刷新
}

/** 判断是否为种子角色 */
function isSeedRole(roleCode: string): boolean {
  return (SEED_ROLES as readonly string[]).includes(roleCode)
}
</script>

<template>
  <div class="role-manage-page">
    <!-- 页面头部 -->
    <div class="page-header">
      <h2 class="page-title">角色管理</h2>
      <el-button type="primary" @click="openCreateDialog">
        新建角色
      </el-button>
    </div>

    <!-- 表格区域 -->
    <div v-loading="store.rolesLoading" class="table-area">
      <el-table
        :data="store.allRoles"
        stripe
        style="width: 100%"
      >
        <el-table-column prop="id" label="ID" width="80" />

        <el-table-column prop="roleCode" label="角色编码" width="180">
          <template #default="{ row }">
            <el-tag size="small" type="info" class="role-code-col">
              {{ (row as Role).roleCode }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column prop="roleName" label="角色名称" min-width="140" show-overflow-tooltip />

        <el-table-column prop="remark" label="备注" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            {{ (row as Role).remark || '-' }}
          </template>
        </el-table-column>

        <el-table-column label="操作" width="280" fixed="right">
          <template #default="{ row }">
            <el-button
              type="primary"
              link
              size="small"
              @click="openPermDialog(row as Role)"
            >
              <el-icon><Key /></el-icon>
              权限分配
            </el-button>

            <el-button
              type="primary"
              link
              size="small"
              @click="openEditDialog(row as Role)"
            >
              <el-icon><Edit /></el-icon>
              编辑
            </el-button>

            <el-tooltip
              v-if="isSeedRole((row as Role).roleCode)"
              content="种子角色不允许删除"
              placement="top"
            >
              <span class="delete-btn-wrapper">
                <el-button
                  type="danger"
                  link
                  size="small"
                  disabled
                >
                  <el-icon><Delete /></el-icon>
                  删除
                </el-button>
              </span>
            </el-tooltip>

            <el-button
              v-else
              type="danger"
              link
              size="small"
              @click="handleDelete(row as Role)"
            >
              <el-icon><Delete /></el-icon>
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 空状态 -->
      <el-empty
        v-if="!store.rolesLoading && store.allRoles.length === 0"
        description="暂无角色数据"
      />
    </div>

    <!-- 角色表单弹窗（创建/编辑） -->
    <RoleFormDialog
      v-model="formDialogVisible"
      :role="editingRole"
      @success="onFormSuccess"
    />

    <!-- 权限分配弹窗 -->
    <PermissionTreeDialog
      v-model="permDialogVisible"
      :role-id="permRoleId"
      :role-name="permRoleName"
      @success="onPermSuccess"
    />
  </div>
</template>

<style scoped>
.role-manage-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.page-title {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.table-area {
  background: var(--el-bg-color);
  border-radius: 4px;
  min-height: 200px;
}

.role-code-col {
  font-family: monospace;
  font-size: 12px;
}

.delete-btn-wrapper {
  display: inline-block;
  cursor: not-allowed;
}
</style>
