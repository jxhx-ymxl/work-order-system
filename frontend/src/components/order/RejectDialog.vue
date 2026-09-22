<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Loading } from '@element-plus/icons-vue'
import { getActionToken, rejectOrder } from '@/api/order'
import type { FormInstance, FormRules } from 'element-plus'

const props = defineProps<{
  /** 工单ID */
  orderId: number
  /** 当前驳回次数 */
  rejectCount: number
  /** 最大驳回次数 */
  maxReject: number
}>()

const emit = defineEmits<{
  (e: 'update:visible', value: boolean): void
  (e: 'success'): void
}>()

const visible = defineModel<boolean>('visible', { default: false })

const formRef = ref<FormInstance>()
const token = ref('')
const remark = ref('')
const submitting = ref(false)
const tokenLoading = ref(false)
const tokenError = ref('')

const rules: FormRules = {
  remark: [
    { required: true, message: '请输入驳回理由', trigger: 'blur' },
    { max: 500, message: '驳回理由不超过 500 个字符', trigger: 'blur' },
  ],
}

/** 驳回次数提示文字 */
function rejectHint(): string {
  const current = props.rejectCount + 1
  if (current >= props.maxReject) {
    return `第 ${current} 次驳回，达到上限后将升级至管理员处理`
  }
  return `第 ${current} 次驳回，最多 ${props.maxReject} 次`
}

/** 对话框打开时自动获取 Token */
async function fetchToken(): Promise<void> {
  tokenLoading.value = true
  tokenError.value = ''
  token.value = ''
  try {
    token.value = await getActionToken(props.orderId)
  } catch {
    tokenError.value = '获取操作 Token 失败，请稍后重试'
  } finally {
    tokenLoading.value = false
  }
}

/** 监听 visible 变化——打开时获取 Token 并重置表单 */
watch(visible, (newVal) => {
  if (newVal) {
    remark.value = ''
    formRef.value?.resetFields()
    fetchToken()
  }
})

/** 确认驳回 */
async function handleConfirm(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  if (!token.value) {
    ElMessage.warning('操作 Token 尚未就绪，请关闭后重试')
    return
  }

  submitting.value = true
  try {
    await rejectOrder(props.orderId, {
      token: token.value,
      remark: remark.value,
    })
    ElMessage.success('已驳回')
    visible.value = false
    emit('success')
  } catch {
    // 错误消息已在拦截器中处理（如"请勿重复提交"）
    visible.value = false
    // 失败时也通知父组件刷新——状态可能已变化（如他人抢先操作）
    emit('success')
  } finally {
    submitting.value = false
  }
}

/** 取消 */
function handleCancel(): void {
  visible.value = false
}
</script>

<template>
  <el-dialog
    v-model="visible"
    title="驳回工单"
    width="480px"
    :close-on-click-modal="false"
    :close-on-press-escape="!submitting"
    destroy-on-close
  >
    <!-- Token 加载中 -->
    <div v-if="tokenLoading" class="token-loading">
      <el-icon class="is-loading"><Loading /></el-icon>
      <span>正在获取操作 Token...</span>
    </div>

    <!-- Token 获取失败 -->
    <el-result
      v-else-if="tokenError"
      status="error"
      :sub-title="tokenError"
    >
      <template #extra>
        <el-button type="primary" @click="fetchToken">重试</el-button>
        <el-button @click="handleCancel">取消</el-button>
      </template>
    </el-result>

    <!-- 正常表单 -->
    <template v-else>
      <el-alert
        type="warning"
        :title="rejectHint()"
        :closable="false"
        show-icon
        class="reject-alert"
      />

      <el-form
        ref="formRef"
        :model="{ remark }"
        :rules="rules"
        label-width="0"
        class="reject-form"
      >
        <el-form-item prop="remark">
          <el-input
            v-model="remark"
            type="textarea"
            placeholder="请输入驳回理由（如：问题描述不够详细，请补充具体故障现象）"
            :rows="4"
            maxlength="500"
            show-word-limit
          />
        </el-form-item>
      </el-form>
    </template>

    <template #footer>
      <template v-if="!tokenLoading && !tokenError">
        <el-button @click="handleCancel" :disabled="submitting">取消</el-button>
        <el-button type="danger" :loading="submitting" @click="handleConfirm">
          确认驳回
        </el-button>
      </template>
    </template>
  </el-dialog>
</template>

<style scoped>
.token-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 32px 0;
  color: var(--el-text-color-secondary);
}

.reject-alert {
  margin-bottom: 16px;
}

.reject-form {
  margin-top: 4px;
}
</style>
