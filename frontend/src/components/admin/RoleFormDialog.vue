<script setup lang="ts">
import { ref, reactive, watch } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { useAdminStore } from '@/stores/admin'
import type { Role } from '@/types/admin'

const props = defineProps<{
  modelValue: boolean
  /** 编辑模式时传入已有角色，创建模式为 null */
  role: Role | null
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'success'): void
}>()

const store = useAdminStore()

const formRef = ref<FormInstance>()
const submitting = ref(false)

/** 表单数据 */
const form = reactive({
  roleCode: '',
  roleName: '',
  remark: '',
})

/** 是否为编辑模式 */
const isEdit = ref(false)

/** 校验规则 */
const rules: FormRules = {
  roleCode: [
    { required: true, message: '角色编码不能为空', trigger: 'blur' },
    { pattern: /^[A-Z][A-Z0-9_]*$/, message: '角色编码须为大写字母开头、仅含大写字母/数字/下划线', trigger: 'blur' },
  ],
  roleName: [
    { required: true, message: '角色名称不能为空', trigger: 'blur' },
  ],
}

/** 关闭弹窗 */
function handleClose(): void {
  emit('update:modelValue', false)
}

/** 弹窗打开时初始化 */
watch(
  () => props.modelValue,
  (visible) => {
    if (!visible) {
      form.roleCode = ''
      form.roleName = ''
      form.remark = ''
      isEdit.value = false
      formRef.value?.resetFields()
      return
    }

    if (props.role) {
      // 编辑模式——预填数据
      isEdit.value = true
      form.roleCode = props.role.roleCode
      form.roleName = props.role.roleName
      form.remark = props.role.remark ?? ''
    } else {
      // 创建模式
      isEdit.value = false
      form.roleCode = ''
      form.roleName = ''
      form.remark = ''
    }
  },
)

/** 确认提交 */
async function handleConfirm(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    if (isEdit.value && props.role) {
      await store.performUpdateRole(props.role.id, {
        roleName: form.roleName,
        remark: form.remark || undefined,
      })
      ElMessage.success('角色更新成功')
    } else {
      await store.performCreateRole({
        roleCode: form.roleCode,
        roleName: form.roleName,
        remark: form.remark || undefined,
      })
      ElMessage.success('角色创建成功')
    }
    emit('update:modelValue', false)
    emit('success')
  } catch {
    // 错误已在拦截器中统一提示
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="isEdit ? '编辑角色' : '新建角色'"
    width="480px"
    :close-on-click-modal="false"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-form
      ref="formRef"
      :model="form"
      :rules="rules"
      label-width="90px"
      :disabled="submitting"
    >
      <el-form-item label="角色编码" prop="roleCode">
        <el-input
          v-model="form.roleCode"
          placeholder="请输入角色编码"
          :disabled="isEdit"
          maxlength="32"
        />
        <template v-if="isEdit" #extra>
          <span class="form-tip">编码创建后不可修改</span>
        </template>
      </el-form-item>

      <el-form-item label="角色名称" prop="roleName">
        <el-input
          v-model="form.roleName"
          placeholder="请输入角色名称"
          maxlength="50"
        />
      </el-form-item>

      <el-form-item label="备注">
        <el-input
          v-model="form.remark"
          type="textarea"
          placeholder="可选：角色说明"
          maxlength="200"
          show-word-limit
          :rows="3"
        />
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="handleClose" :disabled="submitting">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="handleConfirm">
        {{ isEdit ? '保存修改' : '确认创建' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.form-tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
