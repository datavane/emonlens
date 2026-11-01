<template>
  <div class="base-page">
    <div class="page-head">
      <div class="head-left">
        <n-button size="small" quaternary @click="router.push('/annotation-queues')">← 返回</n-button>
        <div class="page-title">{{ detail?.name || '' }}</div>
      </div>
      <div class="head-right">
        <div class="head-progress" :title="doneCount + ' / ' + totalCount + ' 已完成'">
          <div class="hp-bar"><i :style="{ transform: 'scaleX(' + progressPct / 100 + ')' }"></i></div>
          <span class="num hp-text">{{ doneCount }}/{{ totalCount }}</span>
        </div>
        <n-button size="small" @click="openAdd">＋ 添加样本</n-button>
        <n-button type="primary" size="small" :disabled="!current" @click="scrollToCurrent">继续标注</n-button>
      </div>
    </div>

    <!-- 当前待标注 -->
    <n-card v-if="current" size="small" class="mt12" title="当前样本">
      <template #header-extra>
        <span class="page-sub mono">{{ current.traceId }}</span>
      </template>
      <div v-if="trace" class="cur-trace">
        <div class="cur-meta">
          <span>{{ trace.summary?.agentKey || '—' }}</span>
          <span v-if="trace.summary?.modelName" class="mono">{{ trace.summary.modelName }}</span>
          <span class="num">耗时 {{ fmtDur(trace.summary?.durationMs) }}</span>
          <span class="num">成本 {{ fmtCost(trace.summary?.totalCostUsd) }}</span>
          <span v-if="autoEvalCount" class="num" style="color:var(--warn)">自动评估 {{ autoEvalCount }} 条可参考</span>
        </div>
        <div class="msg user-msg">
          <div class="role-line"><span class="role">用户输入</span><TimeText :value="trace.summary?.startTime" /></div>
          <div class="msg-text">{{ trace.summary?.inputPreview || '（无输入内容）' }}</div>
        </div>
        <div class="msg agent-msg">
          <div class="role-line">
            <span class="role">助手输出</span>
            <n-tag v-if="trace.summary?.modelName" size="tiny" :bordered="false">{{ trace.summary.modelName }}</n-tag>
            <n-button size="tiny" type="primary" ghost @click="router.push('/traces/' + encodeURIComponent(current.traceId))">查看完整链路</n-button>
          </div>
          <div class="msg-text">{{ trace.summary?.outputPreview || '（无输出内容）' }}</div>
        </div>
        <div class="annotate-row">
          <n-button size="small" type="success" ghost @click="complete(9, '好评')">好评</n-button>
          <n-button size="small" type="error" ghost @click="complete(2, '差评')">差评</n-button>
          <n-input-number v-model:value="scoreForm.score" size="small" :min="0" :max="10" :step="0.5" style="width: 130px" />
          <n-input v-model:value="scoreForm.comment" size="small" placeholder="复核备注（可选）" style="flex: 1" />
          <n-button type="primary" size="small" :loading="submitting" @click="complete(null, null)">提交并下一条</n-button>
        </div>
      </div>
      <n-spin v-else style="width: 100%; margin: 24px 0" />
    </n-card>
    <EmptyState v-else-if="loadError" :text="loadError" action-text="返回队列列表" @action="router.push('/annotation-queues')" />
    <n-card v-else size="small" class="mt12">
      <EmptyState text="队列已全部标注完成 🎉" />
    </n-card>

    <!-- 样本列表 -->
    <n-card size="small" class="mt12" :title="'样本（' + ((detail?.items || []).length) + '）'">
      <n-data-table :columns="itemColumns" :data="detail?.items || []" size="small" :bordered="false" :loading="loading" :max-height="420" />
    </n-card>

    <!-- 分配标注人 -->
    <Modal v-model:show="showAssign" :width="440" title="分配标注人">
      <div class="page-sub mono" style="margin-bottom:10px">Trace：{{ assignTarget?.traceId }}</div>
      <n-select v-model:value="assignUser" :options="userOptions" placeholder="选择标注人（完成后归属该标注人）" />
      <template #footer>
        <n-button size="small" :disabled="assigning" @click="showAssign = false">取消</n-button>
        <n-button type="primary" size="small" :loading="assigning" @click="doAssign">确认分配</n-button>
      </template>
    </Modal>

    <Modal v-model:show="showAdd" :width="520" title="添加样本（每行一个 Trace ID）">
      <n-input v-model:value="addText" type="textarea" :rows="6" class="mono" placeholder="trace-8f3a2b&#10;trace-1c9d04" />
      <template #footer>
        <n-button size="small" :disabled="saving" @click="showAdd = false">取消</n-button>
        <n-button type="primary" size="small" :loading="saving" @click="doAdd">添加</n-button>
      </template>
    </Modal>
  </div>
</template>

<script setup>
import { computed, h, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NButton, NTag } from 'naive-ui'
import { useMessage } from 'naive-ui'
import api from '@/api'
import TimeText from '@/components/TimeText.vue'
import EmptyState from '@/components/EmptyState.vue'

const route = useRoute()
const router = useRouter()
const message = useMessage()
const queueId = String(route.params.id || '')
const detail = ref(null), trace = ref(null), loading = ref(true), loadError = ref('')
const showAdd = ref(false), saving = ref(false), submitting = ref(false)
const addText = ref('')
const scoreForm = reactive({ score: 8, comment: '' })

const current = computed(() => (detail.value?.items || []).find((i) => i.status === 'PENDING') || null)

// ---------- 队列进度 / 当前样本元信息 ----------
const totalCount = computed(() => (detail.value?.items || []).length)
const doneCount = computed(() => (detail.value?.items || []).filter((i) => i.status === 'COMPLETED').length)
const progressPct = computed(() => totalCount.value ? Math.round(doneCount.value * 100 / totalCount.value) : 0)
const autoEvalCount = ref(0)
function fmtDur(ms) {
  if (ms == null) return '—'
  if (ms >= 60000) return (ms / 60000).toFixed(1) + ' min'
  if (ms >= 1000) return (ms / 1000).toFixed(2) + ' s'
  return ms + ' ms'
}
function fmtCost(v) {
  const n = Number(v || 0)
  return '$' + n.toFixed(4)
}

function statusTag(s) {
  return h(NTag, { size: 'small', bordered: false, type: s === 'COMPLETED' ? 'success' : 'warning' }, { default: () => (s === 'COMPLETED' ? '已完成' : '待标注') })
}
const itemColumns = [
  { title: 'Trace', key: 'traceId', width: 200, ellipsis: { tooltip: true },
    render: (r) => h('span', { class: 'mono link', title: r.traceId, onClick: () => router.push({ path: '/traces', query: { traceId: r.traceId } }) }, r.traceId) },
  { title: '状态', key: 'status', width: 90, render: (r) => statusTag(r.status) },
  { title: '标注人', key: 'reviewer', width: 170, render: (r) => r.status === 'COMPLETED'
      ? (r.reviewer || '—')
      : h('span', { style: 'display:inline-flex;align-items:center;gap:6px' }, [
          r.reviewer ? h('span', { style: 'font-size:12px' }, r.reviewer) : h('span', { style: 'color:var(--text-muted);font-size:12px' }, '未分配'),
          h(NButton, { size: 'tiny', quaternary: true, type: 'primary', onClick: () => openAssign(r) }, { default: () => '分配' })
        ]) },
  { title: '分数', key: 'score', width: 70, align: 'right', render: (r) => r.score == null ? '—' : String(r.score) },
  { title: '备注', key: 'comment', minWidth: 200, ellipsis: { tooltip: true }, render: (r) => r.comment || '—' },
  { title: '完成时间', key: 'completedAt', width: 160, render: (r) => h('span', { class: 'num', style: 'font-size:11.5px' }, r.completedAt ? String(r.completedAt).replace('T', ' ').slice(0, 19) : '—') }
]

// ---------- 标注人分配 ----------
const userOptions = ref([])
const showAssign = ref(false), assignTarget = ref(null), assignUser = ref(null), assigning = ref(false)
async function loadUsers() {
  try {
    const page = await api.sysUsers({ pageNumber: 1, pageSize: 100 })
    userOptions.value = (page.records || []).filter((u) => u.status === 'ACTIVE').map((u) => ({ label: u.name ? u.name + '（' + u.username + '）' : u.username, value: u.username }))
  } catch (e) { /* 无用户管理权限时忽略 */ }
}
function openAssign(r) {
  assignTarget.value = r
  assignUser.value = r.reviewer || null
  showAssign.value = true
}
async function doAssign() {
  if (!assignUser.value) { message.error('请选择标注人'); return }
  assigning.value = true
  try {
    await api.assignAnnotationItem(queueId, assignTarget.value.id, assignUser.value)
    message.success(`已分配给 ${assignUser.value}`)
    showAssign.value = false
    load()
  } catch (e) { message.error(e.message) }
  finally { assigning.value = false }
}

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    detail.value = await api.annotationQueueDetail(queueId)
    if (current.value) {
      trace.value = await api.traceDetail(current.value.traceId).catch(() => null)
      try {
        const page = await api.evalResults({ traceId: current.value.traceId, pageNumber: 1, pageSize: 20 })
        autoEvalCount.value = page.total || 0
      } catch (e) { autoEvalCount.value = 0 }
    } else {
      trace.value = null
      autoEvalCount.value = 0
    }
  } catch (e) {
    detail.value = null
    loadError.value = e?.message || '队列不存在或已被删除'
  } finally { loading.value = false }
}
function scrollToCurrent() {
  document.querySelector('.cur-trace')?.scrollIntoView({ behavior: 'smooth', block: 'center' })
}
function openAdd() {
  addText.value = ''
  showAdd.value = true
}
async function doAdd() {
  const ids = addText.value.split('\n').map((s) => s.trim()).filter(Boolean)
  if (!ids.length) { message.error('请填写至少一个 Trace ID'); return }
  saving.value = true
  try {
    const added = await api.addAnnotationItems(queueId, ids)
    message.success(`已添加 ${added} 条（去重后）`)
    showAdd.value = false
    load()
  } catch (e) { message.error(e.message) }
  finally { saving.value = false }
}
async function complete(fixedScore, fixedComment) {
  if (!current.value) return
  submitting.value = true
  try {
    await api.completeAnnotationItem(queueId, current.value.id, {
      score: fixedScore ?? scoreForm.score,
      comment: fixedComment ?? scoreForm.comment
    })
    message.success('已标注，进入下一条')
    scoreForm.comment = ''
    scoreForm.score = 8
    load()
  } catch (e) { message.error(e.message) }
  finally { submitting.value = false }
}
onMounted(() => { load(); loadUsers() })
</script>

<style scoped>
.mt12 { margin-top: 12px; }
.mono { font-family: "JetBrains Mono", ui-monospace, Menlo, monospace; }
.link { color: var(--primary); cursor: pointer; }
.link:hover { text-decoration: underline; }
.page-sub { font-size: 11.5px; color: var(--text-muted); }
.cur-trace { display: flex; flex-direction: column; gap: 8px; }
.cur-meta { display: flex; gap: 14px; flex-wrap: wrap; font-size: 12px; color: var(--text-muted); }
.head-progress { display: flex; align-items: center; gap: 8px; }
.hp-bar { width: 120px; height: 6px; background: var(--bg-subtle); border-radius: 3px; overflow: hidden; }
.hp-bar i { display: block; width: 100%; height: 100%; background: var(--success); border-radius: 3px; transform-origin: left center; transition: transform .3s; }
.hp-text { font-size: 11.5px; color: var(--text-muted); }
.msg { border-radius: 6px; padding: 10px 12px; }
.user-msg { background: var(--bg-subtle); }
.agent-msg { border: 1px solid var(--border); }
.role-line { display: flex; align-items: center; gap: 8px; margin-bottom: 4px; }
.role { font-size: 12px; font-weight: 600; color: var(--text-secondary); }
.msg-text { font-size: 13px; line-height: 1.6; white-space: pre-wrap; word-break: break-word; }
.annotate-row { display: flex; align-items: center; gap: 8px; margin-top: 4px; flex-wrap: wrap; }
</style>
