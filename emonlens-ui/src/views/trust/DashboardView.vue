<template>
  <div class="base-page">
    <div class="page-head">
      <div class="head-right">
        <n-select v-model:value="window" style="width: 130px" size="small"
          :options="[{label:'近 1 小时',value:'1h'},{label:'近 24 小时',value:'24h'},{label:'近 7 天',value:'7d'},{label:'近 30 天',value:'30d'}]"
          @update:value="load" />
        <n-select v-model:value="agentFilter" placeholder="全部 Agent" style="width: 180px" size="small" clearable
          :options="agentOptions" @update:value="load" />
        <n-select v-model:value="envFilter" placeholder="全部环境" style="width: 150px" size="small" clearable
          :options="envOptions" @update:value="load" />
        <n-button size="small" @click="router.push('/quality')">质量总览</n-button>
        <n-button size="small" @click="load">刷新</n-button>
      </div>
    </div>

    <n-alert v-if="loadError" type="error" :bordered="false" class="mt12">
      加载失败：{{ loadError }}
      <n-button text type="primary" size="small" @click="load">重试</n-button>
    </n-alert>
    <n-alert v-else-if="!loading && overall.status === 'INSUFFICIENT_EVIDENCE'" type="info" :bordered="false" class="mt12">
      当前信任分证据不足<template v-if="missingDimsText">（缺少：{{ missingDimsText }}）</template>。
      补齐方式：在「评估器」配置对应监控，或在「数据集」用真实样本跑一次回归实验。
      <n-button text type="primary" size="small" @click="router.push('/quality')">打开质量总览</n-button>
    </n-alert>
    <n-alert v-if="d.truncated" type="warning" :bordered="false" class="mt12">
      该窗口调用量超过单次聚合上限（50,000 条 trace），指标只覆盖最近 50,000 条，可能偏小；缩小时间窗口可得到完整数据。
    </n-alert>

    <!-- Trust Score 主指标 + 次级 KPI -->
    <div class="ov-grid">
      <section class="hero">
        <div class="hero-label">总体信任分</div>
        <div class="hero-row">
          <span class="hero-score num" :style="{ color: scoreColor(overall.score) }">{{ fmtScore(overall.score) }}</span>
          <n-tag size="small" :bordered="false" :type="statusType(overall.status)">{{ statusLabel(overall.status) }}</n-tag>
          <svg class="hero-ring" viewBox="0 0 64 64" width="64" height="64">
            <circle cx="32" cy="32" r="26" fill="none" stroke="var(--bg-subtle)" stroke-width="7" />
            <circle v-if="overall.score != null" cx="32" cy="32" r="26" fill="none" :stroke="scoreColor(overall.score)" stroke-width="7"
              stroke-linecap="round" stroke-dasharray="163.4" :stroke-dashoffset="163.4 * (1 - overall.score / 100)"
              transform="rotate(-90 32 32)" />
            <text x="32" y="36" text-anchor="middle" class="hero-ring-pct num">{{ overall.score == null ? '—' : Math.round(overall.score) + '%' }}</text>
          </svg>
        </div>
        <div class="hero-dims">
          <div v-for="d in overall.dims" :key="d.key" class="dim-line">
            <span class="dim-name">{{ d.label }}</span>
            <div class="dim-bar"><i :style="{ width: (d.v ?? 0) + '%', background: d.v == null ? 'var(--text-muted)' : scoreColor(d.v) }"></i></div>
            <span class="dim-v num">{{ d.v == null ? '—' : d.v }}</span>
          </div>
        </div>
        <div v-if="overall.coverage != null" class="hero-foot page-sub">证据覆盖 {{ overall.coverage }}% · 仅完整证据参与总分</div>
        <template v-if="scoreRows.length">
          <div class="hero-divider"></div>
          <div class="hero-chips">
            <span v-for="c in overall.statusDist" :key="c.status" class="h-chip"
              :style="{ color: statusHex(c.status), background: statusTint(c.status) }">
              <i class="h-dot" :style="{ background: statusHex(c.status) }"></i>{{ enumText('trustStatus', c.status) }} × {{ c.count }}
            </span>
          </div>
          <div v-if="overall.best" class="hero-minmax">
            <span class="hm-label">最高</span>
            <span class="hm-agent mono" :title="overall.best.agentKey">{{ overall.best.agentKey }}</span>
            <span class="num hm-score" :style="{ color: scoreColor(overall.best.score) }">{{ fmtScore(overall.best.score) }}</span>
          </div>
          <div v-if="overall.worst" class="hero-minmax">
            <span class="hm-label">最低</span>
            <span class="hm-agent mono" :title="overall.worst.agentKey">{{ overall.worst.agentKey }}</span>
            <span class="num hm-score" :style="{ color: scoreColor(overall.worst.score) }">{{ fmtScore(overall.worst.score) }}</span>
          </div>
        </template>
        <div class="hero-foot page-sub">{{ agentCount }} 个 Agent · 近 {{ windowLabel }}</div>
      </section>
      <div class="kpi-grid">
        <StatCard title="调用次数" :value="d.traceCount ?? '—'" :delta="deltaOf('traceCount')" />        <StatCard title="错误率" :value="d.errorRate ?? '—'" :delta="deltaOf('errorCount')" invert-delta
          :value-style="hasError ? 'color: var(--error)' : ''" :hint="hasError ? (d.errorCount + ' 条含错误') : ''" />
        <StatCard title="P95 延迟" :value="fmtMs(d.p95LatencyMs)" :delta="deltaOf('p95LatencyMs')" invert-delta />
        <StatCard title="估算成本" :value="d.totalCostUsd != null ? '$' + Number(d.totalCostUsd).toFixed(4) : '—'" :delta="deltaOf('totalCostUsd')" invert-delta />
        <StatCard title="会话数" :value="sessionCount != null ? sessionCount : '—'" />
        <StatCard title="活跃用户" :value="userCount != null ? userCount : '—'" />
      </div>
    </div>

    <!-- 趋势（数据不足时自动隐藏，Recent Traces 前置） -->
    <n-card v-if="traceCount >= 5" size="small" class="mt12" title="调用趋势">
      <template #header-extra>
        <span class="legend"><i class="dot" style="background: var(--primary)"></i>调用量</span>
        <span class="legend"><i class="dot" style="background: var(--error)"></i>错误</span>
      </template>
      <div v-if="fullTrend.length" class="chart-wrap" @mouseleave="hoverIdx = null">
        <div class="chart-tooltip" v-if="hoverIdx != null && fullTrend[hoverIdx] && fullTrend[hoverIdx].traces > 0"
          :style="{ left: tooltipX }">
          <div class="tt-time">{{ fullTrend[hoverIdx].time }}</div>
          <div>调用量 <b class="num">{{ fullTrend[hoverIdx].traces }}</b></div>
          <div>错误 <b class="num" style="color:var(--error)">{{ fullTrend[hoverIdx].errors }}</b></div>
        </div>
        <svg class="chart" viewBox="0 0 1000 190" preserveAspectRatio="none">
          <line v-for="i in 4" :key="'g' + i" class="grid-line" x1="0" :y1="(i - 1) * 47.5 + 5" x2="1000" :y2="(i - 1) * 47.5 + 5" stroke-width="1" />
          <rect v-for="(p, i) in bars" :key="'b' + i"
            :x="p.x" :y="p.y" :width="p.w" :height="p.h" rx="2" fill="var(--primary)" opacity="0.85"
            style="cursor: crosshair" @mouseenter="hoverIdx = i" />
          <rect v-for="(p, i) in errorBars" :key="'e' + i"
            :x="p.x" :y="p.y" :width="p.w" :height="p.h" rx="1.5" fill="var(--error)"
            style="cursor: crosshair" @mouseenter="hoverIdx = i" />
        </svg>
        <div class="chart-x"><span v-for="(label, idx) in xLabels" :key="idx">{{ label }}</span></div>
        <div class="chart-y"><span>{{ yMax }}</span><span>{{ Math.round(yMax / 2) }}</span><span>0</span></div>
      </div>
      <EmptyState v-else text="所选时间范围内暂无数据" />
    </n-card>
    <n-card v-else size="small" class="mt12" title="最近调用">
      <n-data-table :columns="traceColumns" :data="recentTraces" size="small" :bordered="false" :max-height="320"
        :row-props="(row) => ({ style: 'cursor:pointer', onClick: () => router.push({ path: '/traces', query: { traceId: row.traceId } }) })" />
    </n-card>

    <!-- Agent Health / Model Usage（紧凑列表） -->
    <div class="two-col">
      <n-card size="small" class="mt12" title="Agent 健康度">
        <n-data-table :columns="scoreColumns" :data="scoreRows" size="small" :bordered="false" :max-height="240" />
      </n-card>
      <n-card size="small" class="mt12" title="模型用量">
        <n-data-table :columns="modelColumns" :data="modelBreakdown" size="small" :bordered="false" :max-height="240" :scroll-x="325" />
      </n-card>
    </div>

    <!-- 延迟分布 -->
    <n-card size="small" class="mt12" title="延迟分布">
      <div v-if="histogram.some((b) => b.count > 0)" class="hist-list">
        <div v-for="b in histogram" :key="b.label" class="hist-line">
          <span class="hist-label num">{{ b.label }}</span>
          <div class="hist-bar">
            <i :style="{ width: (yHist > 0 ? Math.max(b.count / yHist * 100, b.count > 0 ? 2 : 0) : 0) + '%' }"></i>
          </div>
          <span class="hist-count num">{{ b.count }}</span>
        </div>
      </div>
      <EmptyState v-else text="所选时间范围内暂无延迟数据" />
    </n-card>

    <!-- Top 用户 + 近期错误（双列） -->
    <div class="bottom-grid">
      <n-card size="small" class="mt12" title="Top 用户">
        <n-data-table size="small" :bordered="false" :columns="topUserColumns" :data="topUsers" :max-height="240"
          :row-props="(row) => ({ style: 'cursor:pointer', onClick: () => router.push('/users') })" />
      </n-card>
      <n-card size="small" class="mt12" title="近期错误">
        <EmptyState v-if="!errorTraces.length" text="所选时间范围内无错误调用" />
        <n-data-table v-else size="small" :bordered="false" :columns="errorColumns" :data="errorTraces" :max-height="240"
          :row-props="(row) => ({ style: 'cursor:pointer', onClick: () => router.push('/traces/' + encodeURIComponent(row.traceId)) })" />
      </n-card>
    </div>

    <!-- Recent Alerts（Severity 强调，置底） -->
    <n-card size="small" class="mt12" title="最新告警">
      <n-data-table :columns="alertColumns" :data="alerts" size="small" :bordered="false" :max-height="240"
        :row-class-name="(row) => 'sev-' + (row.severity || 'LOW').toLowerCase()" />
    </n-card>

    <!-- 最近 Traces（数据充足时置底） -->
    <n-card v-if="traceCount >= 5" size="small" class="mt12" title="最近调用">
      <n-data-table :columns="traceColumns" :data="recentTraces" size="small" :bordered="false" :max-height="320"
        :row-props="(row) => ({ style: 'cursor:pointer', onClick: () => router.push({ path: '/traces', query: { traceId: row.traceId } }) })" />
    </n-card>
  </div>
</template>

<script setup>
import { computed, h, onMounted, ref } from 'vue'
import { NTag } from 'naive-ui'
import { useRouter } from 'vue-router'
import api from '@/api'
import { enumText } from '@/common/i18n'
import { fetchAgentOptions } from '@/common/agents'
import StatCard from '@/components/StatCard.vue'
import TimeText from '@/components/TimeText.vue'
import CopyableId from '@/components/CopyableId.vue'
import EmptyState from '@/components/EmptyState.vue'

const router = useRouter()
const d = ref({})
const window = ref('24h')
const agentFilter = ref(null)
const envFilter = ref(null)
const agentOptions = ref([])
const envOptions = ref([])
const alerts = ref([])
const recentTraces = ref([])
const scoreRows = ref([])
const trend = ref([])
const modelBreakdown = ref([])
const hoverIdx = ref(null)
const sessionCount = ref(null)
const userCount = ref(null)
const topUsers = ref([])
const errorTraces = ref([])
const hasError = computed(() => Number(d.value.errorCount) > 0)
const traceCount = computed(() => Number(d.value.traceCount) || 0)
const loading = ref(true)
const loadError = ref('')
const windowLabel = computed(() => ({ '1h': '1 小时', '24h': '24 小时', '7d': '7 天', '30d': '30 天' }[window.value] || '24 小时'))
const histogram = computed(() => d.value?.latencyHistogram || [])
const yHist = computed(() => Math.max(0, ...histogram.value.map((b) => Number(b.count) || 0)))

function deltaOf(key) {
  const v = d.value?.delta?.[key]
  return v == null ? null : Number(v)
}
function fmtMs(ms) {
  if (ms == null || ms === 0) return '—'
  if (ms >= 60000) return (ms / 60000).toFixed(1) + ' min'
  if (ms >= 1000) return (ms / 1000).toFixed(2) + ' s'
  return ms + ' ms'
}

// ---------- Overall Trust Score（多 Agent 均值 + 最差状态） ----------
const STATUS_ORDER = { TRUSTED: 0, WATCH: 1, INSUFFICIENT_EVIDENCE: 2, AT_RISK: 3, CRITICAL: 4, DEGRADED: 5, UNTRUSTED: 6 }
const missingDimsText = computed(() => (overall.value.dims || [])
  .filter((d) => d.v == null)
  .map((d) => d.label)
  .join('、'))
const overall = computed(() => {
  if (!scoreRows.value.length) return { score: null, status: 'INSUFFICIENT_EVIDENCE', dims: [], statusDist: [], best: null, worst: null, coverage: 0 }
  const scored = scoreRows.value.filter((r) => r.score != null)
  // 有任一 Agent 证据不足时，总体不给“平均分”，避免 100 分与“证据不足”并排出现。
  const score = scoreRows.value.length > 0 && scored.length === scoreRows.value.length
    ? scored.reduce((a, r) => a + Number(r.score), 0) / scored.length : null
  const worstOf = (s) => STATUS_ORDER[s] ?? 99
  const status = scoreRows.value.reduce((worst, r) =>
    worstOf(r.status) > worstOf(worst) ? r.status : worst, 'TRUSTED')
  const dims = ['performance', 'behavior', 'output', 'security', 'context'].map((key, i) => {
    const label = ['性能', '行为', '输出', '安全', '上下文'][i]
    const vals = scoreRows.value.map((r) => {
      try { return JSON.parse(r.dimensions)?.[key]?.score } catch (e) { return null }
    })
    // 任一 Agent 缺该维度证据时，总体维度显示 —，不拿其余 Agent 的均值伪装完整。
    return { key, label, v: vals.every((v) => v != null)
      ? Math.round(vals.reduce((a, b) => a + b, 0) / vals.length) : null }
  })
  const distMap = {}
  for (const r of scoreRows.value) distMap[r.status] = (distMap[r.status] || 0) + 1
  const statusDist = Object.entries(distMap)
    .sort((a, b) => (STATUS_ORDER[a[0]] ?? 99) - (STATUS_ORDER[b[0]] ?? 99))
    .map(([st, count]) => ({ status: st, count }))
  const sorted = [...scored].sort((a, b) => b.score - a.score)
  const coverageVals = scoreRows.value
    .filter((r) => r.coverage != null)
    .map((r) => Number(r.coverage)).filter((v) => Number.isFinite(v))
  const coverage = coverageVals.length ? Math.round(coverageVals.reduce((a, b) => a + b, 0) / coverageVals.length) : null
  const complete = score != null
  const best = complete && sorted[0] ? { agentKey: sorted[0].agent, score: sorted[0].score } : null
  const worst = complete && sorted.length > 1 ? { agentKey: sorted[sorted.length - 1].agent, score: sorted[sorted.length - 1].score } : null
  return { score, status, dims, statusDist, best, worst, coverage }
})
// 上报中的 Agent 数量以 summary 为准（信任分快照只覆盖证据完整的 Agent，会把新接入的 Agent 显示成 0）
const agentCount = computed(() => d.value.agentCount ?? agentOptions.value.length)

// ---------- 柱状图（补齐空桶） ----------
const BUCKET_COUNT = { '1h': 12, '24h': 24, '7d': 7, '30d': 30 }
function fillTrend(raw, win) {
  const n = BUCKET_COUNT[win]
  if (!n || !raw?.length) return raw || []
  const now = new Date()
  const stepMs = win === '1h' ? 5 * 60000 : win === '24h' ? 3600000 : 86400000
  const p2 = (v) => String(v).padStart(2, '0')
  const fmt = (d) => win === '1h' ? `${p2(d.getHours())}:${p2(d.getMinutes())}`
    : win === '24h' ? `${p2(d.getDate())}日${p2(d.getHours())}:00` : `${p2(d.getMonth() + 1)}-${p2(d.getDate())}`
  const byKey = Object.fromEntries(raw.map((r) => [r.time, r]))
  const out = []
  const start = new Date(now.getTime() - (n - 1) * stepMs)
  if (win === '1h') start.setMinutes(Math.floor(start.getMinutes() / 5) * 5, 0, 0)
  else if (win === '24h') start.setMinutes(0, 0, 0)
  else start.setHours(0, 0, 0, 0)
  for (let i = 0; i < n; i++) {
    const d2 = new Date(start.getTime() + i * stepMs)
    const k = fmt(d2)
    const hit = byKey[k]
    out.push({ time: k, traces: hit?.traces || 0, errors: hit?.errors || 0 })
  }
  const missing = raw.filter((r) => !out.some((o) => o.time === r.time))
  return missing.length ? [...missing, ...out] : out
}
const fullTrend = computed(() => fillTrend(trend.value, window.value))
const W = 1000, H = 185
const yMax = computed(() => Math.max(4, ...fullTrend.value.map((p) => p.traces)))
const bars = computed(() => {
  const n = fullTrend.value.length
  if (!n) return []
  const slot = W / n
  const bw = Math.min(slot * 0.6, 26)
  return fullTrend.value.map((p, i) => {
    const h = (p.traces / yMax.value) * (H - 10)
    return { x: i * slot + (slot - bw) / 2, y: H - h, w: bw, h }
  })
})
const errorBars = computed(() => bars.value
  .map((b, i) => ({ b, err: fullTrend.value[i]?.errors || 0 }))
  .filter(({ err }) => err > 0)
  .map(({ b, err }) => {
    const h = Math.max((err / yMax.value) * (H - 10), 3)
    return { x: b.x + b.w * 0.25, y: H - h, w: b.w * 0.5, h }
  }))
const tooltipX = computed(() => {
  if (hoverIdx.value == null || !bars.value.length) return '0%'
  const b = bars.value[hoverIdx.value]
  return Math.min(Math.max((b.x + b.w / 2) / W * 100, 6), 94) + '%'
})
const xLabels = computed(() => {
  const n = fullTrend.value.length
  if (n === 0) return []
  if (n <= 2) return fullTrend.value.map((p) => p.time)
  return [fullTrend.value[0].time, fullTrend.value[Math.floor(n / 2)].time, fullTrend.value[n - 1].time]
})

// ---------- 表格 ----------
function sevTag(s) {
  const type = s === 'CRITICAL' ? 'error' : s === 'HIGH' ? 'warning' : 'default'
  return h(NTag, { size: 'small', bordered: false, type }, { default: () => s })
}
function stTag(s) {
  return h(NTag, { size: 'small', bordered: false, type: s === 'COMPLETED' ? 'success' : 'info' }, { default: () => s })
}
function fmtScore(score) {
  return score == null ? '—' : Number(score).toFixed(1)
}
function scoreColor(score) {
  if (score == null) return 'var(--text-muted)'
  if (score >= 80) return 'var(--success)'
  if (score >= 60) return 'var(--warn)'
  return 'var(--error)'
}
function statusType(s) {
  if (s === 'TRUSTED') return 'success'
  if (s === 'INSUFFICIENT_EVIDENCE' || s === 'NO_DATA' || s === 'UNKNOWN') return 'default'
  if (s === 'DEGRADED' || s === 'WATCH') return 'warning'
  return 'error'
}
function statusLabel(s) {
  return ({ TRUSTED: '可信', AT_RISK: '有风险', CRITICAL: '严重', INSUFFICIENT_EVIDENCE: '证据不足', WATCH: '观察', DEGRADED: '退化', UNTRUSTED: '不可信' }[s] || s)
}
function statusHex(s) {
  if (s === 'TRUSTED') return 'var(--success)'
  if (s === 'INSUFFICIENT_EVIDENCE' || s === 'NO_DATA') return 'var(--text-muted)'
  if (s === 'DEGRADED' || s === 'WATCH') return 'var(--warn)'
  return 'var(--error)'
}
// var(--x) 不能拼十六进制透明度；用 color-mix 生成与状态色一致的浅底
function statusTint(s) {
  return `color-mix(in srgb, ${statusHex(s)} 12%, transparent)`
}
function dimBar(dims, key) {
  let v = null
  try { v = JSON.parse(dims)?.[key]?.score } catch (e) { /* ignore */ }
  const label = { performance: '性能', behavior: '行为', output: '输出', security: '安全', context: '上下文' }[key] || key
  if (v == null) return h('span', { style: 'color:var(--text-muted);font-size:12px', title: label + '：无证据' }, '—')
  return h('div', { title: label + ' ' + Math.round(v), style: 'display:flex;align-items:center' }, [
    h('div', { style: 'flex:1;height:6px;background:var(--bg-subtle);border-radius:3px;overflow:hidden' }, [
      h('div', { style: `height:100%;width:${Math.round(v)}%;background:${scoreColor(v)};border-radius:3px` })
    ])
  ])
}
const scoreColumns = [
  { title: 'Agent', key: 'agent', width: 90, ellipsis: true },
  { title: '得分', key: 'score', width: 50, render: (r) => h('span', { class: 'num', style: `font-weight:600;color:${scoreColor(r.score)}` }, fmtScore(r.score)) },
  { title: '状态', key: 'status', width: 90, render: (r) => h(NTag, { size: 'small', bordered: false, type: statusType(r.status) }, { default: () => statusLabel(r.status) }) },
  { title: '覆盖', key: 'coverage', width: 50, render: (r) => h('span', { class: 'num' }, r.coverage == null ? '—' : r.coverage + '%') },
  { title: '性能', key: 'perf', width: 54, render: (r) => dimBar(r.dimensions, 'performance') },
  { title: '行为', key: 'beh', width: 54, render: (r) => dimBar(r.dimensions, 'behavior') },
  { title: '输出', key: 'out', width: 54, render: (r) => dimBar(r.dimensions, 'output') },
  { title: '安全', key: 'sec', width: 54, render: (r) => dimBar(r.dimensions, 'security') },
  { title: '上下文', key: 'ctx', width: 54, render: (r) => dimBar(r.dimensions, 'context') }
]
const modelColumns = [
  { title: '模型', key: 'model', width: 110, ellipsis: true },
  { title: '调用量', key: 'traces', width: 65, align: 'right', render: (r) => h('span', { class: 'num' }, String(r.traces)) },
  { title: '错误', key: 'errors', width: 60, align: 'right', render: (r) => h('span', { class: 'num', style: r.errors ? 'color:var(--error)' : '' }, String(r.errors)) },
  { title: '成本', key: 'costUsd', width: 90, align: 'right', render: (r) => h('span', { class: 'num' }, '$' + Number(r.costUsd || 0).toFixed(4)) }
]
const alertColumns = [
  { title: '严重级', key: 'severity', width: 95, render: (r) => sevTag(r.severity) },
  { title: 'Agent', key: 'agentKey', width: 110 },
  { title: '标题', key: 'title', ellipsis: { tooltip: true } },
  { title: '状态', key: 'status', width: 100, render: (r) => h(NTag, { size: 'small', bordered: false, type: r.status === 'OPEN' ? 'error' : r.status === 'ACKNOWLEDGED' ? 'warning' : 'success' }, { default: () => enumText('incidentStatus', r.status) }) },
  { title: '最近', key: 'lastSeenAt', width: 100, render: (r) => h(TimeText, { value: r.lastSeenAt }) }
]
const traceColumns = [
  { title: '状态', key: 'status', width: 90, render: (r) => stTag(r.status) },
  { title: 'Trace ID', key: 'traceId', width: 170, render: (r) => h(CopyableId, { value: r.traceId }) },
  { title: 'Agent', key: 'agentKey', width: 110 },
  { title: '输入摘要', key: 'inputPreview', minWidth: 240, ellipsis: { tooltip: true }, render: (r) => r.inputPreview || '—' },
  { title: '耗时', key: 'durationMs', width: 90, align: 'right', render: (r) => h('span', { class: 'num' }, fmtMs(r.durationMs)) },
  { title: '时间', key: 'startTime', width: 110, render: (r) => h(TimeText, { value: r.startTime }) }
]
const topUserColumns = [
  { title: '用户', key: 'userId', width: 120, ellipsis: { tooltip: true }, render: (r) => h('span', { class: 'mono' }, r.userId) },
  { title: '调用', key: 'traceCount', width: 55, align: 'right', render: (r) => h('span', { class: 'num' }, String(r.traceCount)) },
  { title: '错误', key: 'errorCount', width: 55, align: 'right', render: (r) => h('span', { style: r.errorCount > 0 ? 'color:var(--error)' : '' }, String(r.errorCount)) },
  { title: '成本', key: 'totalCostUsd', width: 80, align: 'right', render: (r) => h('span', { class: 'num' }, '$' + Number(r.totalCostUsd || 0).toFixed(4)) }
]
const errorColumns = [
  { title: 'Trace', key: 'traceId', width: 150, render: (r) => h('span', { class: 'mono', style: 'color:var(--primary)' }, String(r.traceId).slice(0, 20)) },
  { title: 'Agent', key: 'agentKey', width: 95 },
  { title: '错误', key: 'errorCount', width: 55, align: 'right', render: (r) => h('span', { style: 'color:var(--error)' }, String(r.errorCount)) },
  { title: '输入', key: 'inputPreview', minWidth: 150, ellipsis: { tooltip: true }, render: (r) => r.inputPreview || '—' },
  { title: '时间', key: 'startTime', width: 120, render: (r) => h(TimeText, { value: r.startTime }) }
]

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const [summary, overview, agents, incidents, traces] = await Promise.all([
      api.dashboardSummary(window.value, agentFilter.value || undefined, envFilter.value || undefined),
      api.scoreOverview(),
      fetchAgentOptions(),
      api.incidents({ pageNumber: 1, pageSize: 8 }).catch(() => ({})),
      api.traceSearch({ pageNumber: 1, pageSize: 8 }).catch(() => ({}))
    ])
    d.value = summary || {}
    trend.value = summary?.trend || []
    modelBreakdown.value = summary?.modelBreakdown || []
    alerts.value = incidents.records || []
    recentTraces.value = traces.records || []
    const overviewMap = overview || {}
    scoreRows.value = Object.entries(overviewMap).map(([agent, s]) => ({
      agent, score: s.score == null ? null : Number(s.score), status: s.status,
      dimensions: s.dimensions, coverage: s.coverage, evidenceStatus: s.evidenceStatus
    }))
    agentOptions.value = agents
    // 会话/用户 KPI + Top 用户 + 近期错误（各自独立降级，失败不影响主视图）
    // KPI 与页头筛选器同口径：窗口时间 + Agent + 环境
    const kpiFilters = {
      timeFrom: timeFromFor(window.value),
      agentKey: agentFilter.value || undefined,
      environment: envFilter.value || undefined
    }
    try {
      const s = await api.sessionsList({ pageNumber: 1, pageSize: 1, ...kpiFilters })
      sessionCount.value = s.total ?? null
    } catch (e) { sessionCount.value = null }
    try {
      const u = await api.userInsights({ pageNumber: 1, pageSize: 5, ...kpiFilters })
      userCount.value = u.total ?? null
      topUsers.value = u.records || []
    } catch (e) { userCount.value = null; topUsers.value = [] }
    try {
      const er = await api.traceSearch({ hasError: true, pageNumber: 1, pageSize: 8, sortBy: 'startTime', sortOrder: 'desc', timeFrom: timeFromFor(window.value) })
      errorTraces.value = er.records || []
    } catch (e) { errorTraces.value = [] }
  } catch (e) {
    loadError.value = e?.message || String(e)
  } finally {
    loading.value = false
  }
}

function localIso(date) {
  const pad = (n) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T`
    + `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

function timeFromFor(win) {
  const hours = { '1h': 1, '24h': 24, '7d': 24 * 7, '30d': 24 * 30 }[win]
  if (!hours) return undefined
  // 后端按服务器本地墙上时间解析 LocalDateTime；用 toISOString 会按 UTC 偏移窗口
  return localIso(new Date(Date.now() - hours * 3600 * 1000))
}
onMounted(async () => {
  try { envOptions.value = (await api.environments() || []).map((e) => ({ label: e, value: e })) } catch (err) { /* ignore */ }
  load()
})
</script>

<style scoped>
.filters { display: flex; align-items: center; gap: 10px; }
.ov-grid { display: grid; grid-template-columns: 340px minmax(0, 1fr); gap: 12px; margin-top: 12px; }
@media (max-width: 1100px) { .ov-grid { grid-template-columns: 1fr; } }
.bottom-grid { display: grid; grid-template-columns: minmax(0, 5fr) minmax(0, 7fr); gap: 12px; }
@media (max-width: 1100px) { .bottom-grid { grid-template-columns: 1fr; } }
.hero { background: var(--bg-surface); border: 1px solid var(--border); border-radius: 8px; padding: 16px 20px; display: flex; flex-direction: column; }
.hero-label { font-size: 11.5px; font-weight: 600; letter-spacing: 0.06em; text-transform: uppercase; color: var(--text-muted); }
.hero-row { display: flex; align-items: center; gap: 10px; margin-top: 6px; }
.hero-ring { margin-left: auto; flex: 0 0 64px; }
.hero-score { font-size: 44px; font-weight: 700; font-family: "JetBrains Mono", ui-monospace, Menlo, monospace; line-height: 1.1; }
.hero-ring-pct { font-size: 11px; font-weight: 600; fill: var(--text-secondary); }
.hero-dims { margin-top: 12px; display: flex; flex-direction: column; gap: 8px; }
.dim-line { display: flex; align-items: center; gap: 10px; }
.dim-name { flex: 0 0 84px; font-size: 12px; color: var(--text-secondary); }
.dim-bar { flex: 1; height: 6px; background: var(--bg-subtle); border-radius: 3px; overflow: hidden; }
.dim-bar i { display: block; height: 100%; border-radius: 3px; }
.dim-v { flex: 0 0 30px; text-align: right; font-size: 11.5px; color: var(--text-secondary); }
.hero-divider { margin-top: 14px; border-top: 1px dashed var(--border); }
.hero-chips { margin-top: 10px; display: flex; flex-wrap: wrap; gap: 6px; }
.h-chip { display: inline-flex; align-items: center; gap: 5px; font-size: 11px; font-weight: 600; padding: 3px 8px; border-radius: 999px; }
.h-dot { width: 6px; height: 6px; border-radius: 50%; display: inline-block; }
.hero-minmax { margin-top: 7px; display: flex; align-items: center; gap: 8px; font-size: 12px; }
.hm-label { flex: 0 0 26px; color: var(--text-muted); font-size: 11px; }
.hm-agent { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--text-secondary); font-size: 11.5px; }
.hm-score { flex: 0 0 auto; font-weight: 600; }
.hero-foot { margin-top: auto; padding-top: 12px; }
.kpi-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); grid-auto-rows: minmax(0, 1fr); gap: 12px; }
.legend { display: inline-flex; align-items: center; gap: 5px; font-size: 12px; color: var(--text-secondary); margin-left: 14px; }
.legend .dot { width: 8px; height: 8px; border-radius: 2px; display: inline-block; }
.chart-wrap { position: relative; padding: 6px 0 0 34px; }
.chart { width: 100%; height: 185px; display: block; }
.grid-line { stroke: var(--border); }
.chart rect:hover { opacity: 1; }
.chart-tooltip { position: absolute; top: 0; transform: translateX(-50%); background: var(--text-primary); color: var(--bg-page); border-radius: 6px; padding: 6px 10px; font-size: 11.5px; pointer-events: none; white-space: nowrap; z-index: 2; }
.tt-time { color: var(--text-muted); font-size: 10.5px; margin-bottom: 2px; }
.chart-x { display: flex; justify-content: space-between; font-size: 11px; color: var(--text-muted); margin-top: 4px; }
.chart-y { position: absolute; left: 0; top: 6px; height: 185px; display: flex; flex-direction: column; justify-content: space-between; font-size: 11px; color: var(--text-muted); text-align: right; width: 28px; }
.two-col { display: grid; grid-template-columns: minmax(0, 3fr) minmax(0, 2fr); gap: 12px; }
@media (max-width: 1200px) { .two-col { grid-template-columns: 1fr; } }
.hist-list { display: flex; flex-direction: column; gap: 10px; max-height: 260px; overflow-y: auto; padding-right: 4px; }
.hist-line { display: flex; align-items: center; gap: 12px; }
.hist-label { flex: 0 0 52px; font-size: 12px; color: var(--text-secondary); text-align: right; }
.hist-bar { flex: 1; height: 14px; background: var(--bg-subtle); border-radius: 4px; overflow: hidden; }
.hist-bar i { display: block; height: 100%; background: var(--primary); opacity: 0.8; border-radius: 4px; }
.hist-count { flex: 0 0 36px; text-align: right; font-size: 12px; color: var(--text-secondary); }
.mt12 { margin-top: 12px; }
.mono { font-family: "JetBrains Mono", ui-monospace, Menlo, monospace; }
.num { font-family: "JetBrains Mono", ui-monospace, Menlo, monospace; font-variant-numeric: tabular-nums; }
</style>
