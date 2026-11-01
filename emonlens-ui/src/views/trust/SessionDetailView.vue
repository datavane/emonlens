<template>
  <div class="base-page">
    <!-- 页头 + 统计卡整体吸顶：只有下方调用时间线滚动 -->
    <div class="head-stack">
      <div class="page-head">
        <div class="head-left">
          <n-button size="small" quaternary @click="router.push('/sessions')">← 返回会话</n-button>
          <div class="page-title" :style="detail?.name ? '' : 'font-family:JetBrains Mono,ui-monospace,Menlo,monospace'">
            {{ detail?.name || detail?.sessionId || '' }}</div>
        </div>
        <n-button size="small" @click="load">刷新</n-button>
      </div>

      <n-card size="small" class="mt12" v-if="detail">
        <div class="stat-row">
          <div class="stat"><span class="v num">{{ detail.traceCount }}</span><span class="k">调用</span></div>
          <div class="stat"><span class="v num" :style="detail.errorCount > 0 ? 'color:var(--error)' : ''">{{ detail.errorCount }}</span><span class="k">错误</span></div>
          <div class="stat"><span class="v num">{{ fmtCost(detail.totalCostUsd) }}</span><span class="k">成本</span></div>
          <div class="stat"><span class="v num">{{ fmtDur(detail.totalDurationMs) }}</span><span class="k">总耗时</span></div>
          <div class="stat"><span class="v num">{{ fmtTokens(detail.totalTokens) }}</span><span class="k">Tokens<template v-if="detail.cachedInputTokens"> · 缓存 {{ fmtTokens(detail.cachedInputTokens) }}</template></span></div>
          <div class="stat"><span class="v num">{{ (detail.userIds || []).length || '—' }}</span><span class="k">用户</span></div>
          <div class="stat"><span class="k">首次 {{ fmtTs(detail.firstSeen) }} · 最近 {{ fmtTs(detail.lastActivity) }}</span></div>
        </div>
      </n-card>
    </div>

    <n-card size="small" class="mt12" title="调用时间线（最新在前）">
      <EmptyState v-if="loadError" :text="loadError" action-text="返回会话列表" @action="router.push('/sessions')" />
      <EmptyState v-else-if="!detail && !loading" text="会话不存在或已被删除" action-text="返回会话列表" @action="router.push('/sessions')" />
      <EmptyState v-else-if="!detail" text="加载中…" />
      <EmptyState v-else-if="!(detail.traces || []).length" text="该会话暂无调用记录" />
      <div v-else class="timeline">
        <div v-for="(t, idx) in timeline" :key="t.traceId" class="tl-item"
          :style="{ cursor: 'pointer' }" @click="router.push('/traces/' + encodeURIComponent(t.traceId))">
          <div class="tl-time">{{ fmtTs(t.startTime) }}</div>
          <div class="tl-rail">
            <span class="dot" :class="t.errorCount > 0 ? 'dot-error' : 'dot-ok'"></span>
            <span v-if="idx < timeline.length - 1" class="line"></span>
          </div>
          <div class="tl-body">
            <div class="tl-head">
              <span class="mono link">{{ t.traceId }}</span>
            </div>
            <div class="tl-preview">{{ t.inputPreview || '（无输入摘要）' }}</div>
            <div class="tl-meta">
              <span>{{ t.agentKey }}</span>
              <span :style="t.errorCount > 0 ? 'color:var(--error)' : 'color:var(--text-muted)'">{{ enumText('spanStatus', t.status) }}{{ t.errorCount > 0 ? ' · ' + t.errorCount + ' 错误' : '' }}</span>
              <span class="num">{{ fmtDur(t.durationMs) }}</span>
              <span class="num">{{ fmtCost(t.totalCostUsd) }}</span>
              <span v-if="t.humanScore != null" class="num hs-chip"
                :style="{ color: t.humanScore >= 6 ? 'var(--success)' : 'var(--error)' }"
                title="点击查看该 Trace 的评估记录"
                @click.stop="router.push({ path: '/evaluations', query: { traceId: t.traceId } })">
                人评 {{ t.humanScore }}
              </span>
              <span v-if="t.autoEvalCount" class="num hs-chip" title="自动评估结果数，点击按该 Trace 筛选"
                @click.stop="router.push({ path: '/evaluations', query: { traceId: t.traceId } })">
                自动评估 {{ t.autoEvalCount }}
              </span>
              <span v-if="t.findingCount" class="num hs-chip hs-alert"
                :title="'该 Trace 有 ' + t.findingCount + ' 条异常发现（最高 ' + t.topSeverity + '），点击查看告警与异常'"
                @click.stop="router.push({ path: '/alerts', query: { tab: 'findings' } })">
                ⚠ {{ t.findingCount }} 条异常
              </span>
              <span v-if="t.userId" class="mono">{{ t.userId }}</span>
            </div>
          </div>
          <div class="tl-gap">
            <span v-if="idx > 0 && gapOf(t, timeline[idx - 1])" class="gap-chip"
              title="距下一次调用的时间间隔">⏱ 间隔 {{ gapOf(t, timeline[idx - 1]) }}</span>
          </div>
        </div>
      </div>
    </n-card>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import api from '@/api'
import { enumText } from '@/common/i18n'
import EmptyState from '@/components/EmptyState.vue'
import { fmtDur, fmtCost, fmtTokens } from '@/common/format'

const route = useRoute()
const router = useRouter()
const sessionId = String(route.params.sessionId || '')
const detail = ref(null)
const loading = ref(true)
const loadError = ref('')

// 时间线倒序渲染：最新调用在最上（服务端按开始时间正序返回，这里反转）
const timeline = computed(() => {
  const list = [...(detail.value?.traces || [])]
  list.sort((a, b) => String(b.startTime || '').localeCompare(String(a.startTime || '')))
  return list
})

function fmtTs(s) {
  if (!s) return '—'
  return String(s).replace('T', ' ').slice(0, 19)
}
function gapOf(earlier, later) {
  // 倒序时间线：chip 挂在较早一侧，表示「距下一次调用」的间隔（later 为更新的一条）
  if (!earlier?.startTime || !later?.startTime) return ''
  const gap = new Date(String(later.startTime).slice(0, 23)) - new Date(String(earlier.startTime).slice(0, 23))
  if (!Number.isFinite(gap) || gap <= 0) return ''
  if (gap >= 3600000) return (gap / 3600000).toFixed(1) + ' h'
  if (gap >= 60000) return (gap / 60000).toFixed(1) + ' min'
  return Math.round(gap / 1000) + ' s'
}
async function load() {
  loading.value = true
  loadError.value = ''
  try {
    detail.value = await api.sessionDetail(sessionId)
    if (!detail.value) loadError.value = '会话不存在或已被删除'
  } catch (e) {
    detail.value = null
    loadError.value = e?.message || '会话不存在或已被删除'
  } finally { loading.value = false }
}
onMounted(load)
</script>

<style scoped>
.mt12 { margin-top: 12px; }
.mono { font-family: "JetBrains Mono", ui-monospace, Menlo, monospace; }
.stat-row { display: flex; gap: 28px; align-items: baseline; flex-wrap: wrap; }
.stat { display: flex; flex-direction: column; gap: 2px; }
.stat .v { font-size: 20px; font-weight: 600; }
.stat .k { font-size: 11.5px; color: var(--text-muted); }
.timeline { display: flex; flex-direction: column; margin-top: 8px; max-height: 520px; overflow-y: auto; padding-right: 4px; }
.tl-item { display: flex; gap: 12px; }
/* 最左时间列：固定宽，长会话也不与内容挤压 */
.tl-time { flex: 0 0 150px; font-size: 11.5px; color: var(--text-muted); padding-top: 5px; white-space: nowrap; }
.tl-rail { display: flex; flex-direction: column; align-items: center; width: 14px; flex: 0 0 14px; }
.dot { width: 9px; height: 9px; border-radius: 50%; margin-top: 7px; }
.dot-ok { background: var(--primary); }
.dot-error { background: var(--error); }
.line { flex: 1; width: 1.5px; background: var(--border); margin-top: 2px; }
/* min-width:0 允许内容截断：长命令摘要不再把页面横向撑破 */
.tl-body { flex: 1; min-width: 0; padding: 4px 0 16px; border-bottom: 1px dashed var(--border); }
.tl-item:last-child .tl-body { border-bottom: none; }
.tl-head { display: flex; justify-content: space-between; gap: 10px; min-width: 0; }
.tl-head .mono { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.tl-head .page-sub { flex-shrink: 0; }
.link { color: var(--primary); }
.link:hover { text-decoration: underline; }
.tl-preview { margin-top: 4px; font-size: 13px; color: var(--text-secondary); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
/* 右侧 gutter 固定宽：容纳「⏱ 间隔 59.9 min」最长文案且所有行的 rail 对齐 */
.tl-gap { flex: 0 0 104px; min-width: 0; display: flex; justify-content: flex-end; align-items: flex-start; padding-top: 5px; }
.gap-chip { white-space: nowrap; max-width: 100%; overflow: hidden; text-overflow: ellipsis;
  font-size: 11px; color: var(--text-muted); background: var(--bg-subtle); border-radius: 4px; padding: 2px 6px; }
.tl-meta { display: flex; gap: 12px; margin-top: 4px; font-size: 11.5px; color: var(--text-muted); }
.hs-chip { background: var(--bg-subtle); border-radius: 4px; padding: 1px 6px; font-weight: 600; cursor: pointer; }
.hs-alert { background: var(--error-bg); }
.page-sub { font-size: 11.5px; color: var(--text-muted); }
</style>
