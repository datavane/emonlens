<template>
  <n-data-table class="main-table" flex-height style="height: 100%" :columns="columns" :data="rows" size="small" :bordered="false" :loading="loading" remote
    :pagination="pagination" :row-props="rowProps">
    <template #empty><EmptyState text="没有匹配的会话" /></template>
  </n-data-table>
</template>

<script setup>
import { h, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { NTag } from 'naive-ui'
import api from '@/api'
import TimeText from '@/components/TimeText.vue'
import EmptyState from '@/components/EmptyState.vue'
import { fmtDur, fmtCost } from '@/common/format'

const props = defineProps({
  initialQuery: { type: String, default: '' }
})
const router = useRouter()
const rows = ref([]), loading = ref(false)
const query = ref(props.initialQuery || '')
const pagination = reactive({
  page: 1, pageSize: 10, itemCount: 0, showSizePicker: true, pageSizes: [10, 20, 50, 100],
  prefix: ({ itemCount }) => '共 ' + itemCount + ' 个会话',
  onChange: (page) => { pagination.page = page; load() },
  onUpdatePageSize: (pageSize) => { pagination.pageSize = pageSize; pagination.page = 1; load() }
})


const columns = [
  { title: '会话', key: 'sessionId', minWidth: 240, render: (r) => h('div', {}, [
      r.name
        ? h('div', { style: 'font-size:13px;color:var(--text);overflow:hidden;text-overflow:ellipsis;white-space:nowrap', title: r.name }, r.name)
        : h('div', { style: 'font-size:13px;color:var(--text-muted);font-style:italic' }, '（无输入摘要）'),
      h('div', { class: 'mono', style: 'font-size:11px;color:var(--primary)' }, r.sessionId)
    ]) },
  { title: '用户', key: 'userIds', width: 140, render: (r) => (r.userIds || []).length
    ? h('span', { class: 'mono' }, r.userIds.slice(0, 2).join(', ') + ((r.userIds.length > 2) ? '…' : '')) : '—' },
  { title: 'Agent', key: 'agentKeys', width: 130, render: (r) => (r.agentKeys || []).slice(0, 2).join(', ') || '—' },
  { title: '调用', key: 'traceCount', width: 70, align: 'right', render: (r) => h('span', { class: 'num' }, String(r.traceCount)) },
  { title: '错误', key: 'errorCount', width: 70, align: 'right', render: (r) => r.errorCount > 0
    ? h(NTag, { size: 'small', bordered: false, type: 'error' }, { default: () => String(r.errorCount) })
    : h('span', { style: 'color:var(--text-muted)' }, '0') },
  { title: '成本', key: 'totalCostUsd', width: 90, align: 'right', render: (r) => h('span', { class: 'num' }, fmtCost(r.totalCostUsd)) },
  { title: '总耗时', key: 'totalDurationMs', width: 100, align: 'right', render: (r) => h('span', { class: 'num' }, fmtDur(r.totalDurationMs)) },
  { title: '开始', key: 'firstSeen', width: 150, render: (r) => h(TimeText, { value: r.firstSeen }) },
  { title: '最近活动', key: 'lastActivity', width: 150, render: (r) => h(TimeText, { value: r.lastActivity }) }
]
const rowProps = (row) => ({ style: 'cursor:pointer', onClick: () => router.push('/sessions/' + encodeURIComponent(row.sessionId)) })

async function load() {
  loading.value = true
  try {
    const page = await api.sessionsList({ query: query.value.trim() || undefined, pageNumber: pagination.page, pageSize: pagination.pageSize })
    rows.value = page.records || []
    pagination.itemCount = page.total || 0
  } catch (e) {
    rows.value = []
    pagination.itemCount = 0
    message.error(e.message || '会话列表加载失败')
  } finally { loading.value = false }
}
function reload(nextQuery) {
  query.value = nextQuery == null ? '' : nextQuery
  pagination.page = 1
  load()
}
defineExpose({ reload })
onMounted(load)
</script>

<style scoped>
.mono { font-family: "JetBrains Mono", ui-monospace, Menlo, monospace; }
</style>
