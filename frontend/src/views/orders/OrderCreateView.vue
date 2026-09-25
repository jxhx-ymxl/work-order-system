<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { useOrderStore } from '@/stores/order'
import { ORDER_TYPE_MAP } from '@/types/order'
import type { SubmitOrderReq } from '@/types/order'

const router = useRouter()
const store = useOrderStore()
const formRef = ref<FormInstance>()
const submitting = ref(false)

const form = reactive<SubmitOrderReq>({
  title: '',
  content: '',
  // P5 步骤 3：type / priority **不再是必填**——不选就交给系统的异步分诊（F1-4）。
  // 两者都用 undefined 表示"未选择"，提交时**不放进请求体**（放空串会被当成"用户选了空类型"）。
  type: undefined,
  priority: undefined,
})

const rules: FormRules = {
  title: [
    { required: true, message: '请输入工单标题', trigger: 'blur' },
    { max: 200, message: '标题不超过 200 个字符', trigger: 'blur' },
  ],
  content: [
    { required: true, message: '请输入工单内容', trigger: 'blur' },
  ],
  // 注意：**这里刻意没有 type 规则**。放开必填是 F1-4 的前置条件——
  // 前端强制选类型时，AI 分类永远不会被触发（线上等于该功能未生效）。
}

/** 工单类型下拉选项 */
const typeOptions = Object.entries(ORDER_TYPE_MAP).map(([value, label]) => ({
  value,
  label,
}))

/** 优先级选项（与类型一样可以"不选"= 由系统判断） */
const priorityOptions = [
  { value: 0, label: '普通' },
  { value: 1, label: '紧急' },
]

/** 提交工单 */
async function handleSubmit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    // 只把"用户真的选了"的字段放进请求体：留着空串或默认 0 会让后端认为用户已指定，
    // 从而**整条异步分诊被跳过**（落库的 type=OTHER + priority=0 与"用户选了其他/普通"无法区分）。
    const payload: SubmitOrderReq = {
      title: form.title,
      content: form.content,
    }
    if (form.type) payload.type = form.type
    if (form.priority !== undefined) payload.priority = form.priority

    const result = await store.submitOrder(payload)
    ElMessage.success(`工单提交成功！工单编号: ${result.orderNo}`)
    router.push(`/orders/${result.id}`)
  } catch {
    // 错误消息已在 request 拦截器中处理
  } finally {
    submitting.value = false
  }
}

/** 取消 */
function handleCancel(): void {
  router.push('/orders')
}
</script>

<template>
  <div class="order-create-page">
    <div class="page-header">
      <h2 class="page-title">创建工单</h2>
    </div>

    <el-card shadow="never">
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-width="80px"
        style="max-width: 680px"
      >
        <el-form-item label="标题" prop="title">
          <el-input
            v-model="form.title"
            placeholder="请输入工单标题"
            maxlength="200"
            show-word-limit
          />
        </el-form-item>

        <el-form-item label="内容" prop="content">
          <el-input
            v-model="form.content"
            type="textarea"
            placeholder="请详细描述工单内容"
            :rows="5"
            maxlength="2000"
            show-word-limit
          />
        </el-form-item>

        <el-form-item label="工单类型" prop="type">
          <el-select
            v-model="form.type"
            placeholder="不选则由系统自动判断"
            clearable
            style="width: 200px"
          >
            <el-option
              v-for="opt in typeOptions"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
        </el-form-item>

        <el-form-item label="优先级">
          <el-select
            v-model="form.priority"
            placeholder="不选则由系统自动判断"
            clearable
            style="width: 200px"
          >
            <el-option
              v-for="opt in priorityOptions"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
        </el-form-item>

        <el-form-item>
          <el-alert type="info" :closable="false" show-icon>
            <template #title>
              类型与优先级都可以不选：提交会立即返回，随后由后台的 AI 分类异步补上
              （工单详情里会先显示"分类中"，数秒后变成具体类型，SLA 截止时间也会随之收紧）。
            </template>
          </el-alert>
        </el-form-item>

        <el-form-item>
          <el-button
            type="primary"
            :loading="submitting"
            @click="handleSubmit"
          >
            提交工单
          </el-button>
          <el-button @click="handleCancel">取消</el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
.order-create-page {
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
</style>
