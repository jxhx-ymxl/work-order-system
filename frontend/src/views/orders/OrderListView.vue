<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { Plus } from '@element-plus/icons-vue'
import { useOrderStore } from '@/stores/order'
import { STATUS_MAP, ORDER_TYPE_MAP } from '@/types/order'
import type { WorkOrderVO } from '@/types/order'

const router = useRouter()
const store = useOrderStore()

/** 状态下拉选项 */
const statusOptions = [
  { label: '全部', value: '' },
  { label: '待分配', value: 'PENDING' },
  { label: '已接单', value: 'ACCEPTED' },
  { label: '处理中', value: 'IN_PROGRESS' },
  { label: '待验收', value: 'AWAIT_APPROVAL' },
  { label: '已关闭', value: 'CLOSED' },
  { label: '已释放', value: 'RELEASED' },
  { label: '已升级', value: 'ESCALATED_ADMIN' },
]

/** 分页尺寸选项 */
const pageSizes = [10, 20, 50, 100]

/** 获取类型中文名 */
function typeLabel(type: string): string {
  return ORDER_TYPE_MAP[type] ?? type
}

/** 获取优先级标签颜色 */
function priorityColor(priority: number): 'info' | 'danger' {
  return priority === 1 ? 'danger' : 'info'
}

/** 优先级文本 */
function priorityLabel(priority: number): string {
  return priority === 1 ? '紧急' : '普通'
}

/** 搜索 */
function handleSearch(): void {
  store.pagination.current = 1
  store.fetchOrders()
}

/** 重置 */
function handleReset(): void {
  store.resetFilters()
  store.pagination.current = 1
  store.fetchOrders()
}

/** 分页变化 */
function handlePageChange(page: number): void {
  store.fetchOrders(page)
}

/** 每页条数变化 */
function handleSizeChange(size: number): void {
  store.pagination.current = 1
  store.fetchOrders(1, size)
}

/** 跳转工单详情 */
function goDetail(row: WorkOrderVO): void {
  router.push(`/orders/${row.id}`)
}

/** 跳转创建工单 */
function goCreate(): void {
  router.push('/orders/create')
}

onMounted(() => {
  store.fetchOrders()
})
</script>

<template>
  <div class="order-list-page">
    <!-- 页面头部 -->
    <div class="page-header">
      <h2 class="page-title">工单列表</h2>
      <el-button type="primary" @click="goCreate">
        <el-icon><Plus /></el-icon>
        创建工单
      </el-button>
    </div>

    <!-- 筛选栏 -->
    <div class="filter-bar">
      <el-select
        v-model="store.filters.status"
        placeholder="工单状态"
        clearable
        style="width: 160px"
        @change="handleSearch"
      >
        <el-option
          v-for="opt in statusOptions"
          :key="opt.value"
          :label="opt.label"
          :value="opt.value"
        />
      </el-select>

      <el-input
        v-model="store.filters.orderNo"
        placeholder="输入工单编号搜索"
        clearable
        style="width: 240px"
        @keyup.enter="handleSearch"
      />

      <el-button type="primary" @click="handleSearch">搜索</el-button>
      <el-button @click="handleReset">重置</el-button>
    </div>

    <!-- 表格区域 -->
    <div v-loading="store.loading" class="table-area">
      <el-table
        :data="store.orders"
        stripe
        style="width: 100%"
        @row-click="goDetail"
      >
        <el-table-column label="工单编号" width="200">
          <template #default="{ row }">
            <el-link type="primary" @click.stop="goDetail(row as WorkOrderVO)">
              {{ (row as WorkOrderVO).orderNo }}
            </el-link>
          </template>
        </el-table-column>

        <el-table-column prop="title" label="标题" min-width="180" show-overflow-tooltip />

        <el-table-column label="类型" width="90">
          <template #default="{ row }">
            {{ typeLabel((row as WorkOrderVO).type) }}
          </template>
        </el-table-column>

        <el-table-column label="优先级" width="80">
          <template #default="{ row }">
            <el-tag :type="priorityColor((row as WorkOrderVO).priority)" size="small" disable-transitions>
              {{ priorityLabel((row as WorkOrderVO).priority) }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag
              :type="STATUS_MAP[(row as WorkOrderVO).status]?.color"
              size="small"
              disable-transitions
            >
              {{ STATUS_MAP[(row as WorkOrderVO).status]?.label ?? (row as WorkOrderVO).status }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="SLA截止时间" width="170">
          <template #default="{ row }">
            {{ (row as WorkOrderVO).slaDeadline ?? '-' }}
          </template>
        </el-table-column>

        <el-table-column label="创建时间" width="170">
          <template #default="{ row }">
            {{ (row as WorkOrderVO).createdAt ?? '-' }}
          </template>
        </el-table-column>

        <el-table-column label="操作" width="80" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click.stop="goDetail(row as WorkOrderVO)">
              详情
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 空状态 -->
      <el-empty v-if="!store.loading && store.orders.length === 0" description="暂无工单" />

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
  </div>
</template>

<style scoped>
.order-list-page {
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
  padding: 0;
  min-height: 200px;
}

.pagination-wrap {
  display: flex;
  justify-content: flex-end;
  padding: 16px 0 0;
}
</style>
