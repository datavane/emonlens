<template>
  <div class="base-page">
    <div class="head-stack">
      <div class="page-toolbar">
        <div class="toolbar-actions">
          <n-button size="small" :loading="loading" @click="load">刷新</n-button>
          <n-button size="small" @click="router.push('/datasets')">管理数据集</n-button>
        </div>
      </div>
    </div>

    <div class="kpi-grid">
      <StatCard title="数据集" :value="totals.datasets" hint="当前工作空间" />
      <StatCard title="最近运行" :value="totals.runs" hint="有实验的数据集" />
      <StatCard title="可用通过 Gate" :value="totals.passGates"
        :value-style="totals.passGates ? 'color: var(--success)' : ''" hint="verification.usable=true" />
      <StatCard title="待处理 Gate" :value="totals.blockedGates"
        :value-style="totals.blockedGates ? 'color: var(--warn)' : ''" hint="未生成 / BLOCKED / FAIL / 已失效" />
      <StatCard title="待关闭案例" :value="totals.openCases"
        :value-style="totals.openCases ? 'color: var(--warn)' : ''" hint="待处理 / 调查中 / 已修复" />
      <StatCard title="未完成校准" :value="totals.uncalibrated"
        :value-style="totals.uncalibrated ? 'color: var(--warn)' : ''" hint="含 LLM Judge 的最近运行" />
    </div>

    <n-card size="small" class="mt12" title="数据集质量状态">
      <template #header-extra>
        <span class="page-sub">Gate 状态按最近一次 run 的最新 Gate + verification 计算</span>
      </template>
      <n-data-table :columns="columns" :data="rows" size="small" :bordered="false" :max-height="480"
        :loading="loading" :row-key="(r) => r.datasetId"
        :row-props="(r) => ({ style: 'cursor:pointer', onClick: () => router.push('/datasets/' + r.datasetId) })">
        <template #empty>
          <EmptyState v-if="loadError" :text="loadError" action-text="重试" @action="load" />
          <EmptyState v-else text="还没有数据集。先到「数据集」从 Trace 收录样本，再跑第一次实验。" action-text="去数据集" @action="router.push('/datasets')" />
        </template>
      </n-data-table>
    </n-card>

    <n-card size="small" class="mt12" title="质量闭环怎么走">
      <div class="flow-hint">
        <div class="fh-step"><b>1</b> 数据集：样本 + 用途/风险/切分</div>
        <div class="fh-step"><b>2</b> 评估器与套件：确定性检查优先，Judge 需校准</div>
        <div class="fh-step"><b>3</b> 运行：冻结 revision，记录覆盖率/无法判定/执行错误</div>
        <div class="fh-step"><b>4</b> 校准：盲审 Judge，至少 10 条且一致率 ≥ 80%</div>
        <div class="fh-step"><b>5</b> Gate：通过 / 未通过 / 阻断，绑定候选指纹与有效期</div>
        <div class="fh-step"><b>6</b> 案例：失败登记 → 修复 → 回归 run → 可用通过 Gate 关闭</div>
      </div>
    </n-card>
  </div>
</template>

<script setup>
import { computed, h, onMounted, ref } from 'vue'
import { NButton, NTag } from 'naive-ui'
import { useRouter } from 'vue-router'
import api from '@/api'
import { enumText } from '@/common/i18n'
import StatCard from '@/components/StatCard.vue'
import EmptyState from '@/components/EmptyState.vue'

const router = useRouter()
const loading = ref(false)
const loadError = ref('')
const rows = ref([])
const totals = ref({ datasets: 0, runs: 0, passGates: 0, blockedGates: 0, openCases: 0, uncalibrated: 0 })

const columns = [
  { title: '数据集', key: 'datasetKey', minWidth: 150, ellipsis: { tooltip: true }, render: (r) => h('div', {
      style: 'display:flex;flex-direction:column;gap:2px;min-width:0;overflow:hidden'
    }, [
      h('span', { class: 'mono', style: 'color:var(--primary);font-weight:600;overflow:hidden;text-overflow:ellipsis;white-space:nowrap' }, r.datasetKey),
      h('span', { class: 'page-sub', style: 'overflow:hidden;text-overflow:ellipsis;white-space:nowrap' }, r.description || '—')
    ]) },
  { title: '样本', key: 'items', width: 55, align: 'right', render: (r) => h('span', { class: 'num' }, `${r.activeItems}/${r.totalItems}`) },
  { title: '最近运行', key: 'run', minWidth: 160, ellipsis: { tooltip: true }, render: (r) => r.latestRun
    ? h('div', { style: 'display:flex;flex-direction:column;gap:2px;min-width:0;overflow:hidden' }, [
        h('span', { style: 'white-space:nowrap;overflow:hidden;text-overflow:ellipsis' }, [statusTag(r.latestRun.status), h('span', { class: 'page-sub', style: 'margin-left:6px' }, r.latestRun.finishedAt ? fmtTs(r.latestRun.finishedAt) : '')]),
        h('span', { class: 'page-sub', style: 'white-space:nowrap;overflow:hidden;text-overflow:ellipsis' }, `通过率 ${pct(r.latestRun.passRate)} · 覆盖 ${pct(r.latestRun.coverage)} · ${r.latestRun.itemCount ?? 0} 条`)
      ]) : h('span', { class: 'page-sub' }, '还没有运行') },
  { title: 'Judge 校准', key: 'calibration', minWidth: 130, ellipsis: { tooltip: true }, render: (r) => r.calibration
    ? h('div', { style: 'display:flex;flex-direction:column;gap:2px;min-width:0;overflow:hidden' }, [
        h('span', { style: 'white-space:nowrap;overflow:hidden;text-overflow:ellipsis' }, [calibrationTag(r.calibration.status), h('span', { class: 'page-sub', style: 'margin-left:6px' }, `${r.calibration.reviewed}/${r.calibration.reviewable}`)]),
        h('span', { class: 'page-sub', style: 'white-space:nowrap;overflow:hidden;text-overflow:ellipsis' }, `一致率 ${pct(r.calibration.agreementRate)} · κ ${r.calibration.kappa ?? '—'}`)
      ]) : h('span', { class: 'page-sub' }, r.hasJudge ? '待加载' : '无 Judge 评估器') },
  { title: '准入 Gate', key: 'gate', minWidth: 150, ellipsis: { tooltip: true }, render: (r) => r.gate
    ? h('div', { style: 'display:flex;flex-direction:column;gap:2px;min-width:0;overflow:hidden' }, [
        h('span', { style: 'white-space:nowrap;overflow:hidden;text-overflow:ellipsis' }, [decisionTag(r.gate.decision), h('span', { class: 'page-sub', style: 'margin-left:6px' }, r.gateUsable ? '当前可用' : '已不可用')]),
        h('span', { class: 'page-sub', style: 'white-space:nowrap;overflow:hidden;text-overflow:ellipsis' }, `有效至 ${fmtTs(r.gate.validUntil)} · ${r.gate.policyKey || '—'}`)
      ]) : h('span', { class: 'page-sub' }, '尚未生成') },
  { title: '下一步', key: 'next', minWidth: 130, render: (r) => h(NButton, {
      size: 'tiny', type: r.next.type, secondary: true, onClick: (e) => { e.stopPropagation(); r.next.go() }
    }, { default: () => r.next.label }) }
]

function pct(v) {
  return v == null ? '—' : Number(v).toFixed(1).replace(/\.0$/, '') + '%'
}
function fmtTs(v) {
  return v ? String(v).replace('T', ' ').slice(0, 16) : '—'
}
function statusTag(status) {
  const type = status === 'COMPLETED' ? 'success' : status === 'RUNNING' || status === 'PENDING' ? 'info'
    : status === 'CANCELLED' ? 'default' : 'warning'
  return h(NTag, { size: 'tiny', bordered: false, type, title: status }, { default: () => enumText('runStatus', status) || '—' })
}
function decisionTag(decision) {
  const type = decision === 'PASS' ? 'success' : decision === 'FAIL' ? 'error' : 'warning'
  return h(NTag, { size: 'tiny', bordered: false, type, title: decision }, { default: () => enumText('gateStatus', decision) || '—' })
}
function calibrationTag(status) {
  const type = status === 'OK' ? 'success' : status === 'LOW_AGREEMENT' ? 'error'
    : status === 'INSUFFICIENT_SAMPLE' ? 'warning' : 'info'
  return h(NTag, { size: 'tiny', bordered: false, type, title: status }, { default: () => calibrationText(status) || '—' })
}

function calibrationText(status) {
  return ({ OK: '已校准', IN_PROGRESS: '校准中', INSUFFICIENT_SAMPLE: '样本不足', LOW_AGREEMENT: '一致率低' }[status] || status)
}

function nextAction(row) {
  if (!row.latestRun) return { label: '去运行实验', type: 'primary', go: () => router.push('/datasets/' + row.datasetId) }
  if (row.hasJudge && (!row.calibration || row.calibration.status !== 'OK')) {
    return { label: '去完成校准', type: 'warning', go: () => router.push(`/datasets/${row.datasetId}/runs/${row.latestRun.id}`) }
  }
  if (!row.gate) return { label: '生成 Gate', type: 'primary', go: () => router.push(`/datasets/${row.datasetId}/runs/${row.latestRun.id}`) }
  if (row.gate.decision !== 'PASS' || !row.gateUsable) {
    return { label: '查看 Gate 原因', type: 'warning', go: () => router.push(`/datasets/${row.datasetId}/runs/${row.latestRun.id}`) }
  }
  if (row.openCases > 0) return { label: '处理质量案例', type: 'warning', go: () => router.push('/quality-cases') }
  return { label: '查看运行报告', type: 'success', go: () => router.push(`/datasets/${row.datasetId}/runs/${row.latestRun.id}`) }
}

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const [datasets, cases, evaluatorPage] = await Promise.all([
      api.datasets(),
      api.qualityCases({}).catch(() => []),
      api.evaluators({ pageNumber: 1, pageSize: 200 }).catch(() => ({ records: [] }))
    ])
    const evaluatorType = {}
    for (const e of evaluatorPage.records || []) evaluatorType[e.evaluatorKey] = e.type

    const result = await Promise.all((datasets || []).map(async (d) => {
      // 轻量 runs 接口替代完整 datasetDetail：聚合页不需要加载每个数据集的全部样本
      let runs = []
      try { runs = await api.datasetRuns(d.id) } catch (e) { runs = [] }
      const latestRun = runs[0] || null
      const activeItems = d.activeItemCount ?? d.itemCount ?? 0
      const totalItems = d.itemCount ?? 0
      const openCases = (cases || []).filter((c) => c.datasetId === d.id
        && c.status !== 'CLOSED' && c.status !== 'REJECTED').length

      let gate = null, gateUsable = false, calibration = null
      const judgeKeys = latestRun
        ? (latestRun.evaluatorKeys || []).filter((k) => evaluatorType[k] === 'LLM_JUDGE')
        : []
      if (latestRun) {
        const [gateData, calData] = await Promise.all([
          api.latestQualityGate(latestRun.id).catch(() => null),
          judgeKeys.length
            ? Promise.all(judgeKeys.map((k) => api.judgeCalibration(d.id, latestRun.id, k).catch(() => null)))
            : Promise.resolve([])
        ])
        gate = gateData
        if (gate?.id) {
          const verification = await api.verifyQualityGate(gate.id).catch(() => null)
          gateUsable = !!verification?.usable
        }
        const summaries = (calData || []).map((c) => c?.summary).filter(Boolean)
        if (summaries.length) {
          calibration = summaries.find((s) => s.status !== 'OK') || summaries[0]
          if (summaries.length > 1) calibration = { ...calibration, judgeCount: summaries.length }
        }
      }

      const row = {
        datasetId: d.id, datasetKey: d.datasetKey, description: d.description,
        totalItems, activeItems, latestRun, gate, gateUsable, calibration,
        hasJudge: judgeKeys.length > 0, judgeCount: judgeKeys.length, openCases
      }
      row.next = nextAction(row)
      return row
    }))
    rows.value = result
    totals.value = {
      datasets: result.length,
      runs: result.filter((r) => r.latestRun).length,
      passGates: result.filter((r) => r.gate?.decision === 'PASS' && r.gateUsable).length,
      blockedGates: result.filter((r) => r.latestRun && (!r.gate || r.gate.decision !== 'PASS' || !r.gateUsable)).length,
      openCases: result.reduce((n, r) => n + r.openCases, 0),
      uncalibrated: result.filter((r) => r.hasJudge && r.calibration?.status !== 'OK').length
    }
  } catch (e) {
    rows.value = []
    totals.value = { datasets: 0, runs: 0, passGates: 0, blockedGates: 0, openCases: 0, uncalibrated: 0 }
    loadError.value = e.message || '质量总览加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.kpi-grid { display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); gap: 10px; }
.ds-cell { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.ds-name { color: var(--primary); font-weight: 600; }
.stack { display: flex; flex-direction: column; gap: 2px; }
.flow-hint { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8px 16px; }
.fh-step { font-size: 12px; color: var(--text-secondary); line-height: 1.6; }
.fh-step b { display: inline-flex; width: 18px; height: 18px; align-items: center; justify-content: center; margin-right: 6px; border-radius: 50%; background: var(--primary-bg); color: var(--primary); font-size: 11px; }
@media (max-width: 1200px) {
  .kpi-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); }
}
@media (max-width: 760px) {
  .kpi-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .flow-hint { grid-template-columns: 1fr; }
}
</style>
