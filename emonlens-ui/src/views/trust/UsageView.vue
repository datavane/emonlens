<template>
  <div class="base-page">
    <div class="head-stack">
      <div class="page-toolbar">
        <div class="toolbar-filters">
          <n-select v-model:value="window" style="width: 130px" size="small"
            :options="[{label:'近 1 小时',value:'1h'},{label:'近 24 小时',value:'24h'},{label:'近 7 天',value:'7d'},{label:'近 30 天',value:'30d'}]"
            @update:value="load" />
          <n-select v-model:value="agentFilter" placeholder="全部 Agent" style="width: 180px" size="small" clearable
            :options="agentOptions" @update:value="load" />
        </div>
        <div class="toolbar-actions">
          <n-button size="small" @click="load">刷新</n-button>
        </div>
      </div>
    </div>

    <!-- KPI -->
    <div class="kpi-grid">
      <StatCard title="调用次数" :value="d.traceCount ?? '—'" />
      <StatCard title="错误率" :value="d.errorRate != null ? d.errorRate + '%' : '—'"
        :value-style="Number(d.errorCount) > 0 ? 'color: var(--error)' : ''"
        :hint="Number(d.errorCount) > 0 ? (d.errorCount + ' 次含错误') : ''" />
      <StatCard title="总成本" :value="d.totalCostUsd != null ? '$' + Number(d.totalCostUsd).toFixed(4) : '—'" />
      <StatCard title="平均单次成本" :value="d.avgCostUsd != null ? '$' + Number(d.avgCostUsd).toFixed(6) : '—'"
        :hint="'总成本 ÷ 调用次数'" />
      <StatCard title="输入 Tokens" :value="fmtTokens(d.inputTokens)"
        :hint="cachedHint" />
      <StatCard title="输出 Tokens" :value="fmtTokens(d.outputTokens)"
        :hint="d.totalTokens ? ('合计 ' + fmtTokens(d.totalTokens)) : ''" />
    </div>

    <n-alert v-if="d.truncated" type="warning" :bordered="false" class="mt8" style="margin-bottom:8px">
      该窗口调用量超过单次聚合上限（50,000 条 trace），统计只覆盖最近 50,000 条，可能偏小；缩小时间窗口或按 Agent 筛选可得到完整数据。
    </n-alert>

    <!-- 成本 / tokens 双趋势 -->
    <div class="two-col">
      <n-card size="small" class="mt12" title="每日成本">
        <template #header-extra>
          <span class="page-sub">共 {{ d.daily ? d.daily.length : 0 }} 天 · ${{ Number(d.totalCostUsd || 0).toFixed(4) }}</span>
        </template>
        <div v-if="costBars.length" class="cost-chart">
          <div class="cost-track">
            <div v-for="(b, i) in costBars" :key="i" class="cost-bar-wrap" :title="b.tip">
              <div class="cost-bar" :style="{ height: b.h + '%', background: b.peak ? 'var(--warn)' : 'var(--primary)' }"></div>
            </div>
          </div>
          <div class="cost-axis">
            <span>{{ costBars.length ? costBars[0].label : '' }}</span>
            <span>{{ costBars.length ? costBars[Math.floor(costBars.length / 2)].label : '' }}</span>
            <span>{{ costBars.length ? costBars[costBars.length - 1].label : '' }}</span>
          </div>
        </div>
        <EmptyState v-else-if="!loading" text="所选时间范围内暂无成本数据" />
      </n-card>
      <n-card size="small" class="mt12" title="每日 Tokens（输入 / 输出）">
        <template #header-extra>
          <span class="legend"><i class="dot" style="background: var(--primary)"></i>输入</span>
          <span class="legend"><i class="dot" style="background: var(--warn)"></i>输出</span>
        </template>
        <div v-if="tokenRows.length" class="tok-list">
          <div v-for="r in tokenRows" :key="r.day" class="tok-line">
            <span class="tok-day mono">{{ r.label }}</span>
            <div class="tok-track">
              <i v-if="r.inW" :style="{ width: r.inW + '%', background: 'var(--primary)' }" :title="'输入 ' + fmtTokens(r.inputTokens)"></i>
              <i v-if="r.outW" :style="{ width: r.outW + '%', background: 'var(--warn)' }" :title="'输出 ' + fmtTokens(r.outputTokens)"></i>
            </div>
            <span class="tok-v num">{{ fmtTokens(r.inputTokens + r.outputTokens) }}</span>
          </div>
        </div>
        <EmptyState v-else-if="!loading" text="所选时间范围内暂无 tokens 数据（需 SDK 上报 span tokens）" />
      </n-card>
    </div>

    <div class="two-col two-col--drill">
      <!-- 按天 -->
      <n-card size="small" class="mt12" title="按天明细">
        <n-data-table :columns="dailyColumns" :data="d.daily || []" size="small" :bordered="false" :loading="loading"
          :max-height="420" :pagination="false" :scroll-x="400" />
      </n-card>
      <!-- 维度下钻（模型 / Agent / 用户） -->
      <n-card size="small" class="mt12">
        <template #header>
          <span>维度下钻</span>
        </template>
        <template #header-extra>
          <n-radio-group v-model:value="dimension" size="small" @update:value="load">
            <n-radio-button value="model">模型</n-radio-button>
            <n-radio-button value="agent">Agent</n-radio-button>
            <n-radio-button value="user">用户</n-radio-button>
          </n-radio-group>
        </template>
        <n-data-table :columns="drillColumns" :data="d.rows || []" size="small" :bordered="false" :loading="loading"
          :max-height="420" :pagination="false" :scroll-x="600"
          :row-props="(row) => ({ style: 'cursor:pointer', onClick: () => drill(row) })" />
      </n-card>
    </div>
  </div>
</template>

<script setup>
import { computed, h, onMounted, ref } from 'vue'
import { useMessage } from 'naive-ui'
import { useRouter } from 'vue-router'
import api from '@/api'
import { fmtTokens } from '@/common/format'
import { fetchAgentOptions } from '@/common/agents'
import StatCard from '@/components/StatCard.vue'
import EmptyState from '@/components/EmptyState.vue'
import TimeText from '@/components/TimeText.vue'

const router = useRouter()
const message = useMessage()
const d = ref({})
const window = ref('30d')
const dimension = ref('model')
const agentFilter = ref(null)
const agentOptions = ref([])
const loading = ref(false)

// 缓存命中口径：命中部分按「缓存价」计费（未配置则输入价 10%），
// 所以输入 token 里要单独看出命中多少，否则成本看不出为什么低。
const cachedHint = computed(() => {
  const cached = Number(d.value.cachedInputTokens || 0)
  const input = Number(d.value.inputTokens || 0)
  if (!cached || !input) return ''
  const pct = Math.round(cached / input * 100)
  return `缓存命中 ${fmtTokens(cached)} · ${pct}%`
})

/** 成本统一 4 位小数（与顶部卡片、下钻列表一致；日均单次成本用 6 位足够小） */
function fmtCost(v) {
  return '$' + Number(v || 0).toFixed(4)
}
function numCol(key) {
  return (r) => h('span', { class: 'num' }, String(r[key]))
}
const dailyColumns = [
  { title: '日期', key: 'day', width: 120, render: (r) => h('span', { class: 'mono' }, r.day) },
  { title: '调用量', key: 'traces', width: 90, align: 'right', render: numCol('traces') },
  { title: '错误', key: 'errors', width: 80, align: 'right', render: (r) => h('span', { class: 'num', style: r.errors ? 'color:var(--error)' : '' }, String(r.errors)) },
  { title: '成本', key: 'costUsd', width: 110, align: 'right', render: (r) => h('span', { class: 'num' }, fmtCost(r.costUsd)) }
]
const dimLabel = { model: '模型', agent: 'Agent', user: '用户' }
const drillColumns = computed(() => {
  const maxTraces = Math.max(1, ...(d.value.rows || []).map((r) => r.traces))
  const maxCost = Math.max(0.0000001, ...(d.value.rows || []).map((r) => Number(r.costUsd || 0)))
  return [
    { title: dimLabel[dimension.value] || '维度', key: 'name', width: 130, render: (r) => h('span', { class: 'mono', style: 'display:inline-block;max-width:120px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;vertical-align:bottom', title: r.name }, r.name) },
    { title: '调用量', key: 'traces', width: 130, render: (r) => {
        return h('div', { style: 'display:flex;align-items:center;gap:8px' }, [
          h('div', { style: 'flex:1;height:6px;background:var(--bg-subtle);border-radius:3px;overflow:hidden', title: `${r.traces} 次（占最大维度 ${(r.traces / maxTraces * 100).toFixed(0)}%）` }, [
            h('div', { style: `height:100%;width:${Math.round(r.traces / maxTraces * 100)}%;background:var(--primary);border-radius:3px` })
          ]),
          h('span', { class: 'num', style: 'flex:0 0 36px;text-align:right;font-size:11.5px' }, String(r.traces))
        ])
      } },
    { title: '错误', key: 'errors', width: 60, align: 'right', render: (r) => h('span', { class: 'num', style: r.errors ? 'color:var(--error)' : '' }, String(r.errors)) },
    { title: '成本', key: 'costUsd', width: 140, render: (r) => {
        const cost = Number(r.costUsd || 0)
        return h('div', { style: 'display:flex;align-items:center;gap:8px' }, [
          h('div', { style: 'flex:1;height:6px;background:var(--bg-subtle);border-radius:3px;overflow:hidden' }, [
            h('div', { style: `height:100%;width:${Math.round(cost / maxCost * 100)}%;background:var(--warn);border-radius:3px` })
          ]),
          h('span', { class: 'num', style: 'flex:0 0 62px;text-align:right;font-size:11.5px' }, '$' + cost.toFixed(4))
        ])
      } },
    { title: '最近活动', key: 'lastActivity', width: 120, render: (r) => h(TimeText, { value: r.lastActivity }) }
  ]
})
function drill(row) {
  if (dimension.value === 'agent') {
    router.push({ path: '/traces', query: { agentKeys: row.name, timeRange: 'all' } })
  } else if (dimension.value === 'user') {
    if (row.name === '（未标注）') return
    router.push({ path: '/users', query: { q: row.name } })
  } else {
    router.push({ path: '/traces', query: { modelNames: row.name, timeRange: 'all' } })
  }
}

// 每日成本柱状图（峰值日标红）
const costBars = computed(() => {
  const daily = d.value.daily || []
  if (!daily.length) return []
  const max = Math.max(0.0000001, ...daily.map((x) => Number(x.costUsd || 0)))
  const peakIdx = daily.reduce((best, x, i) => Number(x.costUsd || 0) > Number(daily[best].costUsd || 0) ? i : best, 0)
  return daily.map((x, i) => ({
    h: Math.max(2, Math.round(Number(x.costUsd || 0) / max * 100)),
    peak: i === peakIdx && Number(x.costUsd || 0) > 0,
    label: String(x.day).slice(5),
    tip: `${x.day} · $${Number(x.costUsd || 0).toFixed(6)} · ${x.traces} 次${i === peakIdx && Number(x.costUsd || 0) > 0 ? '（峰值日）' : ''}`
  }))
})
// 每日 tokens 双向条（只显示有 token 活动的日子，最多 12 条）
const tokenRows = computed(() => {
  const rows = (d.value.tokenDaily || [])
    .filter((r) => (r.inputTokens || 0) + (r.outputTokens || 0) > 0)
    .slice(-12).reverse()
  const max = Math.max(1, ...rows.map((r) => (r.inputTokens || 0) + (r.outputTokens || 0)))
  return rows.map((r) => {
    const total = (r.inputTokens || 0) + (r.outputTokens || 0)
    return { ...r, label: String(r.day).slice(5), inW: Math.round((r.inputTokens || 0) / max * 100), outW: Math.round((r.outputTokens || 0) / max * 100), total }
  })
})

let usageSeq = 0
async function load() {
  const seq = ++usageSeq
  loading.value = true
  try {
    const data = await api.usage(window.value, agentFilter.value || undefined, dimension.value)
    if (seq !== usageSeq) return
    d.value = data || {}
  } catch (e) {
    if (seq !== usageSeq) return
    d.value = {}
    message.error(e.message || '加载用量数据失败')
  } finally {
    if (seq === usageSeq) loading.value = false
  }
}
onMounted(async () => {
  try {
    agentOptions.value = await fetchAgentOptions()
  } catch (e) { /* ignore */ }
  load()
})
</script>

<style scoped>
.filters { display: flex; align-items: center; gap: 10px; }
.kpi-grid { display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); gap: 12px; }
@media (max-width: 1100px) { .kpi-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); } }
.two-col { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 12px; align-items: start; }
/* 下钻表列更多，给右侧多分一些宽度，避免成本列被横向滚动裁切 */
.two-col--drill { grid-template-columns: minmax(0, 5fr) minmax(0, 7fr); }
@media (max-width: 1100px) { .two-col, .two-col--drill { grid-template-columns: 1fr; } }
.mt12 { margin-top: 12px; }
.mono { font-family: "JetBrains Mono", ui-monospace, Menlo, monospace; }
.page-sub { font-size: 11.5px; color: var(--text-muted); }
.legend { display: inline-flex; align-items: center; gap: 5px; font-size: 12px; color: var(--text-secondary); margin-left: 14px; }
.legend .dot { width: 8px; height: 8px; border-radius: 2px; display: inline-block; }
.cost-chart { padding: 4px 0 0 0; }
.cost-track { display: flex; align-items: flex-end; gap: 3px; height: 130px; }
.cost-bar-wrap { flex: 1; height: 100%; display: flex; align-items: flex-end; }
.cost-bar { width: 100%; border-radius: 2px 2px 0 0; opacity: 0.85; min-height: 2px; }
.cost-bar:hover { opacity: 1; }
.cost-axis { display: flex; justify-content: space-between; font-size: 11px; color: var(--text-muted); margin-top: 4px; }
.tok-list { display: flex; flex-direction: column; gap: 8px; max-height: 260px; overflow-y: auto; }
.tok-line { display: flex; align-items: center; gap: 10px; }
.tok-day { flex: 0 0 44px; font-size: 11.5px; color: var(--text-secondary); }
.tok-track { flex: 1; height: 12px; background: var(--bg-subtle); border-radius: 4px; overflow: hidden; display: flex; }
.tok-track i { display: block; height: 100%; }
.tok-track i:first-child { border-radius: 4px 0 0 4px; }
.tok-v { flex: 0 0 52px; text-align: right; font-size: 11.5px; color: var(--text-secondary); }
</style>
