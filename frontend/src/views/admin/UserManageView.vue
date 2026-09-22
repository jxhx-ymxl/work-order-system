<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { Setting } from '@element-plus/icons-vue'
import { useAdminStore } from '@/stores/admin'
import type { UserDetailVO } from '@/types/admin'
import UserRoleDialog from '@/components/admin/UserRoleDialog.vue'

/** 分页尺寸选项 */
const pageSizes = [10, 20, 50, 100]

/** 角色分配弹窗状态 */
const roleDialogVisible = ref(false)
const selectedUserId = ref(0)
const selectedUsername = ref('')

const store = useAdminStore()

/** 页面挂载时加载用户列表 */
onMounted(() => {
  store.fetchUsers()
})

/** 搜索 */
function handleSearch(): void {
  store.pagination.current = 1
  store.fetchUsers()
}

/** 重置 */
function handleReset(): void {
  store.resetFilters()
  deptIdProxy.value = ''
  store.pagination.current = 1
  store.fetchUsers()
}

/** 分页变化 */
function handlePageChange(page: number): void {
  store.fetchUsers(page)
}

/** 每页条数变化 */
function handleSizeChange(size: number): void {
  store.pagination.current = 1
  store.fetchUsers(1, size)
}

/** 打开角色分配弹窗 */
function openRoleDialog(row: UserDetailVO): void {
  selectedUserId.value = row.id
  selectedUsername.value = row.username
  roleDialogVisible.value = true
}

/** 角色分配成功后刷新表格 */
function onRoleAssigned(): void {
  store.fetchUsers()
}

/** 状态标签 */
function statusTagType(status: number): 'success' | 'danger' {
  return status === 1 ? 'success' : 'danger'
}

function statusLabel(status: number): string {
  return status === 1 ? '启用' : '禁用'
}

/** deptId 筛选输入——手动解析为 number 或 null */
function onDeptIdInput(value: string): void {
  const trimmed = value.trim()
  if (trimmed === '') {
    store.filters.deptId = null
  } else {
    const num = Number(trimmed)
    store.filters.deptId = Number.isNaN(num) ? null : num
  }
}

/** 用于 el-input 双向绑定的 deptId 字符串代理 */
const deptIdProxy = ref('')
</script>

<template>
  <div class="user-manage-page">
    <!-- 页面头部 -->
    <div class="page-header">
      <h2 class="page-title">用户管理</h2>
    </div>

    <!-- 筛选栏 -->
    <div class="filter-bar">
      <el-input
        v-model="store.filters.username"
        placeholder="用户名搜索"
        clearable
        style="width: 200px"
        @keyup.enter="handleSearch"
      />

      <el-input
        v-model="deptIdProxy"
        placeholder="部门 ID"
        clearable
        style="width: 160px"
        @input="onDeptIdInput"
        @keyup.enter="handleSearch"
      />

      <el-button type="primary" @click="handleSearch">搜索</el-button>
      <el-button @click="handleReset">重置</el-button>
    </div>

    <!-- 表格区域 -->
    <div v-loading="store.loading" class="table-area">
      <el-table
        :data="store.users"
        stripe
        style="width: 100%"
      >
        <el-table-column prop="id" label="ID" width="80" />

        <el-table-column prop="username" label="用户名" min-width="120" show-overflow-tooltip />

        <el-table-column prop="phone" label="手机号" width="140">
          <template #default="{ row }">
            {{ (row as UserDetailVO).phone || '-' }}
          </template>
        </el-table-column>

        <el-table-column label="部门 ID" width="100">
          <template #default="{ row }">
            {{ (row as UserDetailVO).deptId ?? '-' }}
          </template>
        </el-table-column>

        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag
              :type="statusTagType((row as UserDetailVO).status)"
              size="small"
              disable-transitions
            >
              {{ statusLabel((row as UserDetailVO).status) }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="角色" min-width="180">
          <template #default="{ row }">
            <el-tag
              v-for="role in (row as UserDetailVO).roles"
              :key="role.roleCode"
              size="small"
              type="primary"
              class="role-tag"
            >
              {{ role.roleName }}
            </el-tag>
            <span v-if="(row as UserDetailVO).roles?.length === 0" class="text-muted">
              未分配
            </span>
          </template>
        </el-table-column>

        <el-table-column label="创建时间" width="170">
          <template #default="{ row }">
            {{ (row as UserDetailVO).createdAt ?? '-' }}
          </template>
        </el-table-column>

        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button
              type="primary"
              link
              size="small"
              @click="openRoleDialog(row as UserDetailVO)"
            >
              <el-icon><Setting /></el-icon>
              分配角色
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 空状态 -->
      <el-empty
        v-if="!store.loading && store.users.length === 0"
        description="暂无用户数据"
      />

      <!-- 分页 -->
      <div v-if="store.pagination.total > 0" class="pagination-wrap">
        <el-pagination
          v-model:current-page="store.pagination.current"
          v-model:page-size="store.pagination.size"
          :page-sizes="pageSizes"
          :total="store.pagination.total"
          layout="total, sizes, prev, pager, next, jumper"
          background
          @current-change="handlePageChange"
          @size-change="handleSizeChange"
        />
      </div>
    </div>

    <!-- 角色分配弹窗 -->
    <UserRoleDialog
      v-model="roleDialogVisible"
      :user-id="selectedUserId"
      :username="selectedUsername"
      @success="onRoleAssigned"
    />
  </div>
</template>

<style scoped>
.user-manage-page {
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

.filter-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.table-area {
  background: var(--el-bg-color);
  border-radius: 4px;
  min-height: 200px;
}

.pagination-wrap {
  display: flex;
  justify-content: flex-end;
  padding: 16px 0 0;
}

.role-tag {
  margin-right: 4px;
  margin-bottom: 2px;
}

.text-muted {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
</style>
