<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { register } from '@/api/user'

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
        deptId: form.deptId,
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
          <el-input-number
            v-model="form.deptId"
            :min="1"
            placeholder="部门ID（选填）"
            class="dept-input"
            controls-position="right"
          />
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

.dept-input {
  width: 100%;
}

.register-footer {
  text-align: center;
  font-size: 13px;
}
</style>
