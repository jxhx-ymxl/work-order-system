<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { register } from '@/api/user'
import { listDeptOptions } from '@/api/dept'
import type { DeptOption } from '@/types/dept'

const router = useRouter()
const registerFormRef = ref()
const loading = ref(false)

const form = reactive({
  username: '',
  password: '',
  confirmPassword: '',
  phone: '',
  deptId: undefined as number | undefined,
})

// ──── 部门下拉（2026-10-08 部门实体化）────

/** 可选的部门（免登录只读 `/api/depts`，只返回启用项） */
const deptOptions = ref<DeptOption[]>([])
const deptLoading = ref(false)

/**
 * 拉部门下拉。**失败不阻塞注册**：拉不到就把下拉禁用并提示"暂不可选"，
 * 用户仍可先注册（部门是选填），事后由管理员分配——这正是本轮要修掉的那个"填 999 也能注册"的旧行为。
 */
onMounted(async () => {
  deptLoading.value = true
  try {
    deptOptions.value = await listDeptOptions()
  } catch {
    deptOptions.value = []
  } finally {
    deptLoading.value = false
  }
})

function validateConfirmPassword(
  _rule: unknown,
  value: string,
  callback: (error?: Error) => void,
) {
  if (value !== form.password) {
    callback(new Error('两次密码输入不一致'))
  } else {
    callback()
  }
}

const rules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 2, max: 50, message: '用户名长度 2-50 位', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 4, message: '密码长度不少于 4 位', trigger: 'blur' },
  ],
  confirmPassword: [
    { required: true, message: '请确认密码', trigger: 'blur' },
    { validator: validateConfirmPassword, trigger: 'blur' },
  ],
}

function handleRegister() {
  registerFormRef.value?.validate(async (valid: boolean) => {
    if (!valid) return
    loading.value = true
    try {
      await register({
        username: form.username,
        password: form.password,
        phone: form.phone || undefined,
        // 只把**数字**放进请求体：el-select 的 clearable 清空后可能给 ''，
        // 原样发出去会被后端当成非法 Long（400），而"不选部门"本该是合法的。
        deptId: typeof form.deptId === 'number' ? form.deptId : undefined,
      })
      ElMessage.success('注册成功，请登录')
      router.push('/login')
    } catch {
      // 错误消息已在 request 拦截器中处理
    } finally {
      loading.value = false
    }
  })
}
</script>

<template>
  <div class="register-wrapper">
    <el-card class="register-card" shadow="always">
      <template #header>
        <h2 class="register-title">用户注册</h2>
      </template>

      <el-form
        ref="registerFormRef"
        :model="form"
        :rules="rules"
        label-width="0"
        size="large"
        @keyup.enter="handleRegister"
      >
        <el-form-item prop="username">
          <el-input v-model="form.username" placeholder="请输入用户名" />
        </el-form-item>

        <el-form-item prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="请输入密码"
            show-password
          />
        </el-form-item>

        <el-form-item prop="confirmPassword">
          <el-input
            v-model="form.confirmPassword"
            type="password"
            placeholder="请再次输入密码"
            show-password
          />
        </el-form-item>

        <el-form-item prop="phone">
          <el-input v-model="form.phone" placeholder="手机号（选填）" />
        </el-form-item>

        <el-form-item prop="deptId">
          <!-- 2026-10-08：原来这里是**手填部门数字**（填 999 能注册成功并把自己孤立），
               现在改成**下拉选择**（选项来自免登录只读的 /api/depts，只含启用项）。
               标题仍放在**输入框上方**而不是左侧：左侧加 label 会让这一行相对其它字段缩进，
               走查判据里"无错位"就保不住了（D105 发现项②的取舍保留）。 -->
          <div class="dept-field">
            <span class="dept-field-title">部门（选填）</span>
            <el-select
              v-model="form.deptId"
              class="dept-input"
              placeholder="请选择部门"
              clearable
              filterable
              :loading="deptLoading"
              :disabled="deptOptions.length === 0"
            >
              <el-option
                v-for="dept in deptOptions"
                :key="dept.id"
                :label="dept.name"
                :value="dept.id"
              />
            </el-select>
            <span v-if="!deptLoading && deptOptions.length === 0" class="dept-field-note">
              部门列表暂不可选，可先不选（注册后由管理员分配）
            </span>
          </div>
        </el-form-item>

        <el-form-item>
          <el-button
            type="primary"
            class="register-btn"
            :loading="loading"
            @click="handleRegister"
          >
            注册
          </el-button>
        </el-form-item>
      </el-form>

      <div class="register-footer">
        <router-link to="/login">已有账号？去登录</router-link>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.register-wrapper {
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--el-bg-color-page);
}

.register-card {
  width: 420px;
}

.register-title {
  text-align: center;
  margin: 0;
  font-size: 22px;
  color: var(--el-color-primary);
}

.register-btn {
  width: 100%;
}

.dept-field {
  width: 100%;
}

.dept-field-title {
  display: block;
  margin-bottom: 6px;
  font-size: 13px;
  line-height: 1;
  color: var(--el-text-color-secondary);
}

.dept-field-note {
  display: block;
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--el-text-color-secondary);
}

.dept-input {
  width: 100%;
}

.register-footer {
  text-align: center;
  font-size: 13px;
}
</style>
