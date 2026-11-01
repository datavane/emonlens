<template>
  <div class="base-page">
    <div class="head-stack">
      <div class="page-head">
        <div class="head-left">
          <n-button size="small" quaternary @click="router.push('/traces')">← 返回列表</n-button>
        </div>
        <n-button size="small" quaternary title="交换 A / B" @click="swapAB">⇄ 交换 A/B</n-button>
        <n-button size="small" @click="load">刷新</n-button>
      </div>

      <!-- 结论摘要条：一眼判定谁更快/更省/更稳。放在 head-stack 里随页头一起吸顶——
           往下翻 span 对齐表时仍需随时看到结论，否则对比的意义就丢了 -->
      <n-card size="small" class="mt12" v-if="!loading && a && b">
        <div class="verdict-row">
          <span class="v-item" :title="'A ' + fmtDur(a.summary.durationMs) + ' / B ' + fmtDur(b.summary.durationMs)">
            总耗时 <b class="num" :style="{ color: verdict.durDelta === 0 ? '' : verdict.durDelta > 0 ? 'var(--error)' : 'var(--success)' }">
              {{ verdict.durDelta === 0 ? '持平' : (verdict.durDelta > 0 ? 'B 慢 +' : 'A 慢 ') + fmtDur(Math.abs(verdict.durDelta)) }}</b>
          </span>
          <span class="v-item">
            总成本 <b class="num" :style="{ color: verdict.costDelta === 0 ? '' : verdict.costDelta > 0 ? 'var(--error)' : 'var(--success)' }">
              {{ verdict.costDelta === 0 ? '持平' : (verdict.costDelta > 0 ? 'B 贵 $' : 'A 贵 $') + Math.abs(verdict.costDelta).toFixed(5) }}</b>
          </span>
          <span class="v-item">
            Tokens <b class="num" :style="{ color: verdict.tokDelta === 0 ? '' : 'var(--text)' }">
              {{ verdict.tokDelta === 0 ? '持平' : (verdict.tokDelta > 0 ? 'B 多 ' : 'A 多 ') + verdict.tokDeltaAbs }}</b>
          </span>
          <span class="v-item">
            错误 <b class="num" :style="{ color: verdict.errDelta === 0 ? '' : 'var(--error)' }">{{ verdict.errText }}</b>
          </span>
        </div>
      </n-card>
    </div>

    <n-spin v-if="loading" style="width: 100%; margin-top: 60px" />
    <template v-else-if="a && b">

      <!-- 元信息对比 -->
      <n-card size="small" class="mt12" title="元信息">
        <div class="cmp-grid">
          <div class="cmp-col" v-for="(side, i) in [a, b]" :key="i">
            <div class="cmp-head">
              <CopyableId :value="i === 0 ? aId : bId" :length="26" class="head-id" />
              <n-tag size="tiny" :bordered="false" :type="side.summary.status === 'COMPLETED' ? 'success' : 'info'">{{ enumText('runStatus', side.summary.status) }}</n-tag>
            </div>
            <div class="desc-list">
              <template v-for="row in metaRows" :key="row.key">
                <span class="k">{{ row.label }}</span>
                <span :class="{ diff: isDiff(row) }" :title="isDiff(row) ? '两侧不同' : ''">{{ row.val(side) }}</span>
              </template>
            </div>
          </div>
        </div>
      </n-card>

      <!-- 输入 / 输出并排 -->
      <div class="io-grid mt12">
        <n-card size="small" :title="'Input（A / B）'">
          <template #header-extra>
            <n-tag size="tiny" :bordered="false" :type="ioSame.input ? 'success' : 'warning'">{{ ioSame.input ? '相同' : '不同' }}</n-tag>
          </template>
          <div class="io-pair">
            <CodeBlock :content="a.summary?.inputPreview || '（无输入）'" label="A" :max-height="180" />
            <CodeBlock :content="b.summary?.inputPreview || '（无输入）'" label="B" :max-height="180" />
          </div>
        </n-card>
        <n-card size="small" :title="'Output（A / B）'">
          <template #header-extra>
            <n-tag size="tiny" :bordered="false" :type="ioSame.output ? 'success' : 'warning'">{{ ioSame.output ? '相同' : '不同' }}</n-tag>
          </template>
          <div class="io-pair">
            <CodeBlock :content="a.summary?.outputPreview || '（无输出）'" label="A" :max-height="180" />
            <CodeBlock :content="b.summary?.outputPreview || '（无输出）'" label="B" :max-height="180" />
          </div>
        </n-card>
      </div>

      <!-- Span 对齐 diff -->
      <n-card size="small" class="mt12" :title="'Span 对齐（' + spanRows.length + '）'">
        <div class="page-sub" style="margin-bottom:8px">按 span 名称+类型对齐；同名取同类第 N 个实例逐一配对；Δ = B − A 耗时</div>
        <n-data-table :columns="spanColumns" :data="spanRows" size="small" :bordered="false" :max-height="480" />
      </n-card>
    </template>
    <EmptyState v-else-if="!loading" text="需要两条 Trace：请从调用链路列表勾选两条后点「对比」"
      action-text="去列表勾选" @action="router.push('/traces')" />
  </div>
</template>

<script setup>
import { computed, h, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NTag } from 'naive-ui'
import api from '@/api'
import { enumText } from '@/common/i18n'
import CopyableId from '@/components/CopyableId.vue'
import CodeBlock from '@/components/CodeBlock.vue'
import EmptyState from '@/components/EmptyState.vue'

const route = useRoute()
const router = useRouter()
const aId = ref(String(route.query.a || ''))
const bId = ref(String(route.query.b || ''))
const a = ref(null), b = ref(null), loading = ref(true)

const metaRows = [
  { key: 'agent', label: 'Agent', val: (s) => (s.summary.agentKey || '—') + (s.summary.agentVersion ? ' · ' + s.summary.agentVersion : ''), diff: (s) => s.summary.agentKey },
  { key: 'model', label: '模型', val: (s) => s.summary.modelName || '—', diff: (s) => s.summary.modelName },
  { key: 'dur', label: '耗时', val: (s) => fmtDur(s.summary.durationMs), diff: (s) => s.summary.durationMs },
  // 汇总表（et_trace_summary）不存 tokens：按 span 求和（与列表 Tokens 列同口径）
  { key: 'tokens', label: 'Tokens', val: (s) => tokensText(s), diff: null },
  { key: 'cost', label: '成本', val: (s) => (s.summary.totalCostUsd != null ? '$' + Number(s.summary.totalCostUsd).toFixed(5) : '—'), diff: (s) => s.summary.totalCostUsd },
  { key: 'errors', label: '错误 span', val: (s) => String(s.summary.errorCount ?? 0), diff: (s) => s.summary.errorCount },
  { key: 'env', label: '环境', val: (s) => s.summary.environment || '—', diff: (s) => s.summary.environment },
  { key: 'session', label: 'Session', val: (s) => s.summary.sessionId || '—', diff: (s) => s.summary.sessionId },
  { key: 'start', label: '开始时间', val: (s) => String(s.summary.startTime || '—').replace('T', ' ').slice(0, 19), diff: null }
]

function isDiff(row) {
  if (!row.diff || !a.value || !b.value) return false
  return row.diff(a.value) !== row.diff(b.value)
}

function fmtDur(ms) {
  if (ms == null) return '—'
  if (ms >= 60000) return (ms / 60000).toFixed(2) + ' min'
  if (ms >= 1000) return (ms / 1000).toFixed(2) + ' s'
  return ms + ' ms'
}

function spanSums(side) {
  return (side?.spans || []).reduce((acc, s) => {
    acc.in += s.inputTokens || 0
    acc.out += s.outputTokens || 0
    acc.cached += s.cachedInputTokens || 0
    return acc
  }, { in: 0, out: 0, cached: 0 })
}
function tokensText(side) {
  const t = spanSums(side)
  const base = t.in + ' → ' + t.out
  return t.cached ? base + '（缓存 ' + t.cached + '）' : base
}

// ---------- 结论摘要 / A-B 交换 ----------
function swapAB() {
  const prevA = aId.value
  aId.value = bId.value
  bId.value = prevA
  router.replace({ query: { a: aId.value, b: bId.value } })
  load()
}
const verdict = computed(() => {
  if (!a.value || !b.value) return { durDelta: 0, costDelta: 0, tokDelta: 0, tokDeltaAbs: '0', errDelta: 0, errText: '持平' }
  const sa = a.value.summary || {}
  const sb = b.value.summary || {}
  const durDelta = (sb.durationMs ?? 0) - (sa.durationMs ?? 0)
  const costDelta = Number((Number(sb.totalCostUsd || 0) - Number(sa.totalCostUsd || 0)).toFixed(6))
  const ta = spanSums(a.value)
  const tb = spanSums(b.value)
  const tokDelta = (tb.in + tb.out) - (ta.in + ta.out)
  const errDelta = (sb.errorCount ?? 0) - (sa.errorCount ?? 0)
  let errText = '持平'
  if (errDelta > 0) errText = 'B 多 ' + errDelta + ' 个错误 span'
  else if (errDelta < 0) errText = 'A 多 ' + Math.abs(errDelta) + ' 个错误 span'
  return { durDelta, costDelta, tokDelta, tokDeltaAbs: tokDeltaAbsFmt(Math.abs(tokDelta)), errDelta, errText }
})
function tokDeltaAbsFmt(n) {
  return n >= 1_000_000 ? (n / 1_000_000).toFixed(2) + 'M' : n >= 1000 ? (n / 1000).toFixed(1) + 'K' : String(n)
}
const ioSame = computed(() => {
  if (!a.value || !b.value) return { input: true, output: true }
  const sa = a.value.summary || {}
  const sb = b.value.summary || {}
  return {
    input: (sa.inputPreview || '') === (sb.inputPreview || ''),
    output: (sa.outputPreview || '') === (sb.outputPreview || '')
  }
})

// ---------- span 对齐 diff ----------
function keyOf(s) {
  return (s.spanName || s.toolName || s.spanType || '?') + '|' + s.spanType
}
const spanRows = computed(() => {
  if (!a.value || !b.value) return []
  const pa = a.value.spans || []
  const pb = b.value.spans || []
  const groups = new Map()
  for (const s of pa) {
    const k = keyOf(s)
    if (!groups.has(k)) groups.set(k, { a: [], b: [] })
    groups.get(k).a.push(s)
  }
  for (const s of pb) {
    const k = keyOf(s)
    if (!groups.has(k)) groups.set(k, { a: [], b: [] })
    groups.get(k).b.push(s)
  }
  const rows = []
  for (const [key, g] of groups) {
    const n = Math.max(g.a.length, g.b.length)
    for (let i = 0; i < n; i++) {
      const sa = g.a[i] || null
      const sb = g.b[i] || null
      const durA = sa?.durationMs ?? null
      const durB = sb?.durationMs ?? null
      const delta = durA != null && durB != null ? durB - durA : null
      const costA = Number(sa?.costUsd || 0)
      const costB = Number(sb?.costUsd || 0)
      const tokA = (sa?.inputTokens || 0) + (sa?.outputTokens || 0)
      const tokB = (sb?.inputTokens || 0) + (sb?.outputTokens || 0)
      rows.push({
        key: key + '#' + i,
        name: key.split('|')[0],
        type: key.split('|')[1],
        sideA: sa ? 'A' : 'only-B',
        sideB: sb ? 'B' : 'only-A',
        durA, durB, delta,
        statusA: sa?.status || null,
        statusB: sb?.status || null,
        costDelta: sa && sb ? Number((costB - costA).toFixed(6)) : null,
        tokDelta: sa && sb ? tokB - tokA : null
      })
    }
  }
  // 两侧都存在的配对行排在前面，只有单侧的（仅 A / 仅 B）沉底
  const unpaired = (r) => r.sideA === 'only-B' || r.sideB === 'only-A'
  rows.sort((x, y) => (unpaired(x) ? 1 : 0) - (unpaired(y) ? 1 : 0))
  return rows
})

function durCell(ms, status) {
  if (ms == null) return h('span', { style: 'color:var(--border-strong);font-size:12px' }, '— 不存在')
  return h('span', { class: 'num' }, [
    fmtDur(ms) + ' ',
    status === 'ERROR' ? h(NTag, { size: 'tiny', bordered: false, type: 'error' }, { default: () => 'ERR' }) : null
  ])
}

const spanColumns = [
  { title: 'Span', key: 'name', width: 220, render: (r) => h('span', { class: 'mono', style: 'font-size:12px' }, r.name) },
  { title: '类型', key: 'type', width: 90, render: (r) => h(NTag, { size: 'tiny', bordered: false, title: r.type }, { default: () => enumText('spanType', r.type) }) },
  { title: 'A 耗时', key: 'durA', width: 130, align: 'right', render: (r) => durCell(r.durA, r.statusA) },
  { title: 'B 耗时', key: 'durB', width: 130, align: 'right', render: (r) => durCell(r.durB, r.statusB) },
  { title: 'Δ (B−A) 耗时', key: 'delta', width: 130, align: 'right', render: (r) => {
      if (r.delta == null) return h('span', { style: 'color:var(--text-muted);font-size:12px' }, '—')
      if (r.delta === 0) return h('span', { class: 'num', style: 'color:var(--text-muted)' }, '±0')
      const up = r.delta > 0
      return h('span', { class: 'num', style: 'font-weight:600;color:' + (up ? 'var(--error)' : 'var(--success)') },
        (up ? '+' : '−') + fmtDur(Math.abs(r.delta)))
    } },
  { title: 'Δ 成本', key: 'costDelta', width: 100, align: 'right', render: (r) => {
      if (r.costDelta == null) return h('span', { style: 'color:var(--text-muted);font-size:12px' }, '—')
      if (r.costDelta === 0) return h('span', { class: 'num', style: 'color:var(--text-muted)' }, '±$0')
      return h('span', { class: 'num', style: 'color:' + (r.costDelta > 0 ? 'var(--error)' : 'var(--success)') },
        (r.costDelta > 0 ? '+$' : '−$') + Math.abs(r.costDelta).toFixed(5))
    } },
  { title: 'Δ Tokens', key: 'tokDelta', width: 100, align: 'right', render: (r) => {
      if (r.tokDelta == null) return h('span', { style: 'color:var(--text-muted);font-size:12px' }, '—')
      if (r.tokDelta === 0) return h('span', { class: 'num', style: 'color:var(--text-muted)' }, '±0')
      return h('span', { class: 'num', style: 'color:' + (r.tokDelta > 0 ? 'var(--error)' : 'var(--success)') },
        (r.tokDelta > 0 ? '+' : '−') + Math.abs(r.tokDelta))
    } },
  { title: '差异', key: 'only', width: 90, render: (r) => {
      if (r.sideA === 'only-B') return h(NTag, { size: 'tiny', bordered: false, type: 'warning' }, { default: () => '仅 B' })
      if (r.sideB === 'only-A') return h(NTag, { size: 'tiny', bordered: false, type: 'warning' }, { default: () => '仅 A' })
      return h('span', { style: 'color:var(--text-muted);font-size:12px' }, '两侧')
    } }
]

async function load() {
  loading.value = true
  try {
    const [da, db] = await Promise.all([
      aId.value && api.traceDetail(aId.value).catch(() => null),
      bId.value && api.traceDetail(bId.value).catch(() => null)
    ])
    a.value = da
    b.value = db
  } finally { loading.value = false }
}
onMounted(load)
</script>

<style scoped>
.mt12 { margin-top: 12px; }
.cmp-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
@media (max-width: 900px) { .cmp-grid { grid-template-columns: 1fr; } }
.cmp-col { min-width: 0; }
.cmp-head { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.desc-list { display: grid; grid-template-columns: 90px 1fr; row-gap: 7px; font-size: 12.5px; }
.desc-list .k { color: var(--text-muted); }
.desc-list .diff { color: var(--warn); font-weight: 600; background: var(--warn-bg); border-radius: 3px; padding: 0 4px; }
.io-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
@media (max-width: 900px) { .io-grid { grid-template-columns: 1fr; } }
.io-pair { display: grid; gap: 10px; }
.mono { font-family: "JetBrains Mono", ui-monospace, Menlo, monospace; }
.num { font-family: "JetBrains Mono", ui-monospace, Menlo, monospace; font-variant-numeric: tabular-nums; }
.page-sub { font-size: 11.5px; color: var(--text-muted); }
.head-id { font-size: 13px; font-weight: 600; color: var(--text-primary) !important; }
.verdict-row { display: flex; gap: 28px; flex-wrap: wrap; align-items: baseline; }
.v-item { font-size: 12.5px; color: var(--text-muted); }
.v-item b { margin-left: 6px; font-size: 13.5px; }
</style>
