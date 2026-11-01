<template>
  <div class="panel-root">
    <div class="page-toolbar">
      <div class="toolbar-filters">
        <n-input v-model:value="keyword" placeholder="搜索用户名 / 姓名" size="small" style="width: 160px" clearable @clear="search" @keyup.enter="search" />
        <n-select v-model:value="statusFilter" placeholder="全部状态" size="small" style="width: 100px" clearable
          :options="['ACTIVE', 'LOCKED'].map((s) => ({ label: s === 'ACTIVE' ? '启用' : '已锁定', value: s }))"
          @update:value="search" />
      </div>
      <div class="toolbar-actions">
        <lan-button type="primary" :icon="AddOutline" @click="showCreate = true">新建用户</lan-button>
      </div>
    </div>
    <n-card size="small" class="table-card">
      <n-data-table class="main-table" flex-height style="height: 100%" :columns="columns" :data="records" size="small" :bordered="false" remote :scroll-x="740"
        :pagination="pagination" :loading="loading" />
    </n-card>

    <Modal v-model:show="showCreate" :width="480" title="新建用户">
      <n-form label-placement="top">
        <n-form-item label="用户名"><n-input v-model:value="form.username" /></n-form-item>
        <n-form-item label="初始密码"><n-input v-model:value="form.password" type="password" /></n-form-item>
        <n-form-item label="姓名"><n-input v-model:value="form.name" /></n-form-item>
        <n-form-item label="角色"><n-select v-model:value="form.roleId" :options="roleOptions" /></n-form-item>
      </n-form>
      <template #footer>
        <lan-button :disabled="submitting" @click="showCreate = false">取消</lan-button>
        <lan-button type="primary" :loading="submitting" @click="doCreate">创建</lan-button>
      </template>
    </Modal>

    <Modal v-model:show="showReset" :width="480" title="重置密码">
      <n-form ref="resetFormRef" :model="resetForm" :rules="resetRules" label-placement="top">
        <n-form-item label="用户">
          <n-input :value="resetTarget?.username || ''" disabled />
        </n-form-item>
        <n-form-item label="新密码" path="password">
          <n-input v-model:value="resetForm.password" type="password" show-password-on="click"
            placeholder="请输入至少 8 位的新密码" @keyup.enter="submitReset" />
        </n-form-item>
      </n-form>
      <template #footer>
        <lan-button :disabled="resetSubmitting" @click="closeReset">取消</lan-button>
        <lan-button type="primary" :loading="resetSubmitting" @click="submitReset">确认重置</lan-button>
      </template>
    </Modal>
  </div>
</template>

<script setup>
import { h, onMounted, reactive, ref } from 'vue'
import { NButton, NPopconfirm, NSelect, NTag, useMessage } from 'naive-ui'
import api from '@/api'
import { AddOutline } from '@vicons/ionicons5'

const message = useMessage()
const records = ref([]), loading = ref(false), showCreate = ref(false)
const keyword = ref(''), statusFilter = ref(null)
function search() { pagination.page = 1; load() }
const submitting = ref(false), actionLoading = ref('')
const showReset = ref(false), resetTarget = ref(null), resetFormRef = ref(null)
const resetForm = reactive({ password: '' })
const resetRules = {
  password: [
    { required: true, message: '请输入新密码', trigger: ['input', 'blur'] },
    { min: 8, message: '密码长度不能少于 8 位', trigger: ['input', 'blur'] }
  ]
}
const resetSubmitting = ref(false)
const roleOptions = ref([])
function roleName(id) {
  const hit = roleOptions.value.find((o) => o.value === id)
  return hit ? hit.label : (id || '—')
}
const pagination = reactive({
  page: 1, pageSize: 10, itemCount: 0, showSizePicker: true, pageSizes: [10, 20, 50, 100],
  prefix: ({ itemCount }) => '共 ' + itemCount + ' 条',
  onChange: (page) => { pagination.page = page; load() },
  onUpdatePageSize: (pageSize) => { pagination.pageSize = pageSize; pagination.page = 1; load() }
})
const form = reactive({ username: '', password: '', name: '', roleId: 'role-viewer' })

const columns = [
  { title: '用户名', key: 'username', minWidth: 130, ellipsis: { tooltip: true } },
  { title: '姓名', key: 'name', minWidth: 110, ellipsis: { tooltip: true } },
  { title: '状态', key: 'status', width: 85, render: (r) => h(NTag, { size: 'small', bordered: false, type: r.status === 'ACTIVE' ? 'success' : 'error' }, { default: () => (r.status === 'ACTIVE' ? '启用' : '已锁定') }) },
  { title: '创建时间', key: 'createdAt', width: 95, render: (r) => h('span', { class: 'mono', style: 'font-size:11px' }, r.createdAt ? String(r.createdAt).slice(0, 10) : '—') },
  { title: '角色', key: 'roleId', minWidth: 140, render: (r) => h(NSelect, {
      size: 'tiny', value: r.roleId, options: roleOptions.value, disabled: r.username === 'admin',
      onUpdateValue: (v) => doChangeRole(r, v)
    }) },
  { title: '操作', key: 'ops', width: 180, render: (r) => [
      r.status === 'ACTIVE'
        ? h(NPopconfirm, { onPositiveClick: () => doToggle(r) }, {
            trigger: () => h(NButton, { size: 'tiny', ghost: true, loading: actionLoading.value === `toggle-${r.id}`, disabled: !!actionLoading.value }, { default: () => '锁定' }),
            default: () => `确认锁定用户「${r.username}」？锁定后该用户将无法登录。`
          })
        : h(NButton, { size: 'tiny', ghost: true, loading: actionLoading.value === `toggle-${r.id}`, disabled: !!actionLoading.value, onClick: () => doToggle(r) }, { default: () => '解锁' }),
      h(NButton, { size: 'tiny', ghost: true, loading: actionLoading.value === `reset-${r.id}`, disabled: !!actionLoading.value, onClick: () => openReset(r) }, { default: () => '重置密码' })
    ] }
]

async function loadRoles() {
  try {
    const roles = await api.sysRoles()
    roleOptions.value = (roles || []).map((r) => ({ label: r.name, value: r.id }))
  } catch (e) {
    // 只有 user:manage 的角色拿不到角色列表：成员管理仍应可用，只是不能改角色
    roleOptions.value = []
  }
}
async function load() {
  loading.value = true
  try {
    const page = await api.sysUsers({
      pageNumber: pagination.page, pageSize: pagination.pageSize,
      query: keyword.value.trim() || undefined,
      status: statusFilter.value || undefined
    })
    records.value = page.records || []
    pagination.itemCount = page.total || 0
  } catch (e) {
    message.error(e.message || '用户列表加载失败')
  } finally { loading.value = false }
}
async function doCreate() {
  submitting.value = true
  try {
    await api.createSysUser({ ...form })
    message.success('已创建'); showCreate.value = false; load()
  } catch (e) { message.error(e.message) }
  finally { submitting.value = false }
}
async function doToggle(r) {
  actionLoading.value = `toggle-${r.id}`
  try {
    await api.changeUserStatus(r.id, r.status === 'ACTIVE' ? 'LOCKED' : 'ACTIVE')
    message.success('已更新'); load()
  } catch (e) { message.error(e.message) }
  finally { actionLoading.value = '' }
}
async function doChangeRole(r, roleId) {
  if (roleId === r.roleId) return
  actionLoading.value = `role-${r.id}`
  try {
    await api.changeUserRole(r.id, roleId)
    message.success(`已将 ${r.username} 的角色改为 ${roleName(roleId)}`)
    load()
  } catch (e) { message.error(e.message) }
  finally { actionLoading.value = '' }
}
function openReset(row) {
  resetTarget.value = row
  resetForm.password = ''
  showReset.value = true
}
function closeReset() {
  if (resetSubmitting.value) return
  showReset.value = false
  resetTarget.value = null
  resetForm.password = ''
}
async function submitReset() {
  if (!resetTarget.value || resetSubmitting.value) return
  try {
    await resetFormRef.value?.validate()
  } catch (e) {
    return
  }
  const target = resetTarget.value
  resetSubmitting.value = true
  actionLoading.value = `reset-${target.id}`
  try {
    await api.resetUserPassword(target.id, resetForm.password)
    message.success('密码已重置')
    showReset.value = false
    resetTarget.value = null
    resetForm.password = ''
  } catch (e) { message.error(e.message) }
  finally {
    resetSubmitting.value = false
    actionLoading.value = ''
  }
}
onMounted(async () => { await loadRoles(); await load() })
</script>
