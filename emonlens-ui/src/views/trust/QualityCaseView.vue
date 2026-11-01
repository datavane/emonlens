<template>
  <div class="base-page">
    <div class="head-stack">
      <div class="page-toolbar">
        <div class="toolbar-filters">
          <n-select v-model:value="status" size="small" clearable placeholder="全部状态"
            :options="statusOptions" style="width: 150px" @update:value="load" />
          <n-input v-model:value="owner" size="small" clearable placeholder="Owner 精确匹配"
            style="width: 200px" @keyup.enter="load" @clear="load" />
          <n-button size="small" @click="load">筛选</n-button>
        </div>
        <div class="toolbar-actions">
          <n-button size="small" :loading="loading" @click="load">刷新</n-button>
          <n-button type="primary" size="small" @click="openCreate">＋ 登记案例</n-button>
        </div>
      </div>
    </div>

    <n-card size="small" class="table-card">
      <n-data-table class="main-table" flex-height style="height: 100%" :columns="columns" :data="rows" size="small" :bordered="false"
        :loading="loading" :row-key="(r) => r.id" :scroll-x="770"
        :row-props="(r) => ({ style: 'cursor: pointer', onClick: () => open(r) })" />
    </n-card>

    <Modal v-model:show="showCreate" :width="620" title="登记质量案例">
      <n-form label-placement="top">
        <div class="form-grid">
          <n-form-item label="数据集" required>
            <n-select v-model:value="createForm.datasetId" :options="datasetOptions"
              placeholder="选择数据集" @update:value="onCreateDatasetChange" />
          </n-form-item>
          <n-form-item label="源 run">
            <n-select v-model:value="createForm.sourceRunId" :options="runOptions" clearable
              placeholder="可选：关联触发问题的 run" />
          </n-form-item>
        </div>
        <div class="form-grid">
          <n-form-item label="源样本">
            <n-select v-model:value="createForm.sourceItemId" :options="itemOptions" clearable
              placeholder="可选：定位到具体样本" />
          </n-form-item>
          <n-form-item label="根因分类">
            <n-select v-model:value="createForm.rootCauseCategory" :options="rootCauseOptions" />
          </n-form-item>
        </div>
        <n-form-item label="标题" required>
          <n-input v-model:value="createForm.title" placeholder="如：退款场景引用错误订单号" />
        </n-form-item>
        <n-form-item label="现象" required>
          <n-input v-model:value="createForm.phenomenon" type="textarea" :rows="4"
            placeholder="描述用户看到的问题、触发条件和影响范围" />
        </n-form-item>
        <div class="form-grid">
          <n-form-item label="Owner">
            <n-input v-model:value="createForm.owner" placeholder="负责人" />
          </n-form-item>
          <n-form-item label="Agent Key">
            <n-input v-model:value="createForm.agentKey" placeholder="可选，如 codex-cli" class="mono" />
          </n-form-item>
        </div>
      </n-form>
      <template #footer>
        <n-button size="small" :disabled="creating" @click="showCreate = false">取消</n-button>
        <n-button type="primary" size="small" :loading="creating" @click="doCreate">登记</n-button>
      </template>
    </Modal>

    <n-drawer v-model:show="showDetail" :width="560" placement="right">
      <n-drawer-content v-if="selected" title="案例详情" closable>
        <div class="case-head">
          <n-tag size="small" :bordered="false" :type="statusType(selected.status)">{{ enumText('caseStatus', selected.status) }}</n-tag>
          <span class="case-title">{{ selected.title }}</span>
        </div>
        <div class="meta-grid">
          <span>Dataset</span><b class="mono">{{ selected.datasetId }}</b>
          <span>源 run</span><b class="mono">{{ selected.sourceRunId || '—' }}</b>
          <span>源样本</span><b class="mono">{{ selected.sourceItemId || '—' }}</b>
          <span>Agent</span><b class="mono">{{ selected.agentKey || '—' }}</b>
          <span>根因</span><b>{{ selected.rootCauseCategory ? enumText('rootCause', selected.rootCauseCategory) : '—' }}</b>
          <span>Owner</span><b>{{ selected.owner || '—' }}</b>
          <span>回归 run</span><b class="mono">{{ selected.regressionRunId || '—' }}</b>
          <span>创建</span><b>{{ fmt(selected.createdAt) }}</b>
        </div>
        <n-divider />
        <div class="section-title">现象</div>
        <div class="phenomenon">{{ selected.phenomenon || '—' }}</div>
        <div class="section-title">修复说明</div>
        <div class="phenomenon">{{ selected.fixNote || '—' }}</div>

        <n-divider />
        <div class="section-title">状态迁移</div>
        <n-space :size="8" class="mb8">
          <n-button v-for="s in nextStatuses(selected.status)" :key="s" size="tiny"
            :type="s === 'REJECTED' ? 'error' : 'primary'" secondary @click="moveStatus(s)">
            → {{ s }}
          </n-button>
          <span v-if="!nextStatuses(selected.status).length" class="page-sub">当前状态不可再迁移</span>
        </n-space>
        <n-input v-model:value="fixNote" size="small" type="textarea" :rows="2"
          placeholder="修复说明（可选，随状态迁移保存）" class="mb8" />

        <div class="section-title">回归验证</div>
        <div class="filters">
          <n-input v-model:value="regressionRunId" size="small" placeholder="回归 run ID" class="mono" />
          <n-button size="small" @click="linkRegression">关联回归 run</n-button>
        </div>
        <div class="page-sub mb8">
          关闭条件：状态已修复、回归 run 已完成、该 run 最新 Gate 为可用通过。
        </div>
        <n-button type="primary" size="small" :loading="closing" :disabled="selected.status !== 'FIXED'"
          @click="doClose">关闭案例</n-button>
      </n-drawer-content>
    </n-drawer>
  </div>
</template>

<script setup>
import { computed, h, onMounted, reactive, ref, watch } from 'vue'
import { NButton, NTag, useMessage } from 'naive-ui'
import api from '@/api'
import { enumText, enumOptions } from '@/common/i18n'

const message = useMessage()
const rows = ref([]), loading = ref(false)
const status = ref(null), owner = ref('')
const showDetail = ref(false), selected = ref(null)
const fixNote = ref(''), regressionRunId = ref(''), closing = ref(false)

const showCreate = ref(false), creating = ref(false)
const datasets = ref([]), datasetDetail = ref(null)
const createForm = reactive({
  datasetId: null, sourceRunId: null, sourceItemId: null,
  title: '', phenomenon: '', rootCauseCategory: 'UNKNOWN', owner: '', agentKey: ''
})
const datasetOptions = computed(() => datasets.value.map((d) => ({
  label: `${d.datasetKey} (${d.itemCount ?? 0} 条)`, value: d.id
})))
const runOptions = computed(() => (datasetDetail.value?.runs || []).map((r, i, arr) => ({
  label: `#${arr.length - i} ${r.evaluatorKey || (r.evaluatorKeys || []).join(' + ') || 'run'} · ${String(r.startedAt || '').slice(5, 16).replace('T', ' ')}`,
  value: r.id
})))
const sourceRunItems = ref([])
const itemOptions = computed(() => sourceRunItems.value.map((it) => ({
  label: `${String(it.id).slice(0, 8)} · ${it.sampleSnapshot?.input || it.sampleSnapshot?.traceInput || '无正文'}`,
  value: it.id
})))

const statusOptions = enumOptions('caseStatus', ['OPEN', 'INVESTIGATING', 'FIXED', 'CLOSED', 'REJECTED'])
  .map((v) => ({ label: v, value: v }))
const rootCauseOptions = enumOptions('rootCause', ['UNKNOWN', 'PROMPT', 'MODEL', 'TOOL', 'RETRIEVAL', 'DATA', 'POLICY', 'ENVIRONMENT'])
  .map((v) => ({ label: v, value: v }))
const columns = [
  { title: '标题', key: 'title', minWidth: 160, ellipsis: { tooltip: true } },
  { title: '状态', key: 'status', width: 85, render: (r) => h(NTag, {
      size: 'tiny', bordered: false, type: statusType(r.status)
    }, { default: () => enumText('caseStatus', r.status) }) },
  { title: 'Owner', key: 'owner', width: 75, ellipsis: { tooltip: true }, render: (r) => r.owner || '—' },
  { title: '根因', key: 'rootCauseCategory', width: 90, render: (r) => (r.rootCauseCategory ? enumText('rootCause', r.rootCauseCategory) : '—') },
  { title: '回归 run', key: 'regressionRunId', minWidth: 120, ellipsis: { tooltip: true },
    render: (r) => h('span', { class: 'mono', title: r.regressionRunId || '' }, shortId(r.regressionRunId)) },
  { title: 'Dataset', key: 'datasetId', minWidth: 120, ellipsis: { tooltip: true },
    render: (r) => h('span', { class: 'mono', title: r.datasetId || '' }, shortId(r.datasetId)) },
  { title: '创建时间', key: 'createdAt', width: 120, ellipsis: { tooltip: true }, render: (r) => fmt(r.createdAt) }
]

async function onSourceRunChange(runId) {
  createForm.sourceItemId = null
  sourceRunItems.value = []
  if (!runId || !createForm.datasetId) return
  try {
    const data = await api.datasetRunDetail(createForm.datasetId, runId)
    sourceRunItems.value = data.items || []
  } catch (e) {
    // 源 run 加载失败不影响登记；只是无法定位源样本
    sourceRunItems.value = []
  }
}

watch(() => createForm.sourceRunId, (runId) => { onSourceRunChange(runId) })
function statusType(s) {
  return s === 'CLOSED' ? 'success' : s === 'FIXED' ? 'info'
    : s === 'REJECTED' ? 'error' : s === 'INVESTIGATING' ? 'warning' : 'default'
}
function nextStatuses(s) {
  if (s === 'OPEN' || s === 'FIXED') return ['INVESTIGATING', 'REJECTED']
  if (s === 'INVESTIGATING') return ['FIXED', 'REJECTED']
  return []
}
function fmt(value) {
  return value ? String(value).replace('T', ' ').slice(0, 19) : '—'
}
function shortId(value) {
  if (!value) return '—'
  const s = String(value)
  return s.length > 14 ? s.slice(0, 8) + '…' + s.slice(-4) : s
}
async function loadDatasets() {
  try { datasets.value = await api.datasets() || [] } catch (e) { datasets.value = [] }
}
async function onCreateDatasetChange(id) {
  createForm.sourceRunId = null
  createForm.sourceItemId = null
  sourceRunItems.value = []
  datasetDetail.value = null
  if (!id) return
  try {
    datasetDetail.value = await api.datasetDetail(id)
    createForm.sourceRunId = (datasetDetail.value.runs || [])[0]?.id || null
    if (createForm.sourceRunId) await onSourceRunChange(createForm.sourceRunId)
  } catch (e) { message.error('数据集加载失败：' + (e.message || e)) }
}
async function openCreate() {
  if (!datasets.value.length) await loadDatasets()
  Object.assign(createForm, {
    datasetId: datasets.value[0]?.id || null, sourceRunId: null, sourceItemId: null,
    title: '', phenomenon: '', rootCauseCategory: 'UNKNOWN', owner: '', agentKey: ''
  })
  datasetDetail.value = null
  showCreate.value = true
  if (createForm.datasetId) onCreateDatasetChange(createForm.datasetId)
}
async function doCreate() {
  if (!createForm.datasetId) { message.error('请选择数据集'); return }
  if (!createForm.title.trim()) { message.error('请填写标题'); return }
  if (!createForm.phenomenon.trim()) { message.error('请填写现象'); return }
  creating.value = true
  try {
    await api.createQualityCase({
      datasetId: createForm.datasetId,
      sourceRunId: createForm.sourceRunId || undefined,
      sourceItemId: createForm.sourceItemId || undefined,
      agentKey: createForm.agentKey.trim() || undefined,
      title: createForm.title.trim(),
      phenomenon: createForm.phenomenon.trim(),
      rootCauseCategory: createForm.rootCauseCategory || 'UNKNOWN',
      owner: createForm.owner.trim() || undefined
    })
    message.success('质量案例已登记')
    showCreate.value = false
    await load()
  } catch (e) {
    message.error('登记失败：' + (e.message || e))
  } finally { creating.value = false }
}
async function load() {
  loading.value = true
  try {
    rows.value = await api.qualityCases({
      status: status.value || undefined,
      owner: owner.value.trim() || undefined
    })
  } catch (e) {
    message.error('加载质量案例失败：' + (e.message || e))
  } finally {
    loading.value = false
  }
}
async function open(row) {
  selected.value = row
  fixNote.value = row.fixNote || ''
  regressionRunId.value = row.regressionRunId || ''
  showDetail.value = true
}
function replace(updated) {
  selected.value = updated
  const idx = rows.value.findIndex((r) => r.id === updated.id)
  if (idx >= 0) rows.value.splice(idx, 1, updated)
}
async function moveStatus(target) {
  try {
    replace(await api.patchQualityCase(selected.value.id, {
      status: target, fixNote: fixNote.value.trim() || undefined
    }))
    message.success(`状态已更新为 ${target}`)
  } catch (e) {
    message.error('状态更新失败：' + (e.message || e))
  }
}
async function linkRegression() {
  if (!regressionRunId.value.trim()) { message.error('请填写回归 run ID'); return }
  try {
    replace(await api.linkQualityCaseRegression(selected.value.id, {
      runId: regressionRunId.value.trim()
    }))
    message.success('已关联回归 run')
  } catch (e) {
    message.error('关联失败：' + (e.message || e))
  }
}
async function doClose() {
  closing.value = true
  try {
    replace(await api.closeQualityCase(selected.value.id))
    message.success('案例已关闭')
  } catch (e) {
    message.error('关闭失败：' + (e.message || e))
  } finally {
    closing.value = false
  }
}
onMounted(() => { load(); loadDatasets() })
</script>

<style scoped>
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 0 12px; }
.case-head { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; }
.case-title { font-weight: 600; font-size: 15px; }
.meta-grid { display: grid; grid-template-columns: 80px 1fr; gap: 6px 10px; font-size: 12.5px; }
.meta-grid span { color: var(--text-muted); }
.meta-grid b { font-weight: 500; overflow-wrap: anywhere; }
.section-title { font-weight: 600; font-size: 13px; margin: 8px 0 6px; }
.phenomenon { white-space: pre-wrap; font-size: 13px; color: var(--text-secondary); line-height: 1.6; }
.mb8 { margin-bottom: 8px; }
.mono { font-family: "JetBrains Mono", ui-monospace, Menlo, monospace; }
</style>
