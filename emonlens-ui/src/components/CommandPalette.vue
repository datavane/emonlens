<template>
  <teleport to="body">
    <div v-if="open" class="cmdk-mask" @click.self="close">
      <div class="cmdk-panel">
        <input
          ref="inputRef"
          v-model="query"
          class="cmdk-input"
          placeholder="搜索页面、Trace、会话…（↑↓ 选择，回车打开）"
          @keydown.down.prevent="move(1)"
          @keydown.up.prevent="move(-1)"
          @keydown.enter="go(items[active])"
          @keydown.esc="close"
        />
        <div class="cmdk-list">
          <div
            v-for="(item, i) in items"
            :key="item.key"
            class="cmdk-row"
            :class="{ active: i === active }"
            @mouseenter="active = i"
            @click="go(item)"
          >
            <span class="cmdk-label">{{ item.label }}</span>
            <span class="cmdk-hint">{{ item.hint }}</span>
          </div>
          <div v-if="!items.length" class="cmdk-empty">无匹配结果</div>
        </div>
        <div class="cmdk-foot">⌘K 打开 · Esc 关闭</div>
      </div>
    </div>
  </teleport>
</template>

<script setup>
import { computed, nextTick, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/store/auth'
import { MENU, MENU_SECTION_LABELS, TAB_GROUPS } from '@/common/menu'
import api from '@/api'

const router = useRouter()
const auth = useAuthStore()
const open = ref(false)
const query = ref('')
const active = ref(0)
const inputRef = ref(null)

function openPalette() {
  open.value = true
  query.value = ''
  active.value = 0
  nextTick(() => inputRef.value?.focus())
}
function close() {
  open.value = false
}
function go(item) {
  if (!item) return
  close()
  if (!item.path) return
  // 动态项（打开 Trace/会话）依赖 query 参数，不能丢
  router.push(item.query ? { path: item.path, query: item.query } : item.path)
}

const items = computed(() => {
  const q = query.value.trim().toLowerCase()
  const out = []
  // 页面导航（按权限过滤）
  for (const m of MENU) {
    if (!auth.hasPermission(m.perm)) continue
    if (!q || m.label.toLowerCase().includes(q) || (MENU_SECTION_LABELS[m.section] || m.section).toLowerCase().includes(q) || m.path.includes(q)) {
      out.push({ key: 'p-' + m.key, label: m.label, hint: MENU_SECTION_LABELS[m.section] || m.section, path: m.path })
    }
  }
  // 二级 Tab 页面（领域包 / 规则候选 / 质量案例等）也进入搜索
  const groupLabels = { assets: '数据集与资产', insights: '洞察与成本', workbench: '调试与提示词' }
  for (const [group, tabs] of Object.entries(TAB_GROUPS)) {
    for (const t of tabs) {
      if (!auth.hasPermission(t.perm)) continue
      if (!q || t.label.toLowerCase().includes(q) || (groupLabels[group] || '').toLowerCase().includes(q) || t.path.includes(q)) {
        out.push({ key: 't-' + group + '-' + t.path, label: t.label, hint: groupLabels[group] || '页面', path: t.path })
      }
    }
  }
  // 快速打开 Trace / 会话（直接以输入值作为 ID 打开）
  if (q.length >= 3) {
    out.push({ key: 't-dyn', label: '打开调用链路：' + query.value.trim(), hint: '调用链路详情', path: '/traces', query: { traceId: query.value.trim() } })
    out.push({ key: 's-dyn', label: '打开会话：' + query.value.trim(), hint: '会话时间线', path: '/sessions/' + encodeURIComponent(query.value.trim()) })
  }
  // 异步匹配结果：会话（按名称/ID/用户）与 Trace ID
  for (const s of sessionHits.value) {
    out.push({ key: 's-' + s.sessionId, label: s.name || s.sessionId, hint: '会话 · ' + s.sessionId, path: '/sessions/' + encodeURIComponent(s.sessionId) })
  }
  for (const t of traceHits.value) {
    out.push({ key: 'tr-' + t.traceId, label: t.inputPreview || t.traceId, hint: 'Trace · ' + t.traceId, path: '/traces/' + encodeURIComponent(t.traceId) })
  }
  return out.slice(0, 12)
})

// 搜索防抖：输入 ≥2 字符时异步匹配会话与 trace
const sessionHits = ref([])
const traceHits = ref([])
let searchTimer = null
let searchSeq = 0
watch(query, (v) => {
  clearTimeout(searchTimer)
  const kw = v.trim()
  if (kw.length < 2) { sessionHits.value = []; traceHits.value = []; return }
  const seq = ++searchSeq
  searchTimer = setTimeout(async () => {
    try {
      const s = await api.sessionsList({ query: kw, pageNumber: 1, pageSize: 3 })
      if (seq !== searchSeq) return
      sessionHits.value = (s.records || []).filter((x) => x.sessionId !== kw)
    } catch (e) { if (seq === searchSeq) sessionHits.value = [] }
    try {
      const t = await api.traceSearch({ query: kw, pageNumber: 1, pageSize: 3 })
      if (seq !== searchSeq) return
      traceHits.value = (t.records || []).filter((x) => x.traceId !== kw)
    } catch (e) { if (seq === searchSeq) traceHits.value = [] }
  }, 250)
})
onUnmounted(() => clearTimeout(searchTimer))

function move(delta) {
  if (!items.value.length) return
  active.value = (active.value + delta + items.value.length) % items.value.length
}

watch(open, (v) => { if (!v) query.value = '' })
// 过滤变化时重置高亮，避免回车跳到旧选中项
watch(query, () => { active.value = 0 })

defineExpose({ openPalette })
</script>

<style>
.cmdk-mask { position: fixed; inset: 0; background: var(--overlay); z-index: 3000; display: flex; align-items: flex-start; justify-content: center; padding-top: 12vh; }
.cmdk-panel { width: 560px; max-width: 92vw; background: var(--bg-surface); border-radius: 10px; box-shadow: 0 20px 60px rgba(15, 23, 42, 0.25); overflow: hidden; }
.cmdk-input { width: 100%; height: 48px; padding: 0 16px; font-size: 14px; color: var(--text-primary); background: transparent; border: 0; outline: none; border-bottom: 1px solid var(--border); box-sizing: border-box; }
.cmdk-list { max-height: 320px; overflow: auto; padding: 6px; }
.cmdk-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 9px 12px; border-radius: 6px; cursor: pointer; font-size: 13px; color: var(--text-primary); }
.cmdk-row.active { background: var(--bg-subtle); }
.cmdk-hint { font-size: 11.5px; color: var(--text-muted); }
.cmdk-empty { padding: 20px; text-align: center; font-size: 12.5px; color: var(--text-muted); }
.cmdk-foot { padding: 8px 14px; font-size: 11px; color: var(--text-muted); border-top: 1px solid var(--border); }
</style>
