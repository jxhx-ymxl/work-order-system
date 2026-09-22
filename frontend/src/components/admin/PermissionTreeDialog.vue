<script setup lang="ts">
import { ref, watch, nextTick } from 'vue'
import { ElMessage, type ElTree } from 'element-plus'
import { useAdminStore } from '@/stores/admin'
import type { PermissionTreeVO } from '@/types/admin'

const props = defineProps<{
  modelValue: boolean
  roleId: number
  roleName: string
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'success'): void
}>()

const store = useAdminStore()

const treeRef = ref<InstanceType<typeof ElTree>>()
const dialogLoading = ref(false)
const submitting = ref(false)

/** 权限树数据 */
const treeData = ref<PermissionTreeVO[]>([])

/** 已分配的权限 ID 集合 */
const checkedIds = ref<Set<number>>(new Set())

/** 树节点是否全部展开 */
const allExpanded = ref(false)

/** 关闭弹窗 */
function handleClose(): void {
  emit('update:modelValue', false)
}

/** 递归收集所有节点 ID（含叶子与非叶子，用于全选） */
function collectAllIds(nodes: PermissionTreeVO[]): number[] {
  const ids: number[] = []
  function walk(list: PermissionTreeVO[]): void {
    for (const node of list) {
      ids.push(node.id)
      if (node.children && node.children.length > 0) {
        walk(node.children)
      }
    }
  }
  walk(nodes)
  return ids
}

/** 收集所有叶子节点 ID（不含任何有 children 的节点） */
function collectLeafIds(nodes: PermissionTreeVO[]): Set<number> {
  const leafIds = new Set<number>()
  function walk(list: PermissionTreeVO[]): void {
    for (const node of list) {
      if (!node.children || node.children.length === 0) {
        leafIds.add(node.id)
      } else {
        walk(node.children)
      }
    }
  }
  walk(nodes)
  return leafIds
}

/** 弹窗打开时加载权限树 + 角色已有权限 */
watch(
  () => props.modelValue,
  async (visible) => {
    if (!visible) {
      treeData.value = []
      checkedIds.value = new Set()
      allExpanded.value = false
      submitting.value = false
      return
    }

    dialogLoading.value = true
    try {
      const [tree, roleDetail] = await Promise.all([
        store.fetchPermissionTree(),
        store.fetchRoleDetail(props.roleId),
      ])
      treeData.value = tree

      // 角色已有权限 ID 与树中实际存在的叶子节点做交集，过滤掉后端可能
      // 返回的父节点 ID（父节点不应出现在权限分配列表中）
      const leafIds = collectLeafIds(tree)
      const rolePermIds = new Set(roleDetail.permIds ?? [])
      // 只保留树中真实存在的叶子节点 ID
      const validLeafIds = new Set<number>()
      for (const id of rolePermIds) {
        if (leafIds.has(id)) {
          validLeafIds.add(id)
        }
      }
      checkedIds.value = validLeafIds
      allExpanded.value = false

      await nextTick()
      treeRef.value?.setCheckedKeys([...checkedIds.value], false)
    } finally {
      dialogLoading.value = false
    }
  },
)

/** 展开/折叠全部 */
function toggleExpandAll(): void {
  allExpanded.value = !allExpanded.value
  const nodes = treeData.value
  if (!nodes || nodes.length === 0) return

  if (allExpanded.value) {
    // 展开全部节点
    expandAll(nodes)
  } else {
    // 折叠全部节点
    collapseAll(nodes)
  }
}

/** 递归展开 */
function expandAll(nodes: PermissionTreeVO[]): void {
  for (const node of nodes) {
    treeRef.value?.store?.getNode(node.id)?.expand()
    if (node.children && node.children.length > 0) {
      expandAll(node.children)
    }
  }
}

/** 递归折叠 */
function collapseAll(nodes: PermissionTreeVO[]): void {
  for (const node of nodes) {
    treeRef.value?.store?.getNode(node.id)?.collapse()
    if (node.children && node.children.length > 0) {
      collapseAll(node.children)
    }
  }
}

/** 全选 */
function selectAll(): void {
  const allIds = collectAllIds(treeData.value)
  treeRef.value?.setCheckedKeys(allIds, false)
}

/** 取消全选 */
function deselectAll(): void {
  treeRef.value?.setCheckedKeys([], false)
}

/** 树选中状态变化 */
function handleCheckChange(): void {
  // el-tree 内部维护选中状态，无需额外处理
}

/** 确认分配 */
async function handleConfirm(): Promise<void> {
  const checkedKeys = (treeRef.value?.getCheckedKeys() as number[]) ?? []
  const halfCheckedKeys = (treeRef.value?.getHalfCheckedKeys() as number[]) ?? []

  // 关键：仅提交叶子节点 ID。父节点 ID 对应的 permCode 是分类名（如 "order"），
  // 不是 Sa-Token @SaCheckPermission 实际鉴权用的叶子权限码（如 "order:accept"）。
  // 将父节点 ID 存入 t_role_permission 会导致"UI 显示有权限，实际鉴权不通过"。
  const leafIds = collectLeafIds(treeData.value)
  const allPermIds = [...new Set([...checkedKeys, ...halfCheckedKeys])]
    .filter((id) => leafIds.has(id))

  submitting.value = true
  try {
    await store.performAssignPermissions(props.roleId, allPermIds)
    ElMessage.success('权限分配成功')
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
    :title="`权限分配 - ${roleName}`"
    width="560px"
    :close-on-click-modal="false"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div v-loading="dialogLoading" style="min-height: 200px">
      <!-- 工具栏 -->
      <div class="tree-toolbar">
        <el-button size="small" @click="toggleExpandAll">
          {{ allExpanded ? '折叠全部' : '展开全部' }}
        </el-button>
        <el-button size="small" @click="selectAll">全选</el-button>
        <el-button size="small" @click="deselectAll">取消全选</el-button>
      </div>

      <!-- 权限树 -->
      <div class="tree-container">
        <el-tree
          v-if="treeData.length > 0"
          ref="treeRef"
          :data="treeData"
          node-key="id"
          show-checkbox
          default-expand-all
          :props="{ children: 'children', label: 'permName', disabled: '_disabled' }"
          @check="handleCheckChange"
        >
          <template #default="{ data }">
            <span class="tree-node-label">
              <span class="perm-name">{{ (data as PermissionTreeVO).permName }}</span>
              <el-tag size="small" type="info" class="perm-code-tag">
                {{ (data as PermissionTreeVO).permCode }}
              </el-tag>
            </span>
          </template>
        </el-tree>

        <el-empty
          v-if="!dialogLoading && treeData.length === 0"
          description="暂无权限数据"
        />
      </div>
    </div>

    <template #footer>
      <el-button @click="handleClose" :disabled="submitting">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="handleConfirm">
        确认分配
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.tree-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.tree-container {
  max-height: 400px;
  overflow-y: auto;
}

.tree-node-label {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.perm-name {
  font-size: 14px;
  color: var(--el-text-color-primary);
}

.perm-code-tag {
  font-family: monospace;
  font-size: 11px;
}
</style>
