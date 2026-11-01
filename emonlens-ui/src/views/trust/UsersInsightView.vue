<template>
  <div class="base-page">
    <div class="head-stack">
      <div class="page-toolbar">
        <div class="toolbar-filters">
          <n-input v-model:value="query" placeholder="搜索 user id" size="small" style="width: 200px" clearable @clear="search" @keyup.enter="search" />
        </div>
        <div class="toolbar-actions">
          <n-button size="small" @click="load">刷新</n-button>
        </div>
      </div>
    </div>

    <EmptyState v-if="!loading && !rows.length && !query" :text="'暂无带 user_id 的调用数据——上报 span 时带 userId 字段即可按用户聚合'"
      action-text="查看接入文档字段说明" @action="router.push('/agents')" />

    <div v-else class="users-split">
      <!-- 左：用户列表 -->
      <n-card size="small" class="list-card">
        <n-data-table class="main-table" flex-height style="height: 100%" :columns="columns" :data="rows" size="small" :bordered="false" :loading="loading" remote
          :pagination="pagination" :row-props="rowProps" :row-class-name="(r) => (r.userId === selectedId ? 'row-active' : '')" />
      </n-card>

      <!-- 右：页内详情面板（Langfuse Users detail） -->
      <n-card size="small" class="detail-card">
        <template v-if="detail">
          <div class="d-head">
            <div class="d-avatar">{{ (detail.userId || '?').slice(0, 1).toUpperCase() }}</div>
            <div class="d-title">
              <div class="d-name mono">{{ detail.userId }}</div>
              <div class="page-sub">首次 {{ fmtRel(detail.firstSeen) }} · 最近 {{ fmtRel(detail.lastSeen) }}</div>
            </div>
          </div>
          <div class="d-stats">
            <div class="ds"><span class="v num">{{ detail.traceCount }}</span><span class="k">调用</span></div>
            <div class="ds"><span class="v num" :style="detail.errorCount > 0 ? 'color:var(--error)' : ''">{{ detail.errorCount }}</span><span class="k">错误</span></div>
            <div class="ds"><span class="v num">{{ fmtCost(detail.totalCostUsd) }}</span><span class="k">成本</span></div>
          </div>


          <div class="panel-title">最近调用（{{ (detail.recentTraces || []).length }}）· 按会话分组</div>
          <template v-if="(detail.recentTraces || []).length">
            <div v-for="g in recentBySession" :key="g.sessionId || '__none__'" class="sess-group">
              <div class="sg-head" :title="g.sessionId ? '查看会话时间线' : '未归属会话'"
                @click="g.sessionId && router.push('/sessions/' + encodeURIComponent(g.sessionId))">
                <span class="mono sg-id">{{ g.sessionId || '（未归属会话）' }}</span>
                <span class="page-sub">{{ g.traces.length }} 次调用</span>
                <span v-if="g.sessionId" class="sg-link">会话时间线 →</span>
              </div>
              <div v-for="t in g.traces" :key="t.traceId" class="sg-row" @click="goTrace(t.traceId)">
                <span class="mono sg-trace">{{ t.traceId.slice(0, 16) }}{{ t.traceId.length > 16 ? '…' : '' }}</span>
                <span :style="t.errorCount > 0 ? 'color:var(--error);font-size:11px' : 'color:var(--text-muted);font-size:11px'">{{ enumText('spanStatus', t.status) }}</span>
                <span class="sg-input">{{ t.inputPreview || '—' }}</span>
                <span class="num sg-time">{{ fmtRel(t.startTime) }}</span>
              </div>
            </div>
          </template>
          <div v-else class="page-sub">暂无调用记录</div>
        </template>
        <EmptyState v-else text="点击左侧用户查看详情" />
      </n-card>
    </div>
  </div>
</template>

<script setup>
import { computed, h, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NTag, useMessage } from 'naive-ui'
import api from '@/api'
import { enumText } from '@/common/i18n'
import TimeText from '@/components/TimeText.vue'
import EmptyState from '@/components/EmptyState.vue'
import { fmtCost } from '@/common/format'

const route = useRoute()
const router = useRouter()
const message = useMessage()
const rows = ref([]), loading = ref(false)
// 用量页「用户」维度下钻带 ?q=<userId> 进来，直接定位到该用户
const query = ref(String(route.query.q || ''))
const selectedId = ref('')
const detail = ref(null)
const pagination = reactive({
  page: 1, pageSize: 10, itemCount: 0, showSizePicker: true, pageSizes: [10, 20, 50, 100],
  prefix: ({ itemCount }) => '共 ' + itemCount + ' 个用户',
  onChange: (page) => { pagination.page = page; load() },
  onUpdatePageSize: (pageSize) => { pagination.pageSize = pageSize; pagination.page = 1; load() }
})

function fmtRel(iso) {
  if (!iso) return '—'
  const t = new Date(String(iso).length >= 23 ? String(iso).slice(0, 23) : String(iso)).getTime()
  if (Number.isNaN(t)) return iso
  const diff = Date.now() - t
  if (diff < 60000) return '刚刚'
  if (diff < 3600000) return Math.floor(diff / 60000) + ' 分钟前'
  if (diff < 86400000) return Math.floor(diff / 3600000) + ' 小时前'
  return Math.floor(diff / 86400000) + ' 天前'
}
function rowProps(row) {
  return { style: 'cursor:pointer', onClick: () => select(row.userId) }
}
let detailSeq = 0
async function select(userId) {
  selectedId.value = userId
  detail.value = null
  const seq = ++detailSeq
  try {
    const data = await api.userInsightDetail(userId)
    if (seq === detailSeq) detail.value = data
  } catch (e) {
    if (seq === detailSeq) message.error(e.message || '用户详情加载失败')
  }
}
// 二级下钻才离开页面（Langfuse 同款语义：面板内点会话/单条 trace）
function goTrace(id) { router.push('/traces/' + encodeURIComponent(id)) }

const columns = [
  { title: 'User ID', key: 'userId', width: 175, render: (r) => h('span', { class: 'mono' }, r.userId) },
  { title: '调用', key: 'traceCount', width: 65, align: 'right', render: (r) => h('span', { class: 'mono' }, String(r.traceCount)) },
  { title: '会话', key: 'sessionCount', width: 65, align: 'right', render: (r) => h('span', { class: 'mono' }, String(r.sessionCount)) },
  { title: '错误', key: 'errorCount', width: 65, align: 'right', render: (r) => r.errorCount > 0
    ? h(NTag, { size: 'small', bordered: false, type: 'error' }, { default: () => String(r.errorCount) })
    : h('span', { style: 'color:var(--text-muted)' }, '0') },
  { title: '成本', key: 'totalCostUsd', width: 90, align: 'right', render: (r) => h('span', { class: 'mono' }, fmtCost(r.totalCostUsd)) },
  { title: '最近活动', key: 'lastActivity', width: 110, render: (r) => h(TimeText, { value: r.lastActivity }) }
]
/** 最近调用按会话分组（会话按最近活动倒序，组内按时间倒序） */
const recentBySession = computed(() => {
  const traces = [...(detail.value?.recentTraces || [])]
    .sort((a, b) => String(b.startTime || '').localeCompare(String(a.startTime || '')))
  const groups = new Map()
  for (const t of traces) {
    const key = t.sessionId || '__none__'
    if (!groups.has(key)) groups.set(key, { sessionId: t.sessionId || '', traces: [] })
    groups.get(key).traces.push(t)
  }
  return [...groups.values()]
})

async function load() {
  loading.value = true
  try {
    const page = await api.userInsights({ query: query.value.trim() || undefined, pageNumber: pagination.page, pageSize: pagination.pageSize })
    rows.value = page.records || []
    pagination.itemCount = page.total || 0
    // 当前选中用户已不在新结果里：切到首行，避免右侧保留旧用户详情
    if (rows.value.some((r) => r.userId === selectedId.value)) {
      // keep
    } else if (rows.value.length) {
      select(rows.value[0].userId)
    } else {
      selectedId.value = ''
      detail.value = null
    }
  } catch (e) {
    rows.value = []
    pagination.itemCount = 0
    detail.value = null
    message.error(e.message || '用户列表加载失败')
  } finally { loading.value = false }
}
function search() { pagination.page = 1; load() }
onMounted(load)
</script>

<style scoped>
.users-split { display: grid; grid-template-columns: minmax(0, 5fr) minmax(0, 7fr); gap: 12px; flex: 1; min-height: 0; align-items: stretch; }
@media (max-width: 1100px) { .users-split { grid-template-columns: 1fr; } }
.list-card :deep(.row-active td) { background: var(--primary-bg) !important; }
.detail-card { display: flex; flex-direction: column; min-height: 0; overflow: auto; }
.list-card { display: flex; flex-direction: column; min-height: 0; overflow: hidden; }
.d-head { display: flex; align-items: center; gap: 12px; }
.d-avatar { width: 40px; height: 40px; border-radius: 10px; background: var(--primary-bg); color: var(--primary); display: flex; align-items: center; justify-content: center; font-size: 18px; font-weight: 700; }
.d-name { font-size: 15px; font-weight: 600; }
.d-stats { display: flex; gap: 22px; margin: 14px 0 4px; }
.ds { display: flex; flex-direction: column; }
.ds .v { font-size: 19px; font-weight: 600; }
.ds .k { font-size: 11.5px; color: var(--text-muted); }
.panel-title { font-size: 12px; font-weight: 600; color: var(--text-secondary); margin: 16px 0 8px; text-transform: uppercase; letter-spacing: 0.04em; }
.sess-list { display: flex; flex-direction: column; gap: 2px; }
.sess-row { display: flex; align-items: center; gap: 8px; padding: 7px 10px; border: 1px solid var(--border); border-radius: 6px; cursor: pointer; }
.sess-row:hover { border-color: var(--primary); }
.sess-id { font-size: 12.5px; color: var(--primary); max-width: 200px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.flex-1 { flex: 1; }
.mono { font-family: "JetBrains Mono", ui-monospace, Menlo, monospace; }
.num { font-family: "JetBrains Mono", ui-monospace, Menlo, monospace; font-variant-numeric: tabular-nums; }
.page-sub { font-size: 11.5px; color: var(--text-muted); }
.sess-group { margin-bottom: 10px; border: 1px solid var(--border); border-radius: 6px; overflow: hidden; }
.sg-head { display: flex; align-items: center; gap: 8px; padding: 6px 10px; background: var(--bg-subtle); cursor: pointer; }
.sg-head:hover .sg-link { text-decoration: underline; }
.sg-id { font-size: 12px; color: var(--primary); max-width: 180px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.sg-link { margin-left: auto; font-size: 11px; color: var(--primary); }
.sg-row { display: flex; align-items: center; gap: 8px; padding: 5px 10px; border-top: 1px dashed var(--border); cursor: pointer; }
.sg-row:hover { background: var(--bg-subtle); }
.sg-trace { font-size: 11.5px; color: var(--text-secondary); flex: 0 0 130px; }
.sg-input { flex: 1; font-size: 12px; color: var(--text-secondary); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.sg-time { flex: 0 0 64px; text-align: right; font-size: 11px; color: var(--text-muted); }
</style>
