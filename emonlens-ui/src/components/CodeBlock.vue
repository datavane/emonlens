<template>
  <div class="code-block-wrap">
    <div class="code-toolbar">
      <span v-if="label" class="code-label">{{ label }}</span>
      <span class="flex-1"></span>
      <button class="code-btn" type="button" @click="toggleCollapse">{{ collapsed ? '展开' : '收起' }}</button>
      <button class="code-btn" type="button" @click="copy">复制</button>
    </div>
    <pre v-if="isJson" class="code-block" :class="{ clamped: collapsed && lineCount > collapseLines }" :style="collapsed ? '' : 'max-height:' + maxHeight + 'px'" v-html="html"></pre>
    <pre v-else class="code-block" :class="{ clamped: collapsed && lineCount > collapseLines }" :style="collapsed ? '' : 'max-height:' + maxHeight + 'px'">{{ text }}</pre>
    <div v-if="collapsed && lineCount > collapseLines" class="code-more" @click="collapsed = false">显示全部 {{ lineCount }} 行</div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useMessage } from 'naive-ui'

const props = defineProps({
  content: { type: [String, Object, Array], default: '' },
  label: { type: String, default: '' },
  maxHeight: { type: Number, default: 320 },
  collapseLines: { type: Number, default: 12 },
  startExpanded: { type: Boolean, default: false }
})
const message = useMessage()
const collapsed = ref(!props.startExpanded)

const isJson = ref(false)
const text = computed(() => {
  const c = props.content
  if (c == null || c === '') { isJson.value = false; return '' }
  let out = c
  isJson.value = false
  if (typeof c === 'string') {
    const t = c.trim()
    if ((t.startsWith('{') && t.endsWith('}')) || (t.startsWith('[') && t.endsWith(']'))) {
      try { out = JSON.stringify(JSON.parse(t), null, 2); isJson.value = true } catch (e) { out = c }
    }
  } else {
    try { out = JSON.stringify(c, null, 2); isJson.value = true } catch (e) { out = String(c) }
  }
  return out
})
// JSON 语法着色（无依赖 tokenizer）
const html = computed(() => {
  if (!isJson.value) return ''
  const esc = text.value.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  return esc.replace(/("(\\u[a-zA-Z0-9]{4}|\\[^u]|[^\\"])*"(\s*:)?|\b(true|false|null)\b|-?\d+(?:\.\d*)?(?:[eE][+\-]?\d+)?)/g, (m) => {
    let cls = 'j-num'
    if (m.startsWith('"')) cls = m.endsWith(':') ? 'j-key' : 'j-str'
    else if (m === 'true' || m === 'false') cls = 'j-bool'
    else if (m === 'null') cls = 'j-null'
    return `<span class="${cls}">${m}</span>`
  })
})
const lineCount = computed(() => text.value.split('\n').length)
async function copy() {
  try {
    if (!navigator.clipboard?.writeText) throw new Error('clipboard unavailable')
    await navigator.clipboard.writeText(text.value)
    message.success('已复制')
  } catch (e) {
    // 非安全上下文 / 权限被拒时不能假装成功
    message.error('复制失败，请手动选择文本')
  }
}
function toggleCollapse() { collapsed.value = !collapsed.value }
</script>

<style scoped>
.code-block-wrap { border-radius: var(--radius-ctl); overflow: hidden; border: 1px solid var(--code-border); }
.code-toolbar { display: flex; align-items: center; gap: 6px; padding: 4px 8px; background: var(--code-chrome); }
.code-label { font-size: 11px; color: var(--code-muted); font-family: "JetBrains Mono", ui-monospace, Menlo, monospace; }
.flex-1 { flex: 1; }
.code-btn { padding: 1px 8px; font-size: 11px; color: var(--code-text); background: transparent; border: 1px solid var(--code-border); border-radius: 4px; cursor: pointer; }
.code-btn:hover { color: var(--primary); border-color: var(--primary); }
.code-block {
  margin: 0;
  padding: 10px 12px;
  background: var(--code-bg);
  color: var(--code-text);
  font-family: "JetBrains Mono", ui-monospace, Menlo, monospace;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
  overflow: auto;
  max-height: 320px;
}
.code-block.clamped { max-height: calc(12 * 1.6 * 12px + 20px); overflow: auto; }
.code-more { padding: 4px; text-align: center; font-size: 11px; color: var(--code-muted); background: var(--code-chrome); cursor: pointer; }
.code-more:hover { color: var(--primary); }
</style>
