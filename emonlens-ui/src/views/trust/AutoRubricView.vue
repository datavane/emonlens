<template>
  <div class="base-page">
    <div class="head-stack">
      <div class="page-toolbar">
        <div class="toolbar-filters">
          <n-select v-model:value="datasetId" :options="datasetOptions" size="small"
            placeholder="选择数据集" style="width: 260px" @update:value="onDatasetChange" />
        </div>
      </div>
    </div>

    <n-card size="small" title="新建候选审计">
      <div class="form-grid">
        <n-form-item label="源 run（必须 COMPLETED）">
          <n-select v-model:value="sourceRunId" :options="runOptions" size="small"
            placeholder="选择源 run" />
        </n-form-item>
        <n-form-item label="审计模型">
          <n-input v-model:value="judgeModel" size="small" placeholder="gpt-4o-mini" />
        </n-form-item>
        <n-form-item label="最大调用数">
          <n-input-number v-model:value="maxJudgeCalls" size="small" :min="1" :max="400" />
        </n-form-item>
        <n-form-item label="自动生成候选">
          <n-checkbox v-model:checked="generate">从非 HOLDOUT 失败样本生成</n-checkbox>
        </n-form-item>
        <n-form-item v-if="generate" label="最多生成">
          <n-input-number v-model:value="maxCandidates" size="small" :min="1" :max="10" />
        </n-form-item>
        <n-form-item label="Bootstrap 轮数">
          <n-input-number v-model:value="bootstrapRounds" size="small" :min="0" :max="1000" />
        </n-form-item>
        <n-form-item label="最低选择频率">
          <n-input-number v-model:value="minSelectionFrequency" size="small" :min="0" :max="1" :step="0.05" />
        </n-form-item>
        <n-form-item label="语义去重">
          <n-checkbox v-model:checked="semanticDedup">生成候选时调用 LLM 判断语义重复</n-checkbox>
        </n-form-item>
        <n-form-item label="CV 折数（0 关闭，2–5）">
          <n-select v-model:value="cvFolds" size="small"
            :options="[0, 2, 3, 4, 5].map((v) => ({ label: v === 0 ? '关闭' : v + ' 折', value: v }))" />
        </n-form-item>
      </div>

      <n-data-table :columns="candidateColumns" :data="draftCandidates" size="small"
        :bordered="false" :row-key="(r) => r.uid" :scroll-x="840" class="mt8" :max-height="280" />
      <div class="row-between mt8">
        <n-button size="small" @click="addDraftCandidate">＋ 添加候选</n-button>
        <n-button type="primary" size="small" :loading="creating" :disabled="!canCreate"
          @click="createRun">开始审计</n-button>
      </div>
    </n-card>

    <n-card size="small" class="mt12" title="审计记录">
      <n-data-table :columns="runColumns" :data="runs" size="small" :bordered="false" :max-height="320"
        :loading="loading" :row-key="(r) => r.id" />
      <div v-if="!loading && !runs.length" class="page-sub mt8">还没有审计记录。</div>
    </n-card>

    <Modal v-model:show="showDetail" :width="980" :title="detail ? '审计详情' : ''">
      <template v-if="detail">
        <div class="page-sub" style="margin-bottom:10px">
          状态 {{ enumText('rubricRunStatus', detail.status) }} · 审计集 {{ detail.auditSplit }} ·
          可用样本 {{ detail.auditCount }} · 候选 {{ detail.candidateCount }} ·
          提议 {{ detail.proposedCount }} · 调用 {{ detail.judgeCalls }}/{{ detail.maxJudgeCalls }} ·
          Bootstrap {{ detail.bootstrapRounds }} 轮 / 最低频率 {{ detail.minSelectionFrequency }}
          <template v-if="detail.selection">
            · CV {{ detail.selection.folds }} 折 / meanF1 {{ detail.selection.cv?.meanF1 ?? '—' }}
          </template>
        </div>
        <n-alert v-if="detail.selection" type="info" :bordered="false" class="mb8">
          K 折贪心选择：{{ (detail.selection.selectedKeys || []).join(' + ') || '未选出规则' }}；
          全量审计 precision {{ detail.selection.fullAudit?.precision }} /
          recall {{ detail.selection.fullAudit?.recall }} /
          F1 {{ detail.selection.fullAudit?.f1 }}。
          该集合只作候选规则组合建议，不自动发布。
        </n-alert>
        <n-alert v-if="detail.status === 'NO_AUDIT_SET'" type="warning" :bordered="false" class="mb8">
          HOLDOUT 中可用 PASS/FAIL 样本不足 5 条，本次没有产出候选。
        </n-alert>
        <n-alert v-if="detail.status === 'NO_FAILURES'" type="warning" :bordered="false" class="mb8">
          非 HOLDOUT 中没有失败样本，未调用候选生成。
        </n-alert>
        <n-data-table :columns="detailColumns" :data="detail.candidates || []" size="small"
          :bordered="false" :row-key="(r) => r.id" :max-height="380" />
      </template>
    </Modal>
  </div>
</template>

<script setup>
import { computed, h, onMounted, reactive, ref } from 'vue'
import { NButton, NCheckbox, NInput, NInputNumber, NPopconfirm, NTag, useMessage } from 'naive-ui'
import api from '@/api'
import { enumText } from '@/common/i18n'

const message = useMessage()
const datasets = ref([])
const datasetId = ref(null)
const runs = ref([])
const detail = ref(null)
const loading = ref(false)
const creating = ref(false)
const showDetail = ref(false)
const sourceRunId = ref(null)
const judgeModel = ref('gpt-4o-mini')
const maxJudgeCalls = ref(200)
const generate = ref(false)
const maxCandidates = ref(3)
const bootstrapRounds = ref(200)
const minSelectionFrequency = ref(0.6)
const cvFolds = ref(0)
const semanticDedup = ref(false)
let uid = 0

const draftCandidates = reactive([
  { uid: ++uid, key: 'citation', text: '回答必须给出可核验的引用', minPrecision: 0.8, minSupport: 3 }
])

const datasetOptions = computed(() => datasets.value.map((d) => ({
  label: `${d.datasetKey} (${d.itemCount ?? d.items?.length ?? 0} 条)`, value: d.id
})))

const runOptions = computed(() => {
  const d = datasets.value.find((x) => x.id === datasetId.value)
  return (d?.runs || [])
    .filter((r) => r.status === 'COMPLETED')
    .map((r, i, arr) => ({
      label: `#${arr.length - i} ${r.evaluatorKey || r.evaluatorKeys?.join(',') || 'run'} · ${String(r.startedAt || '').slice(5, 16).replace('T', ' ')}`,
      value: r.id
    }))
})

const canCreate = computed(() => datasetId.value && sourceRunId.value
  && (generate.value || (draftCandidates.length > 0
    && draftCandidates.every((c) => c.key && c.text))))

const candidateColumns = [
  { title: 'key', key: 'key', width: 150, render: (r) => h(NInput, {
      value: r.key, size: 'small', placeholder: 'citation',
      'onUpdate:value': (v) => { r.key = v }
    }) },
  { title: '候选规则', key: 'text', minWidth: 320, render: (r) => h(NInput, {
      value: r.text, size: 'small', placeholder: '回答必须给出可核验的引用',
      'onUpdate:value': (v) => { r.text = v }
    }) },
  { title: 'minPrecision', key: 'minPrecision', width: 130, render: (r) => h(NInputNumber, {
      value: r.minPrecision, size: 'small', min: 0, max: 1, step: 0.05,
      'onUpdate:value': (v) => { r.minPrecision = v }
    }) },
  { title: 'minSupport', key: 'minSupport', width: 120, render: (r) => h(NInputNumber, {
      value: r.minSupport, size: 'small', min: 1, max: 100,
      'onUpdate:value': (v) => { r.minSupport = v }
    }) },
  { title: '', key: 'actions', width: 70, render: (r) => h(NButton, {
      size: 'tiny', disabled: draftCandidates.length <= 1,
      onClick: () => removeDraftCandidate(r)
    }, { default: () => '删除' }) }
]

const runColumns = [
  { title: '状态', key: 'status', width: 130, render: (r) => h(NTag, {
      size: 'tiny', bordered: false,
      type: r.status === 'COMPLETED' ? 'success' : r.status === 'NO_AUDIT_SET' ? 'warning' : 'default'
    }, { default: () => enumText('rubricRunStatus', r.status) }) },
  { title: '源 run', key: 'sourceRunId', width: 130, render: (r) => h('span', { class: 'mono' }, r.sourceRunId.slice(0, 10) + '…') },
  { title: '审计样本', key: 'auditCount', width: 90 },
  { title: '候选', key: 'candidateCount', width: 70 },
  { title: '提议', key: 'proposedCount', width: 70 },
  { title: '调用', key: 'judgeCalls', width: 80 },
  { title: '时间', key: 'createdAt', width: 160, render: (r) => String(r.createdAt || '').replace('T', ' ').slice(0, 19) },
  { title: '', key: 'actions', width: 80, render: (r) => h(NButton, {
      size: 'tiny', onClick: () => openDetail(r)
    }, { default: () => '详情' }) }
]

const detailColumns = [
  { title: 'key', key: 'ruleKey', width: 120, render: (r) => h('span', { class: 'mono' }, r.ruleKey) },
  { title: '规则', key: 'ruleText', minWidth: 230, ellipsis: { tooltip: true } },
  { title: '来源', key: 'origin', width: 90 },
  { title: '状态', key: 'decision', width: 100, render: (r) => h(NTag, {
      size: 'tiny', bordered: false,
      type: r.decision === 'PROPOSED' || r.decision === 'APPROVED' ? 'success'
        : r.decision === 'REJECTED' ? 'error' : 'default'
    }, { default: () => enumText('rubricDecision', r.decision) }) },
  { title: 'precision', key: 'precision', width: 90, render: (r) => r.precision == null ? '—' : r.precision + '%' },
  { title: 'recall', key: 'recall', width: 80, render: (r) => r.recall == null ? '—' : r.recall + '%' },
  { title: 'support', key: 'support', width: 80 },
  { title: '稳定频率', key: 'selectionFrequency', width: 100,
    render: (r) => r.selectionFrequency == null ? '—' : r.selectionFrequency + '%' },
  { title: 'TP/FP/FN/TN', key: 'matrix', width: 120,
    render: (r) => `${r.truePositive}/${r.falsePositive}/${r.falseNegative}/${r.trueNegative}` },
  { title: '', key: 'actions', width: 150, render: (r) => {
      if (r.decision !== 'PROPOSED') return r.reviewedBy ? `by ${r.reviewedBy}` : ''
      return h('div', { style: 'display:flex;gap:6px' }, [
        h(NButton, { size: 'tiny', type: 'primary', onClick: () => decide(r, 'APPROVED') }, { default: () => '批准' }),
        h(NPopconfirm, { onPositiveClick: () => decide(r, 'REJECTED') }, {
          trigger: () => h(NButton, { size: 'tiny' }, { default: () => '拒绝' }),
          default: () => '拒绝只改候选状态，不删除审计记录。'
        })
      ])
    } }
]

async function loadDatasets() {
  datasets.value = await api.datasets() || []
  if (!datasetId.value && datasets.value.length) {
    datasetId.value = datasets.value[0].id
    onDatasetChange(datasetId.value)
  }
}

async function onDatasetChange(id) {
  sourceRunId.value = null
  let dataset = datasets.value.find((d) => d.id === id)
  if (!dataset) return
  // /datasets 列表不带 runs；首次进入时必须拉详情，否则源 run 永远为空。
  if (!dataset.runs) {
    try {
      dataset = await api.datasetDetail(id)
      const idx = datasets.value.findIndex((d) => d.id === id)
      if (idx >= 0) datasets.value[idx] = dataset
    } catch (e) {
      message.error('加载数据集详情失败：' + (e.message || e))
      return
    }
  }
  sourceRunId.value = (dataset.runs || []).find((r) => r.status === 'COMPLETED')?.id || null
  await loadRuns()
}

async function loadRuns() {
  if (!datasetId.value) return
  loading.value = true
  try { runs.value = await api.autoRubricRuns(datasetId.value) || [] } catch (e) {
    message.error('加载审计记录失败：' + (e.message || e))
  } finally { loading.value = false }
}

function addDraftCandidate() {
  if (draftCandidates.length >= 10) {
    message.warning('最多 10 条候选')
    return
  }
  draftCandidates.push({ uid: ++uid, key: '', text: '', minPrecision: 0.8, minSupport: 3 })
}

function removeDraftCandidate(row) {
  const i = draftCandidates.findIndex((x) => x.uid === row.uid)
  if (i >= 0) draftCandidates.splice(i, 1)
}

async function createRun() {
  creating.value = true
  try {
    const created = await api.createAutoRubricRun(datasetId.value, {
      sourceRunId: sourceRunId.value,
      judgeModel: judgeModel.value.trim() || null,
      maxJudgeCalls: maxJudgeCalls.value,
      generate: generate.value,
      maxCandidates: maxCandidates.value,
      bootstrapRounds: bootstrapRounds.value,
      minSelectionFrequency: minSelectionFrequency.value,
      cvFolds: cvFolds.value,
      semanticDedup: semanticDedup.value,
      candidates: draftCandidates.filter((c) => c.key.trim() && c.text.trim())
        .map((c) => ({
          key: c.key.trim(), text: c.text.trim(),
          minPrecision: c.minPrecision, minSupport: c.minSupport
        }))
    })
    message.success(created.status === 'NO_AUDIT_SET'
      ? 'HOLDOUT 样本不足，未产出候选'
      : created.status === 'NO_FAILURES'
        ? '没有非 HOLDOUT 失败样本，未生成候选'
        : `审计完成：${created.proposedCount}/${created.candidateCount} 条候选被提议`)
    await loadRuns()
    await openDetail(created)
  } catch (e) {
    message.error('创建审计失败：' + (e.message || e))
  } finally { creating.value = false }
}

async function openDetail(run) {
  try {
    detail.value = run.candidates ? run : await api.autoRubricRun(run.id)
    showDetail.value = true
  } catch (e) {
    message.error('加载详情失败：' + (e.message || e))
  }
}

async function decide(row, decision) {
  try {
    const updated = await api.decideAutoRubricCandidate(detail.value.id, row.id, {
      decision, note: decision === 'APPROVED' ? '人工批准进入候选池' : '人工拒绝'
    })
    const idx = detail.value.candidates.findIndex((c) => c.id === row.id)
    if (idx >= 0) detail.value.candidates.splice(idx, 1, updated)
    message.success(decision === 'APPROVED' ? '已批准' : '已拒绝')
    await loadRuns()
  } catch (e) {
    message.error('决策失败：' + (e.message || e))
  }
}

onMounted(async () => {
  try { await loadDatasets() } catch (e) { message.error('加载数据集失败：' + (e.message || e)) }
})
</script>

<style scoped>
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 0 12px; }
.row-between { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
</style>
