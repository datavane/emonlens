<template>
  <div class="panel-root">

    <div class="page-toolbar">
      <div class="toolbar-actions">
        <n-button type="primary" size="small" @click="openCreate">＋ 新建角色</n-button>
      </div>
    </div>

    <n-card size="small" class="table-card">
      <n-data-table class="main-table" flex-height style="height: 100%" :columns="columns" :data="roles" size="small" :bordered="false" :loading="loading"
        :row-props="(row) => ({ style: 'cursor:pointer', onClick: () => openDetail(row) })" />
    </n-card>

    <!-- 角色详情 / 权限编辑抽屉 -->
    <n-drawer v-model:show="showDetail" :width="680">
      <n-drawer-content :title="'角色：' + (detail?.name || '')" closable>
        <template v-if="detail">
          <div class="detail-head">
            <n-tag size="small" :bordered="false" :type="detail.builtin ? 'default' : 'primary'">
              {{ detail.builtin ? '内置' : '自定义' }}
            </n-tag>
            <span class="mono role-code">{{ detail.code }}</span>
            <span class="flex-1"></span>
            <span class="num" style="font-size:12px;color:var(--text-muted)">{{ (draft[detail.id] || []).length }} / {{ allPermissionCount }} 项</span>
          </div>
          <n-alert v-if="detail.memberCount" size="small" type="warning" style="margin-bottom:12px">
            该角色已绑定 {{ detail.memberCount }} 名成员：修改权限会即时影响这些成员的功能可见性与操作范围。
          </n-alert>
          <div v-if="detail.description" class="page-sub" style="margin-bottom:6px">{{ detail.description }}</div>

          <n-checkbox-group v-model:value="draft[detail.id]" :disabled="detail.builtin">
            <div v-for="group in PERMISSION_GROUPS" :key="group.section" class="perm-group">
              <div class="perm-group-title">
                <span>{{ group.section }}</span>
                <span class="flex-1"></span>
                <n-button v-if="!detail.builtin" size="tiny" quaternary
                  @click="toggleGroupAll(detail.id, group)">全选 / 清空</n-button>
              </div>
              <div class="permission-grid">
                <n-checkbox v-for="p in group.items" :key="p.key" :value="p.key" class="perm-item">
                  <span class="perm-label">{{ p.label }}</span>
                  <span class="perm-key mono">{{ p.key }}</span>
                </n-checkbox>
              </div>
            </div>
          </n-checkbox-group>

          <div class="role-foot">
            <span v-if="detail.builtin" class="page-sub">内置角色由系统预设，如需调整请创建自定义角色</span>
            <span v-else-if="dirty(detail)" class="page-sub dirty-hint">有未保存的修改</span>
            <span class="flex-1"></span>
            <n-button v-if="!detail.builtin" size="small" type="primary"
              :loading="savingRoleId === detail.id" :disabled="!!savingRoleId || !dirty(detail)"
              @click="save(detail)">
              保存权限
            </n-button>
          </div>
        </template>
        <n-spin v-else style="width: 100%; margin: 40px 0" />
      </n-drawer-content>
    </n-drawer>

    <Modal v-model:show="showCreate" :width="440" title="新建自定义角色">
      <n-form label-placement="top">
        <n-form-item label="角色名称" required>
          <n-input v-model:value="createForm.name" placeholder="如 数据分析只读" @keyup.enter="doCreate" />
        </n-form-item>
        <div class="page-sub">创建后点击角色进入详情抽屉勾选权限并保存；用户可在「成员」页绑定该角色。</div>
      </n-form>
      <template #footer>
        <n-button size="small" :disabled="creating" @click="showCreate = false">取消</n-button>
        <n-button type="primary" size="small" :loading="creating" @click="doCreate">创建</n-button>
      </template>
    </Modal>
  </div>
</template>

<script setup>
import { h, onMounted, reactive, ref } from 'vue'
import { NAlert, NButton, NPopconfirm, NTag } from 'naive-ui'
import { useMessage } from 'naive-ui'
import api from '@/api'

const message = useMessage()
const roles = ref([])
const loading = ref(false)
const savingRoleId = ref('')
const draft = reactive({})
const savedSnapshot = reactive({})
const showCreate = ref(false), creating = ref(false)
const createForm = reactive({ name: '' })
const showDetail = ref(false), detail = ref(null)

const PERMISSION_GROUPS = [
  {
    section: 'Trust 中心',
    items: [
      { key: 'workspace:trust:overview:query', label: '总览查看' },
      { key: 'workspace:trust:trace:query', label: 'Trace 查看' },
      { key: 'workspace:trust:trace:content', label: 'Trace 内容（I/O 原文）' },
      { key: 'workspace:trust:finding:query', label: '发现查看' },
      { key: 'workspace:trust:incident:query', label: '事件查看' },
      { key: 'workspace:trust:incident:manage', label: '事件处理' },
      { key: 'workspace:trust:monitor:query', label: '监控规则查看' },
      { key: 'workspace:trust:monitor:manage', label: '监控规则管理' },
      { key: 'workspace:trust:evaluator:query', label: '评估器查看' },
      { key: 'workspace:trust:evaluator:manage', label: '评估器管理' },
      { key: 'workspace:trust:evaluation:query', label: '评估结果查看' },
      { key: 'workspace:trust:evaluation:run', label: '评估执行 / 标注' },
      { key: 'workspace:trust:agent:query', label: 'Agent 查看' },
      { key: 'workspace:trust:agent:manage', label: 'Agent 注册同步' },
      { key: 'workspace:trust:key:manage', label: '接入密钥管理' }
    ]
  },
  {
    section: '工程',
    items: [
      { key: 'workspace:trust:prompt:query', label: '提示词查看' },
      { key: 'workspace:trust:prompt:manage', label: '提示词管理' },
      { key: 'workspace:trust:dataset:query', label: '数据集查看' },
      { key: 'workspace:trust:dataset:manage', label: '数据集管理' },
      { key: 'workspace:trust:model:query', label: '模型价格查看' },
      { key: 'workspace:trust:model:manage', label: '模型价格管理' }
    ]
  },
  {
    section: '系统管理',
    items: [
      { key: 'workspace:sys:user:manage', label: '用户管理' },
      { key: 'workspace:sys:role:manage', label: '角色权限管理' },
      { key: 'workspace:sys:audit:query', label: '审计日志查看' },
      { key: 'workspace:sys:notify:manage', label: '通知配置管理' },
      { key: 'workspace:sys:openapi:manage', label: 'OpenAPI 管理' },
      { key: 'workspace:sys:setting:manage', label: '系统设置管理' }
    ]
  }
]

const ALL_PERMISSIONS = PERMISSION_GROUPS.flatMap((g) => g.items.map((i) => i.key))
const allPermissionCount = ALL_PERMISSIONS.length

function permLabelOf(key) {
  for (const g of PERMISSION_GROUPS) {
    const hit = g.items.find((i) => i.key === key)
    if (hit) return hit.label
  }
  return key
}
function toggleGroupAll(roleId, group) {
  const keys = group.items.map((i) => i.key)
  const cur = draft[roleId] || []
  const allOn = keys.every((k) => cur.includes(k))
  draft[roleId] = allOn
    ? cur.filter((k) => !keys.includes(k))
    : [...new Set([...cur, ...keys])]
}

function dirty(role) {
  return JSON.stringify([...(draft[role.id] || [])].sort()) !== JSON.stringify([...(savedSnapshot[role.id] || [])].sort())
}

const columns = [
  { title: '角色', key: 'name', width: 130, ellipsis: { tooltip: true }, render: (r) => h('span', { style: 'font-weight:600;font-size:13px' }, r.name) },
  { title: '类型', key: 'type', width: 70, render: (r) => h(NTag, { size: 'small', bordered: false, type: r.builtin ? 'default' : 'primary' },
      { default: () => (r.builtin ? '内置' : '自定义') }) },
  { title: 'Code', key: 'code', width: 110, ellipsis: { tooltip: true }, render: (r) => h('span', { class: 'mono', style: 'font-size:11.5px' }, r.code) },
  { title: '成员数', key: 'memberCount', width: 60, align: 'right', render: (r) => h('span', { class: 'num',
      style: r.memberCount ? 'font-weight:600' : 'color:var(--text-muted)' }, String(r.memberCount ?? 0)) },
  { title: '已授权限', key: 'granted', width: 110, render: (r) => {
      const n = (r.permissions || []).length
      const pct = Math.round(n * 100 / allPermissionCount)
      return h('div', { style: 'display:flex;align-items:center;gap:8px' }, [
        h('div', { style: 'flex:1;height:6px;background:var(--bg-subtle);border-radius:3px;overflow:hidden' }, [
          h('div', { style: `height:100%;width:${pct}%;background:var(--primary);border-radius:3px` })
        ]),
        h('span', { class: 'num', style: 'font-size:11.5px;color:var(--text-muted);flex:0 0 44px' }, n + ' / ' + allPermissionCount)
      ])
    } },
  { title: '权限明细', key: 'perms', minWidth: 140, ellipsis: { tooltip: true }, render: (r) => {
      const labels = (r.permissions || []).slice(0, 3).map(permLabelOf)
      const more = (r.permissions || []).length - labels.length
      if (!labels.length) return h('span', { style: 'color:var(--text-muted);font-size:12px' }, '—')
      return h('span', { style: 'font-size:12px;color:var(--text-secondary)' },
        labels.join('、') + (more > 0 ? ` 等 ${r.permissions.length} 项` : ''))
    } },
  { title: '操作', key: 'ops', width: 140, render: (r) => {
      const btns = [h(NButton, { size: 'tiny', type: 'primary', ghost: true,
        onClick: (e) => { e.stopPropagation(); openDetail(r) } }, { default: () => '编辑权限' })]
      if (!r.builtin) {
        btns.push(h(NPopconfirm, { onPositiveClick: () => doDelete(r) }, {
          trigger: () => h(NButton, { size: 'tiny', quaternary: true, type: 'error',
            disabled: !!r.memberCount,
            title: r.memberCount ? `该角色已绑定 ${r.memberCount} 名成员，请先在「成员」页改绑角色后再删除` : '',
            style: 'margin-left:4px', onClick: (e) => e.stopPropagation() }, { default: () => '删除' }),
          default: () => `确认删除角色「${r.name}」？其权限配置将一并清除。`
        }))
      }
      return h('div', { style: 'display:flex;align-items:center;gap:4px;flex-wrap:nowrap' }, btns)
    } }
]

async function load() {
  const list = await api.sysRoles()
  roles.value = list || []
  list.forEach((r) => {
    draft[r.id] = r.permissions || []
    savedSnapshot[r.id] = [...(r.permissions || [])]
  })
}
function openDetail(role) {
  detail.value = role
  showDetail.value = true
}
function openCreate() {
  createForm.name = ''
  showCreate.value = true
}
async function doCreate() {
  if (!createForm.name.trim()) { message.error('请填写角色名称'); return }
  creating.value = true
  try {
    await api.createRole({ name: createForm.name.trim() })
    message.success('角色已创建，点击角色进入详情勾选权限并保存')
    showCreate.value = false
    await load()
  } catch (e) { message.error(e.message) }
  finally { creating.value = false }
}
async function doDelete(role) {
  try {
    await api.deleteRole(role.id)
    message.success(`角色「${role.name}」已删除`)
    showDetail.value = false
    await load()
  } catch (e) { message.error(e.message) }
}
async function save(role) {
  savingRoleId.value = role.id
  try {
    await api.updateRolePermissions(role.id, draft[role.id])
    savedSnapshot[role.id] = [...draft[role.id]]
    // 同步表格行，避免保存后列表仍显示旧权限数
    const hit = roles.value.find((r) => r.id === role.id)
    if (hit) hit.permissions = [...draft[role.id]]
    if (detail.value?.id === role.id) detail.value = { ...detail.value, permissions: [...draft[role.id]] }
    message.success(`角色「${role.name}」权限已更新`)
  } catch (e) { message.error(e.message) }
  finally { savingRoleId.value = '' }
}
onMounted(load)
</script>

<style scoped>
.flex-1 { flex: 1; }
.page-sub { font-size: 11.5px; color: var(--text-muted); }
.role-code { font-size: 11px; color: var(--text-muted); }
.detail-head { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; }
.perm-group { margin-bottom: 14px; }
.perm-group:last-child { margin-bottom: 4px; }
.perm-group-title {
  display: flex; align-items: center; gap: 8px;
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  color: var(--text-muted);
  margin-bottom: 8px;
}
.permission-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 6px 12px; }
@media (max-width: 1100px) { .permission-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
.perm-item :deep(.n-checkbox__label) { display: flex; flex-direction: column; line-height: 1.3; }
.perm-label { font-size: 12.5px; color: var(--text-primary); }
.perm-key { font-size: 10.5px; color: var(--text-muted); word-break: break-all; }
.role-foot { display: flex; align-items: center; gap: 8px; margin-top: 12px; padding-top: 10px; border-top: 1px solid var(--border); }
.dirty-hint { color: var(--warn); }
.mt12 { margin-top: 12px; }
.mono { font-family: "JetBrains Mono", ui-monospace, Menlo, monospace; }
.link { color: var(--primary); }
</style>
