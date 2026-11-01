<template>
  <div class="base-page">
    <div class="head-stack">
      <div class="page-toolbar">
        <div class="toolbar-actions">
          <n-button size="small" @click="openCredentials">签名凭据</n-button>
          <n-button size="small" :loading="loading" @click="load">刷新</n-button>
          <n-button type="primary" size="small" @click="openCreate">＋ 绑定实验</n-button>
        </div>
      </div>
    </div>

    <n-card size="small" class="table-card">
      <n-data-table class="main-table" flex-height style="height: 100%" :columns="columns" :data="rows" size="small" :bordered="false"
        :loading="loading" :row-key="(r) => r.id" :scroll-x="725"
        :row-props="(r) => ({ style: 'cursor:pointer', onClick: () => openDetail(r) })">
        <template #empty>
          <EmptyState text="还没有绑定外部业务实验。绑定后可以导入版本化报告，作为业务收益证据单独展示。" action-text="绑定实验" @action="openCreate" />
        </template>
      </n-data-table>
    </n-card>

    <n-drawer v-model:show="showDetail" :width="720">
      <n-drawer-content v-if="detail" title="业务实验详情" closable>
        <div class="meta-grid">
          <span>来源</span><b>{{ detail.source }}</b>
          <span>外部实验</span><b class="mono">{{ detail.externalExperimentId }}</b>
          <span>设计</span><b>{{ detail.designType }}</b>
          <span>主指标</span><b>{{ detail.primaryMetric }}</b>
          <span>目标</span><b>{{ detail.target || '—' }}</b>
          <span>最小样本</span><b class="num">{{ detail.minSampleSize ?? '—' }}</b>
          <span>绑定 run</span><b class="mono">{{ detail.runId || '—' }}</b>
          <span>候选指纹</span><b class="mono">{{ detail.candidateFingerprint || '—' }}</b>
        </div>
        <n-divider />
        <div class="section-title">最新证据</div>
        <template v-if="detail.latestEvidence">
          <div class="run-summary sub">
            <span>版本 <b>{{ detail.latestEvidence.reportVersion }}</b></span>
            <span>结论 <n-tag size="tiny" :bordered="false" :type="decisionType(detail.latestEvidence.decision)">{{ enumText('decision', detail.latestEvidence.decision) }}</n-tag></span>
            <span>样本 <b class="num">{{ detail.latestEvidence.qualityChecks?.sampleSize ?? '—' }}</b></span>
            <span>SRM <b>{{ detail.latestEvidence.qualityChecks?.srmOk ? 'OK' : 'CHECK' }}</b></span>
            <span>护栏 <b>{{ detail.latestEvidence.qualityChecks?.guardrailsOk ? 'OK' : 'CHECK' }}</b></span>
            <span>签名 <b>{{ detail.latestEvidence.signatureVerified ? '已验证' : '未验证' }}</b></span>
          </div>
          <pre class="json-box">{{ JSON.stringify(detail.latestEvidence.metrics || {}, null, 2) }}</pre>
        </template>
        <div v-else class="page-sub">还没有导入报告版本。</div>
        <n-divider />
        <div class="section-title">证据版本（{{ (detail.evidence || []).length }}）</div>
        <n-data-table :columns="evidenceColumns" :data="detail.evidence || []" size="small"
          :bordered="false" :max-height="280" :row-key="(r) => r.id" />
        <div class="page-sub" style="margin-top:12px">
          业务证据需要外部实验报告；没有 p 值/置信区间时为 INCONCLUSIVE，观测型实验固定 INCONCLUSIVE。
        </div>
      </n-drawer-content>
    </n-drawer>

    <Modal v-model:show="showCreate" :width="680" title="绑定外部业务实验">
      <n-form label-placement="top">
        <div class="form-grid">
          <n-form-item label="数据集（用于选择 run）">
            <n-select v-model:value="createForm.datasetId" :options="datasetOptions"
              placeholder="可选" @update:value="onDatasetChange" />
          </n-form-item>
          <n-form-item label="绑定 run">
            <n-select v-model:value="createForm.runId" :options="runOptions" clearable
              placeholder="可选：关联一次离线运行" />
          </n-form-item>
        </div>
        <div class="form-grid">
          <n-form-item label="来源 source" required>
            <n-input v-model:value="createForm.source" placeholder="如 growth-platform" />
          </n-form-item>
          <n-form-item label="外部实验 ID" required>
            <n-input v-model:value="createForm.externalExperimentId" placeholder="exp-2026-10-001" />
          </n-form-item>
          <n-form-item label="设计类型">
            <n-select v-model:value="createForm.designType" :options="designOptions" />
          </n-form-item>
          <n-form-item label="主指标" required>
            <n-input v-model:value="createForm.primaryMetric" placeholder="如 conversion" />
          </n-form-item>
          <n-form-item label="最小样本量">
            <n-input-number v-model:value="createForm.minSampleSize" :min="0" style="width:100%" />
          </n-form-item>
          <n-form-item label="目标">
            <n-input v-model:value="createForm.target" placeholder="如 提升下单转化" />
          </n-form-item>
        </div>
        <n-form-item label="需要签名">
          <n-switch v-model:value="createForm.requireSignature" />
          <span class="page-sub" style="margin-left:8px">开启后导入证据必须带 keyId/signature/signedAt/nonce</span>
        </n-form-item>
      </n-form>
      <template #footer>
        <n-button size="small" :disabled="saving" @click="showCreate = false">取消</n-button>
        <n-button type="primary" size="small" :loading="saving" @click="doCreate">绑定</n-button>
      </template>
    </Modal>

    <Modal v-model:show="showCredentials" :width="760" title="业务证据签名凭据">
      <n-data-table :columns="credentialColumns" :data="credentials" size="small"
        :bordered="false" :row-key="(r) => r.id" :max-height="220" />
      <n-divider />
      <n-form label-placement="top">
        <div class="form-grid">
          <n-form-item label="Key ID"><n-input v-model:value="credentialForm.keyId" class="mono" /></n-form-item>
          <n-form-item label="Source"><n-input v-model:value="credentialForm.source" placeholder="如 growth-platform" /></n-form-item>
        </div>
        <n-form-item label="名称（可选）"><n-input v-model:value="credentialForm.name" /></n-form-item>
        <n-form-item label="RSA 公钥（Base64 DER）">
          <n-input v-model:value="credentialForm.publicKey" type="textarea" :rows="4" class="mono" />
        </n-form-item>
      </n-form>
      <template #footer>
        <n-button size="small" @click="showCredentials = false">关闭</n-button>
        <n-button type="primary" size="small" :loading="savingCredential" @click="createCredential">登记凭据</n-button>
      </template>
    </Modal>
  </div>
</template>

<script setup>
import { computed, h, onMounted, reactive, ref } from 'vue'
import { NButton, NTag, useMessage } from 'naive-ui'
import api from '@/api'
import { enumText } from '@/common/i18n'
import EmptyState from '@/components/EmptyState.vue'

const message = useMessage()
const rows = ref([]), loading = ref(false)
const showDetail = ref(false), detail = ref(null)
const showCreate = ref(false), saving = ref(false)
const datasets = ref([]), datasetDetail = ref(null)
const createForm = reactive({
  datasetId: null, runId: null, source: '', externalExperimentId: '',
  designType: 'RANDOMIZED', primaryMetric: '', minSampleSize: 1000, target: '', requireSignature: false
})
const datasetOptions = computed(() => datasets.value.map((d) => ({
  label: `${d.datasetKey} (${d.itemCount ?? 0} 条)`, value: d.id
})))
const runOptions = computed(() => (datasetDetail.value?.runs || []).map((r, i, arr) => ({
  label: `#${arr.length - i} ${r.evaluatorKey || (r.evaluatorKeys || []).join(' + ') || 'run'} · ${String(r.startedAt || '').slice(5, 16).replace('T', ' ')}`,
  value: r.id
})))
const designOptions = [
  { label: 'RANDOMIZED（随机实验）', value: 'RANDOMIZED' },
  { label: 'OBSERVATIONAL（观测）', value: 'OBSERVATIONAL' }
]

const columns = [
  { title: '来源 / 外部实验', key: 'source', minWidth: 220, ellipsis: { tooltip: true }, render: (r) => h('div', {
      style: 'display:flex;flex-direction:column;gap:2px;min-width:0;overflow:hidden'
    }, [
      h('span', { style: 'font-weight:600;white-space:nowrap;overflow:hidden;text-overflow:ellipsis' }, r.source),
      h('span', { class: 'mono page-sub', style: 'white-space:nowrap;overflow:hidden;text-overflow:ellipsis' }, r.externalExperimentId)
    ]) },
  { title: '设计', key: 'designType', width: 90, render: (r) => r.designType === 'RANDOMIZED' ? '随机' : r.designType === 'OBSERVATIONAL' ? '观测' : (r.designType || '—') },
  { title: '主指标', key: 'primaryMetric', width: 105, ellipsis: { tooltip: true }, render: (r) => r.primaryMetric || '—' },
  { title: '绑定 run', key: 'runId', minWidth: 110, ellipsis: { tooltip: true }, render: (r) => h('span', { class: 'mono', title: r.runId || '' }, shortId(r.runId)) },
  { title: '最新证据', key: 'latestEvidence', width: 110, render: (r) => h(NTag, {
      size: 'tiny', bordered: false, type: decisionType(r.latestEvidence?.decision)
    }, { default: () => decisionText(r.latestEvidence?.decision) }) },
  { title: '创建时间', key: 'createdAt', width: 125, ellipsis: { tooltip: true }, render: (r) => fmt(r.createdAt) }
]
const evidenceColumns = [
  { title: '版本', key: 'reportVersion', width: 120 },
  { title: '结论', key: 'decision', width: 110, render: (r) => h(NTag, { size: 'tiny', bordered: false, type: decisionType(r.decision) }, { default: () => enumText('decision', r.decision) }) },
  { title: '样本', key: 'sampleSize', width: 90, render: (r) => r.qualityChecks?.sampleSize ?? '—' },
  { title: '相对 lift', key: 'lift', width: 110, render: (r) => r.metrics?.relativeLift ?? r.metrics?.absoluteDiff ?? '—' },
  { title: '签名', key: 'signatureVerified', width: 90, render: (r) => r.signatureVerified ? '已验证' : '未验证' },
  { title: '接收时间', key: 'receivedAt', width: 160, render: (r) => fmt(r.receivedAt) }
]
const credentialColumns = [
  { title: 'Key ID', key: 'keyId', width: 160, ellipsis: { tooltip: true }, render: (r) => h('span', { class: 'mono' }, r.keyId) },
  { title: 'Source', key: 'source', width: 150 },
  { title: '名称', key: 'name', minWidth: 120, ellipsis: { tooltip: true } },
  { title: '状态', key: 'status', width: 90, render: (r) => h(NTag, { size: 'tiny', bordered: false, type: r.status === 'ACTIVE' ? 'success' : 'default' }, { default: () => enumText('businessStatus', r.status) }) }
]

const showCredentials = ref(false), savingCredential = ref(false), credentials = ref([])
const credentialForm = reactive({ keyId: '', source: '', name: '', publicKey: '' })

function fmt(v) { return v ? String(v).replace('T', ' ').slice(0, 19) : '—' }
function shortId(v) { if (!v) return '—'; const s = String(v); return s.length > 14 ? s.slice(0, 8) + '…' + s.slice(-4) : s }
function decisionType(d) {
  return d === 'POSITIVE' ? 'success' : d === 'NEGATIVE' ? 'error' : d === 'BLOCKED' ? 'warning' : 'default'
}
function decisionText(d) {
  return ({ POSITIVE: '正向', NEGATIVE: '负向', INCONCLUSIVE: '不确定', BLOCKED: '阻断' }[d] || d || '无证据')
}

async function load() {
  loading.value = true
  try { rows.value = await api.businessExperiments() || [] } catch (e) {
    message.error('加载业务实验失败：' + (e.message || e))
  } finally { loading.value = false }
}
async function openDetail(row) {
  try {
    detail.value = await api.businessExperimentDetail(row.id)
    showDetail.value = true
  } catch (e) { message.error('加载详情失败：' + (e.message || e)) }
}
async function loadDatasets() {
  if (datasets.value.length) return
  try { datasets.value = await api.datasets() || [] } catch (e) { datasets.value = [] }
}
async function openCreate() {
  await loadDatasets()
  Object.assign(createForm, {
    datasetId: datasets.value[0]?.id || null, runId: null, source: '',
    externalExperimentId: '', designType: 'RANDOMIZED', primaryMetric: '',
    minSampleSize: 1000, target: '', requireSignature: false
  })
  datasetDetail.value = null
  showCreate.value = true
  if (createForm.datasetId) onDatasetChange(createForm.datasetId)
}
async function onDatasetChange(id) {
  createForm.runId = null
  datasetDetail.value = null
  if (!id) return
  try {
    datasetDetail.value = await api.datasetDetail(id)
    createForm.runId = (datasetDetail.value.runs || [])[0]?.id || null
  } catch (e) { message.error('数据集加载失败：' + (e.message || e)) }
}
async function doCreate() {
  if (!createForm.source.trim() || !createForm.externalExperimentId.trim() || !createForm.primaryMetric.trim()) {
    message.error('来源、外部实验 ID、主指标必填')
    return
  }
  saving.value = true
  try {
    await api.createBusinessExperiment({
      source: createForm.source.trim(),
      externalExperimentId: createForm.externalExperimentId.trim(),
      runId: createForm.runId || undefined,
      designType: createForm.designType,
      primaryMetric: createForm.primaryMetric.trim(),
      minSampleSize: createForm.minSampleSize ?? undefined,
      target: createForm.target.trim() || undefined,
      requireSignature: createForm.requireSignature
    })
    message.success('业务实验已绑定')
    showCreate.value = false
    await load()
  } catch (e) {
    message.error('绑定失败：' + (e.message || e))
  } finally { saving.value = false }
}
async function openCredentials() {
  showCredentials.value = true
  try { credentials.value = await api.businessCredentials() || [] } catch (e) { credentials.value = [] }
}
async function createCredential() {
  if (!credentialForm.keyId.trim() || !credentialForm.source.trim() || !credentialForm.publicKey.trim()) {
    message.error('Key ID、Source、公钥必填')
    return
  }
  savingCredential.value = true
  try {
    await api.createBusinessCredential({
      keyId: credentialForm.keyId.trim(),
      source: credentialForm.source.trim(),
      name: credentialForm.name.trim() || undefined,
      publicKey: credentialForm.publicKey.trim()
    })
    message.success('凭据已登记')
    credentialForm.keyId = ''
    credentialForm.source = ''
    credentialForm.name = ''
    credentialForm.publicKey = ''
    credentials.value = await api.businessCredentials() || []
  } catch (e) {
    message.error('登记失败：' + (e.message || e))
  } finally { savingCredential.value = false }
}

onMounted(load)
</script>

<style scoped>
.stack { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.meta-grid { display: grid; grid-template-columns: 90px 1fr; gap: 6px 10px; font-size: 12.5px; }
.meta-grid span { color: var(--text-muted); }
.meta-grid b { font-weight: 500; overflow-wrap: anywhere; }
.section-title { font-weight: 600; font-size: 13px; margin: 8px 0 6px; }
.json-box { max-height: 260px; overflow: auto; padding: 10px; background: var(--bg-subtle); border: 1px solid var(--border); border-radius: 8px; font-size: 12px; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 0 12px; }
</style>
