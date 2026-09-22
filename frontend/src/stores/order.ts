import { defineStore } from 'pinia'
import { ref, reactive } from 'vue'
import {
  listOrders,
  submitOrder as submitOrderApi,
  getOrderDetail,
  acceptOrder as acceptOrderApi,
  startOrder as startOrderApi,
  completeOrder as completeOrderApi,
  approveOrder as approveOrderApi,
  rejectOrder as rejectOrderApi,
  assignOrder as assignOrderApi,
} from '@/api/order'
import type {
  WorkOrderVO,
  WorkOrderLogVO,
  SubmitOrderReq,
  PageQuery,
  RejectReq,
  AssignReq,
} from '@/types/order'
import type { PageResult } from '@/types/common'

export const useOrderStore = defineStore('order', () => {
  // ──── List State ────
  const orders = ref<WorkOrderVO[]>([])
  const loading = ref(false)

  const pagination = reactive({
    total: 0,
    pages: 0,
    current: 1,
    size: 20,
  })

  const filters = reactive<Pick<PageQuery, 'status' | 'orderNo'>>({
    status: undefined,
    orderNo: undefined,
  })

  // ──── Detail State ────
  const currentOrder = ref<WorkOrderVO | null>(null)
  const currentLogs = ref<WorkOrderLogVO[]>([])
  const detailLoading = ref(false)
  const actionLoading = ref(false)

  // ──── List Actions ────

  /** 获取工单分页列表 */
  async function fetchOrders(page?: number, size?: number): Promise<void> {
    loading.value = true
    try {
      const query: PageQuery = {
        page: page ?? pagination.current,
        size: size ?? pagination.size,
        status: filters.status || undefined,
        orderNo: filters.orderNo || undefined,
      }
      const result: PageResult<WorkOrderVO> = await listOrders(query)
      orders.value = result.records ?? []
      pagination.total = result.total
      pagination.pages = result.pages
      pagination.current = result.current
      pagination.size = size ?? pagination.size
    } finally {
      loading.value = false
    }
  }

  /** 提交工单 */
  async function submitOrder(data: SubmitOrderReq): Promise<WorkOrderVO> {
    return submitOrderApi(data)
  }

  /** 重置筛选条件并刷新 */
  function resetFilters(): void {
    filters.status = undefined
    filters.orderNo = undefined
  }

  // ──── Detail Actions ────

  /** 获取工单详情（含操作日志） */
  async function fetchOrderDetail(id: number): Promise<void> {
    detailLoading.value = true
    try {
      const detail = await getOrderDetail(id)
      currentOrder.value = detail.order
      currentLogs.value = detail.logs ?? []
    } finally {
      detailLoading.value = false
    }
  }

  /** 抢单 */
  async function performAccept(id: number): Promise<void> {
    actionLoading.value = true
    try {
      await acceptOrderApi(id)
    } finally {
      actionLoading.value = false
    }
  }

  /** 开始处理 */
  async function performStart(id: number): Promise<void> {
    actionLoading.value = true
    try {
      await startOrderApi(id)
    } finally {
      actionLoading.value = false
    }
  }

  /** 提交验收 */
  async function performComplete(id: number): Promise<void> {
    actionLoading.value = true
    try {
      await completeOrderApi(id)
    } finally {
      actionLoading.value = false
    }
  }

  /** 验收通过 */
  async function performApprove(id: number): Promise<void> {
    actionLoading.value = true
    try {
      await approveOrderApi(id)
    } finally {
      actionLoading.value = false
    }
  }

  /** 验收驳回 */
  async function performReject(id: number, data: RejectReq): Promise<void> {
    actionLoading.value = true
    try {
      await rejectOrderApi(id, data)
    } finally {
      actionLoading.value = false
    }
  }

  /** 管理员分配工单 */
  async function performAssign(id: number, data: AssignReq): Promise<void> {
    actionLoading.value = true
    try {
      await assignOrderApi(id, data)
    } finally {
      actionLoading.value = false
    }
  }

  /** 清除详情数据 */
  function clearDetail(): void {
    currentOrder.value = null
    currentLogs.value = []
  }

  return {
    // list state
    orders,
    loading,
    pagination,
    filters,
    // detail state
    currentOrder,
    currentLogs,
    detailLoading,
    actionLoading,
    // list actions
    fetchOrders,
    submitOrder,
    resetFilters,
    // detail actions
    fetchOrderDetail,
    performAccept,
    performStart,
    performComplete,
    performApprove,
    performReject,
    performAssign,
    clearDetail,
  }
})
