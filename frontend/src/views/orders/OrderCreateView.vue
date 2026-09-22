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
  type: '',
  priority: 0,
})

const rules: FormRules = {
  title: [
    { required: true, message: '请输入工单标题', trigger: 'blur' },
    { max: 200, message: '标题不超过 200 个字符', trigger: 'blur' },
  ],
  content: [
    { required: true, message: '请输入工单内容', trigger: 'blur' },
  ],
  type: [
    { required: true, message: '请选择工单类型', trigger: 'change' },
  ],
}

/** 工单类型下拉选项 */
const typeOptions = Object.entries(ORDER_TYPE_MAP).map(([value, label]) => ({
  value,
  label,
}))

/** 提交工单 */
async function handleSubmit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    const result = await store.submitOrder({
      title: form.title,
      content: form.content,
      type: form.type,
      priority: form.priority,
    })
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
            placeholder="请选择工单类型"
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
          <el-radio-group v-model="form.priority">
            <el-radio :value="0">普通</el-radio>
            <el-radio :value="1">紧急</el-radio>
          </el-radio-group>
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
