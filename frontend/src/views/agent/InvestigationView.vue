<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { investigateAgent } from '@/api/agent'
import { AGENT_STATUS_MAP } from '@/types/agent'
import type { AgentInvestigationReq, AgentInvestigationVO } from '@/types/agent'

const auth = useAuthStore()

const formRef = ref<FormInstance>()
const form = reactive<AgentInvestigationReq>({
  orderNo: '',
  question: '',
})

const rules: FormRules = {
  orderNo: [{ required: true, message: '请输入工单编号', trigger: 'blur' }],
  question: [{ required: true, message: '请输入要调查的问题', trigger: 'blur' }],
}

// ──── 页面状态：一次调查同时只处于其中一种 ────

/** 同步接口请求进行中 = 「调查中」（与工单「分类中」同一做法：Tag + 提示语，不做本地猜测） */
const investigating = ref(false)
/** 功能未启用：后端 controller 未注册 → 404。这是提示，**不是错误** */
const disabled = ref(false)
/** 拿到业务响应但被拒绝（401/403/409/429/503…） */
const rejection = ref<{ code: number; title: string; type: 'error' | 'warning'; message: string } | null>(null)
/** 请求根本没拿到业务响应（90s 超时 / 网络层失败） */
const transport = ref<{ title: string; message: string } | null>(null)
/** 拿到 Result（业务 code=200）：直接渲染后端返回的内容 */
const vo = ref<AgentInvestigationVO | null>(null)

/**
 * 五种拒绝的**业务 code** 分支（`docs/AGENT-PLAN.md` §3.3 / D93 / D95）。
 *
 * 依据是**业务 code**，不是 HTTP 状态——后端这些拒绝一律走 `Result.fail(...)` +
 * `ResponseEntity.ok(...)`，HTTP 层是 200；只有"功能未启用"那一种才是 HTTP 404。
 * 409 ⇔ `AGENT_BUSY`、429 ⇔ `RATE_LIMITED`、503 ⇔ `BUDGET_EXHAUSTED`
 * （这三种拒绝 body 的 `data` 为 null，所以只能按 code 认，不能按 failureCode 认）。
 */
const REJECTION_MAP: Record<
  number,
  { title: string; type: 'error' | 'warning'; message: string }
> = {
  401: {
    title: '登录已过期',
    type: 'error',
    message: '请重新登录后再发起调查。',
  },
  403: {
    title: '无权调查该工单',
    type: 'error',
    message: '仅部门主管（DEPT_ADMIN）可发起调查，且只能调查本部门的工单。',
  },
  409: {
    title: '调查助手繁忙（AGENT_BUSY）',
    type: 'warning',
    message: '并发名额已满，请稍后再试。',
  },
  429: {
    title: '调查过于频繁（RATE_LIMITED）',
    type: 'warning',
    message: '已达本窗口的调用上限，请稍后再试。',
  },
  503: {
    title: '已达全局调用预算上限（BUDGET_EXHAUSTED）',
    type: 'error',
    message: '调查助手暂时不可用，请稍后再试或联系管理员。',
  },
}

/** 重置所有结果态（发起新一次调查时调用，避免上一轮的结果留在屏幕上） */
function resetResult(): void {
  disabled.value = false
  rejection.value = null
  transport.value = null
  vo.value = null
}

/** 发起一次调查 */
async function handleSubmit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  investigating.value = true
  resetResult()
  try {
    const call = await investigateAgent({
      orderNo: form.orderNo.trim(),
      question: form.question.trim(),
    })

    // ① 传输层没拿到业务响应
    if (call.kind === 'disabled') {
      disabled.value = true
      return
    }
    if (call.kind === 'timeout') {
      transport.value = {
        title: '调查超时',
        message:
          '已超过 90 秒未返回（这一档只配给调查接口）。服务端可能仍在运行，稍后可重试。',
      }
      return
    }
    if (call.kind === 'network') {
      transport.value = { title: '请求未完成', message: call.message }
      return
    }

    // ② 拿到业务响应：**按业务 code 分支**
    const { code, message, data } = call.result
    if (code === 200) {
      vo.value = data
      return
    }

    // 五种拒绝逐一对应；表外的 code 走兜底（仍显示后端 message，不吞掉信息）
    const known = REJECTION_MAP[code]
    rejection.value = known
      ? { code, title: known.title, type: known.type, message: message || known.message }
      : { code, title: '调查请求被拒绝', type: 'error', message: message || '请求失败' }

    // 401 与全局实例同款处理：清登录态并回登录页（否则页面停在"操作无效"的假状态）
    if (code === 401) {
      ElMessage.error('登录已过期，请重新登录')
      await auth.logout()
    }
  } finally {
    investigating.value = false
  }
}

/** 重置表单与结果 */
function handleReset(): void {
  formRef.value?.resetFields()
  resetResult()
}

/** 状态 → 展示信息（表外状态原样展示，不猜） */
function statusInfo(status: string): { label: string; color: 'success' | 'warning' | 'danger' | 'info' } {
  return AGENT_STATUS_MAP[status] ?? { label: status, color: 'info' }
}

/** 是否真的产出了报告（problemType / 证据 / 建议号全空 = report 为 null） */
function hasReport(value: AgentInvestigationVO): boolean {
  return Boolean(
    value.problemType || (value.evidenceIds?.length ?? 0) > 0 || (value.suggestionIds?.length ?? 0) > 0,
  )
}

/** 编号列表 → 一行展示文本 */
function idsText(ids: string[] | null | undefined): string {
  return ids && ids.length > 0 ? ids.join('、') : '—'
}
</script>

<template>
  <div class="investigation-page">
    <div class="page-header">
      <h2 class="page-title">调查助手</h2>
      <span class="page-subtitle">
        只读调查：给出起点工单编号与问题，由后端取证并渲染结论（仅部门主管可用，且只能查本部门工单）
      </span>
    </div>

    <el-card shadow="never" class="form-card">
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-width="90px"
        @submit.prevent
      >
        <el-form-item label="工单编号" prop="orderNo">
          <el-input
            v-model="form.orderNo"
            placeholder="如 WO-20261007-00001"
            maxlength="32"
            clearable
          />
        </el-form-item>
        <el-form-item label="调查问题" prop="question">
          <el-input
            v-model="form.question"
            type="textarea"
            :rows="3"
            maxlength="200"
            show-word-limit
            placeholder="如：这张单现在到哪一步了？"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="investigating" @click="handleSubmit">
            发起调查
          </el-button>
          <el-button :disabled="investigating" @click="handleReset">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never" class="result-card" v-loading="investigating">
      <template #header>
        <span>调查结果</span>
      </template>

      <!-- 调查中（同步接口，最长等约 90s；与工单「分类中」同一做法） -->
      <el-alert v-if="investigating" type="info" :closable="false" show-icon>
        <template #title>
          <el-tag type="info" size="small" disable-transitions>调查中</el-tag>
        </template>
        <div class="alert-hint">
          同步接口正在返回（中位约 11 秒、最长约 90 秒）；请稍候，不要重复提交。
        </div>
      </el-alert>

      <!-- 接口未启用：后端不注册 controller → 404。提示而非报错 -->
      <el-alert
        v-else-if="disabled"
        type="warning"
        :closable="false"
        show-icon
        title="功能未启用"
      >
        <div class="alert-hint">
          后端未启用调查助手（<code>agent.investigation.enabled=false</code>，该路径返回 404）。
          这是"功能没开"，不是故障；请联系管理员开启后重试。
        </div>
      </el-alert>

      <!-- 业务拒绝：按业务 code 分别呈现 -->
      <el-alert
        v-else-if="rejection"
        :type="rejection.type"
        :closable="false"
        show-icon
        :title="rejection.title"
      >
        <div class="alert-hint">
          {{ rejection.message }}
          <span class="code-hint">业务 code = {{ rejection.code }}</span>
        </div>
      </el-alert>

      <!-- 传输层失败：90s 超时 / 网络异常 -->
      <el-alert
        v-else-if="transport"
        type="error"
        :closable="false"
        show-icon
        :title="transport.title"
      >
        <div class="alert-hint">{{ transport.message }}</div>
      </el-alert>

      <!-- 正常返回：直接渲染后端返回的 renderedText（不在前端拼报告） -->
      <div v-else-if="vo" class="result-body">
        <div class="result-header">
          <el-tag :type="statusInfo(vo.status).color" disable-transitions>
            {{ statusInfo(vo.status).label }}
          </el-tag>
          <el-tag v-if="vo.failureCode" type="warning" disable-transitions>
            {{ vo.failureCode }}
          </el-tag>
        </div>

        <el-descriptions :column="2" border size="small" class="result-meta">
          <el-descriptions-item label="状态">
            {{ statusInfo(vo.status).label }}（{{ vo.status }}）
          </el-descriptions-item>
          <el-descriptions-item label="原因码">
            {{ vo.failureCode ?? '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="问题类型">
            {{ vo.problemType ?? '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="证据 / 建议编号">
            {{ idsText(vo.evidenceIds) }} / {{ idsText(vo.suggestionIds) }}
          </el-descriptions-item>
        </el-descriptions>

        <el-alert
          v-if="!hasReport(vo)"
          type="info"
          :closable="false"
          show-icon
          title="本次没有完整报告（report 为 null）"
          class="report-null-hint"
        >
          <div class="alert-hint">
            未完成 / 已取消的运行不产出报告，这是正常返回、不是错误；下方是后端给出的对应正文。
          </div>
        </el-alert>

        <div class="rendered-text-block">
          <div class="rendered-text-title">后端渲染正文（renderedText）</div>
          <pre v-if="vo.renderedText" class="rendered-text">{{ vo.renderedText }}</pre>
          <el-empty v-else description="本次没有可渲染的正文（失败原因见上方原因码）" />
        </div>
      </div>

      <el-empty v-else description="还没有调查结果" />
    </el-card>
  </div>
</template>

<style scoped>
.investigation-page {
  padding: 20px;
}

.page-header {
  margin-bottom: 16px;
}

.page-title {
  margin: 0 0 6px;
  font-size: 20px;
  color: var(--el-text-color-primary);
}

.page-subtitle {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.form-card {
  margin-bottom: 16px;
}

.alert-hint {
  font-size: 13px;
  line-height: 1.6;
}

.code-hint {
  margin-left: 8px;
  color: var(--el-text-color-secondary);
}

.result-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.result-meta {
  margin-bottom: 12px;
}

.report-null-hint {
  margin-bottom: 12px;
}

.rendered-text-title {
  margin-bottom: 8px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.rendered-text {
  margin: 0;
  padding: 12px;
  font-family: inherit;
  font-size: 13px;
  line-height: 1.8;
  white-space: pre-wrap;
  word-break: break-word;
  background-color: var(--el-fill-color-light);
  border-radius: 4px;
}
</style>
