<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft } from '@element-plus/icons-vue'
import { useOrderStore } from '@/stores/order'
import OrderInfoPanel from '@/components/order/OrderInfoPanel.vue'
import OrderActions from '@/components/order/OrderActions.vue'
import OrderLogTimeline from '@/components/order/OrderLogTimeline.vue'

const route = useRoute()
const router = useRouter()
const store = useOrderStore()

const notFound = ref(false)

/** 解析路由参数中的工单 ID */
function parseOrderId(): number | null {
  const idStr = route.params.id as string
  const id = Number(idStr)
  return Number.isNaN(id) || id <= 0 ? null : id
}

/** 加载工单详情 */
async function loadDetail(): Promise<void> {
  const id = parseOrderId()
  if (id === null) {
    notFound.value = true
    return
  }

  notFound.value = false
  try {
    await store.fetchOrderDetail(id)
    // 如果 API 返回空数据
    if (!store.currentOrder) {
      notFound.value = true
    }
  } catch {
    // 404 错误的特殊处理——code 404 时拦截器可能已显示错误
    notFound.value = true
  }
}

/** 操作完成后刷新 */
async function handleActionDone(): Promise<void> {
  await loadDetail()
}

/** 返回工单列表 */
function goBack(): void {
  router.push('/orders')
}

onMounted(() => {
  loadDetail()
})

// 监听路由参数变化（浏览器前进/后退时用 afterEach 兜底）
const removeAfterEach = router.afterEach((to) => {
  if (to.name === 'OrderDetail' && to.params.id !== route.params.id) {
    loadDetail()
  }
})

onUnmounted(() => {
  removeAfterEach()
  store.clearDetail()
})
</script>

<template>
  <div class="order-detail-page">
    <!-- 404 状态 -->
    <el-result
      v-if="notFound"
      status="404"
      title="工单不存在"
      sub-title="请检查工单编号是否正确，或该工单已被删除"
    >
      <template #extra>
        <el-button type="primary" @click="goBack">返回工单列表</el-button>
      </template>
    </el-result>

    <!-- 正常详情 -->
    <template v-else>
      <!-- 顶部导航栏 -->
      <div class="detail-header">
        <el-button :icon="ArrowLeft" @click="goBack">返回列表</el-button>
        <div class="header-right">
          <span class="order-no-label">
            工单编号：<strong>{{ store.currentOrder?.orderNo ?? '加载中...' }}</strong>
          </span>
        </div>
      </div>

      <!-- 加载状态 -->
      <div v-if="store.detailLoading && !store.currentOrder" class="detail-loading">
        <el-skeleton :rows="8" animated />
      </div>

      <!-- 内容区域 -->
      <template v-else-if="store.currentOrder">
        <!-- ESCALATED_ADMIN 全局警告 -->
        <el-alert
          v-if="store.currentOrder.status === 'ESCALATED_ADMIN'"
          type="error"
          :closable="false"
          show-icon
          class="escalated-alert"
        >
          <template #title>
            <strong>此工单驳回次数已达上限（{{ store.currentOrder.rejectCount }}/{{ store.currentOrder.maxReject }}），已升级至管理员处理。</strong>
            请管理员联系提交人与处理人协商解决。
          </template>
        </el-alert>

        <!-- 工单信息面板 -->
        <OrderInfoPanel :order="store.currentOrder" />

        <!-- 操作按钮组（Issue #9: 条件渲染 + 权限控制） -->
        <OrderActions :order="store.currentOrder" @action-done="handleActionDone" />

        <!-- 操作日志时间线 -->
        <OrderLogTimeline :logs="store.currentLogs" />
      </template>
    </template>
  </div>
</template>

<style scoped>
.order-detail-page {
  display: flex;
  flex-direction: column;
  gap: 0;
  max-width: 960px;
  margin: 0 auto;
}

.detail-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.order-no-label {
  font-size: 14px;
  color: var(--el-text-color-secondary);
}

.order-no-label strong {
  color: var(--el-text-color-primary);
}

.detail-loading {
  background: var(--el-bg-color);
  border-radius: 4px;
  padding: 20px;
}

.escalated-alert {
  margin-bottom: 16px;
}
</style>
