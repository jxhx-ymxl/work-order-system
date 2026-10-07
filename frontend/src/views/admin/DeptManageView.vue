<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { createDept, listDepts, updateDept } from '@/api/dept'
import type { DeptVO } from '@/types/dept'

const loading = ref(false)
const depts = ref<DeptVO[]>([])
const saving = ref(false)

const dialogVisible = ref(false)
/** null = 新增；非空 = 给这一行改名 */
const editingId = ref<number | null>(null)
const formRef = ref<FormInstance>()
const form = reactive({ name: '' })
const rules: FormRules = {
  name: [
    { required: true, message: '请输入部门名称', trigger: 'blur' },
    { max: 64, message: '名称不超过 64 个字符', trigger: 'blur' },
  ],
}

/**
 * 拉列表。
 *
 * 用**全局 axios 实例**（普通接口）；失败提示已由 `request.ts` 的响应拦截器统一给出，这里只清空列表。
 */
async function fetchDepts(): Promise<void> {
  loading.value = true
  try {
    depts.value = await listDepts()
  } catch {
    depts.value = []
  } finally {
    loading.value = false
  }
}

onMounted(fetchDepts)

/** 打开"新增"弹窗 */
function openCreate(): void {
  editingId.value = null
  form.name = ''
  dialogVisible.value = true
}

/** 打开"改名"弹窗 */
function openRename(row: DeptVO): void {
  editingId.value = row.id
  form.name = row.name
  dialogVisible.value = true
}

/** 保存（新增或改名） */
async function handleSave(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  saving.value = true
  try {
    if (editingId.value === null) {
      await createDept({ name: form.name.trim() })
      ElMessage.success('已新增部门')
    } else {
      await updateDept(editingId.value, { name: form.name.trim() })
      ElMessage.success('已改名')
    }
    dialogVisible.value = false
    await fetchDepts()
  } catch {
    // 错误消息已在 request 拦截器中处理（重名 → 业务码 400 的提示也在那里）
  } finally {
    saving.value = false
  }
}

/**
 * 启停。
 *
 * **没有删除**：部门被用户/工单引用，物理删除会留悬空引用——"删除"= 停用（D109）。
 * 停用一个仍有用户的部门**不强拦**，但把"仍有 N 个用户"说出来（`userCount` 来自同一个响应）。
 */
async function toggleEnabled(row: DeptVO): Promise<void> {
  const disabling = row.enabled === 1
  const tail = disabling && row.userCount > 0 ? `（该部门仍有 ${row.userCount} 个用户，停用后他们仍归属该部门）` : ''
  try {
    await ElMessageBox.confirm(
      `${disabling ? '停用' : '启用'}部门「${row.name}」？${tail}`,
      disabling ? '停用部门' : '启用部门',
      { type: 'warning', confirmButtonText: '确定', cancelButtonText: '取消' },
    )
  } catch {
    return // 用户取消
  }

  try {
    await updateDept(row.id, { enabled: disabling ? 0 : 1 })
    ElMessage.success(disabling ? '已停用' : '已启用')
    await fetchDepts()
  } catch {
    // 同上：提示由拦截器统一给
  }
}
</script>

<template>
  <div class="dept-manage-page">
    <div class="page-header">
      <h2 class="page-title">部门管理</h2>
      <el-button type="primary" @click="openCreate">新增部门</el-button>
    </div>

    <div v-loading="loading" class="table-area">
      <el-table :data="depts" stripe style="width: 100%">
        <el-table-column prop="id" label="ID" width="90" />
        <el-table-column prop="name" label="名称" min-width="180" show-overflow-tooltip />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag
              :type="(row as DeptVO).enabled === 1 ? 'success' : 'danger'"
              size="small"
              disable-transitions
            >
              {{ (row as DeptVO).enabled === 1 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="用户数" width="100">
          <template #default="{ row }">{{ (row as DeptVO).userCount }}</template>
        </el-table-column>
        <el-table-column label="创建时间" width="170">
          <template #default="{ row }">{{ (row as DeptVO).createdAt ?? '-' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="170" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click="openRename(row as DeptVO)">
              改名
            </el-button>
            <el-button
              :type="(row as DeptVO).enabled === 1 ? 'danger' : 'success'"
              link
              size="small"
              @click="toggleEnabled(row as DeptVO)"
            >
              {{ (row as DeptVO).enabled === 1 ? '停用' : '启用' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-empty v-if="!loading && depts.length === 0" description="暂无部门" />
    </div>

    <el-dialog
      v-model="dialogVisible"
      :title="editingId === null ? '新增部门' : '部门改名'"
      width="420px"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px" @submit.prevent>
        <el-form-item label="部门名称" prop="name">
          <el-input v-model="form.name" maxlength="64" show-word-limit placeholder="如 运维一部" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.dept-manage-page {
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

.table-area {
  background: var(--el-bg-color);
  border-radius: 4px;
  min-height: 200px;
}
</style>
