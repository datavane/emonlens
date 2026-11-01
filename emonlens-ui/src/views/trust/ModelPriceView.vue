<template>
  <div class="base-page">
    <div class="head-stack">
      <div class="page-toolbar">
        <div class="toolbar-actions">
          <n-button type="primary" size="small" @click="openCreate">＋ 新增模型</n-button>
        </div>
      </div>
    </div>

    <n-card size="small">
      <template #header>
        <div style="display:flex;align-items:center;gap:8px">
          <span>价格 × 实际用量（近 30 天）</span>
          <span class="page-sub">调用数与成本来自 trace 聚合，tokens 来自 llm span 聚合</span>
        </div>
      </template>
      <n-data-table :columns="usageColumns" :data="usageRows" size="small" :bordered="false" :loading="loading" :max-height="320" />
    </n-card>

    <n-card v-if="unpriced.length" size="small" class="mt12" title="有用量但未配置价格（成本无法估算）">
      <div class="unpriced-list">
        <div v-for="m in unpriced" :key="m.model" class="up-row">
          <span class="mono">{{ m.model }}</span>
          <span class="num">{{ m.traces }} 次调用</span>
          <span class="num">{{ fmtTokens(m) }}</span>
          <n-button size="tiny" type="primary" ghost @click="openCreateFor(m.model)">补录价格</n-button>
        </div>
      </div>
    </n-card>

    <n-card size="small" class="mt12">
      <n-data-table :columns="columns" :data="records" size="small" :bordered="false" :loading="loading" :max-height="420" />
    </n-card>

    <Modal v-model:show="showEdit" :width="480" :title="editing ? '编辑价格' : '新增模型价格'">
      <n-form label-placement="top">
        <n-form-item label="模型名" required>
          <n-input v-model:value="form.model" :disabled="editing" placeholder="如 gpt-4o-mini" />
        </n-form-item>
        <n-form-item label="输入价格（$/1M tokens）" required>
          <n-input-number v-model:value="form.inputPerMillion" :min="0" :step="0.05" style="width: 100%" />
        </n-form-item>
        <n-form-item label="输出价格（$/1M tokens）" required>
          <n-input-number v-model:value="form.outputPerMillion" :min="0" :step="0.05" style="width: 100%" />
        </n-form-item>
        <n-form-item label="缓存命中价格（$/1M tokens，留空=输入价 10%）">
          <n-input-number v-model:value="form.cachedInputPerMillion" :min="0" :step="0.05" style="width: 100%"
            placeholder="如 0.5" clearable />
          <template #feedback>命中 Prompt Cache 的输入按此价计费（OpenAI/DeepSeek 惯例约为全价 1/10）——高缓存命中的 Agent 若按全价计费，成本会虚高数倍。</template>
        </n-form-item>
        <n-form-item label="币种">
          <n-select v-model:value="form.currency" :options="[{ label: 'USD（美元）', value: 'USD' }, { label: 'CNY（人民币）', value: 'CNY' }]" />
        </n-form-item>
      </n-form>
      <template #footer>
        <n-button size="small" :disabled="saving" @click="showEdit = false">取消</n-button>
        <n-button type="primary" size="small" :loading="saving" @click="doSave">保存</n-button>
      </template>
    </Modal>
  </div>
</template>

<script setup>
import { h, onMounted, reactive, ref } from 'vue'
import { NButton, NPopconfirm, useMessage } from 'naive-ui'
import api from '@/api'

const message = useMessage()
const records = ref([]), loading = ref(false)
const usageRows = ref([]), unpriced = ref([])
const showEdit = ref(false), editing = ref(false), saving = ref(false)
const editingId = ref('')
const form = reactive({ model: '', inputPerMillion: 0.15, outputPerMillion: 0.6, cachedInputPerMillion: null, currency: 'USD' })

const columns = [
  { title: '模型', key: 'model', minWidth: 140, ellipsis: { tooltip: true }, render: (r) => h('span', { class: 'mono' }, r.model) },
  { title: '输入 $/1M', key: 'inputPerMillion', width: 95, align: 'right', render: (r) => h('span', { class: 'num' }, Number(r.inputPerMillion).toFixed(4)) },
  { title: '输出 $/1M', key: 'outputPerMillion', width: 95, align: 'right', render: (r) => h('span', { class: 'num' }, Number(r.outputPerMillion).toFixed(4)) },
  { title: '缓存 $/1M', key: 'cachedInputPerMillion', width: 90, align: 'right', render: (r) => {
      const configured = r.cachedInputPerMillion != null;
      const effective = configured ? Number(r.cachedInputPerMillion) : Number(r.inputPerMillion) * 0.1;
      return h('span', { class: 'num', style: configured ? '' : 'color:var(--text-muted)',
        title: configured ? '已配置的缓存命中价' : '未配置：按输入价的 10% 计费' }, effective.toFixed(4));
    } },
  { title: '币种', key: 'currency', width: 60 },
  { title: '更新时间', key: 'updatedAt', minWidth: 120, render: (r) => h('span', { class: 'num', style: 'font-size:11.5px' }, String(r.updatedAt || '').replace('T', ' ').slice(0, 19)) },
  { title: '操作', key: 'ops', width: 120, render: (r) => [
      h(NButton, { size: 'tiny', onClick: () => openEdit(r) }, { default: () => '编辑' }),
      h(NPopconfirm, { onPositiveClick: () => doDelete(r) }, {
        trigger: () => h(NButton, { size: 'tiny', style: 'margin-left:6px' }, { default: () => '删除' }),
        default: () => `确认删除模型价格 ${r.model}？`
      })
    ] }
]

const usageColumns = [
  { title: '模型', key: 'model', width: 170, ellipsis: { tooltip: true }, render: (r) => h('span', {}, [
      h('span', { class: 'mono' }, r.model),
      r.priced ? null : h('span', { style: 'color:var(--warn);font-size:11px;margin-left:6px' }, '未配置价格')
    ]) },
  { title: '调用', key: 'traces', width: 70, align: 'right', render: (r) => h('span', { class: 'num' }, String(r.traces)) },
  { title: '错误', key: 'errors', width: 60, align: 'right', render: (r) => h('span', { class: 'num', style: r.errors ? 'color:var(--error)' : '' }, String(r.errors)) },
  { title: 'Tokens（入/出）', key: 'tokens', width: 170, align: 'right', render: (r) => r.inputTokens == null ? '—'
      : h('span', { class: 'num', title: '输入 ' + fmtM(r.inputTokens) + ' / 输出 ' + fmtM(r.outputTokens)
            + (r.cachedInputTokens ? ' / 缓存命中 ' + fmtM(r.cachedInputTokens)
              + '（' + Math.round(r.cachedInputTokens / r.inputTokens * 100) + '%）' : '') }, [
          fmtM(r.inputTokens) + ' / ' + fmtM(r.outputTokens),
          r.cachedInputTokens
            ? h('span', { style: 'color:var(--warn);font-size:11px;margin-left:4px' }, '◈' + Math.round(r.cachedInputTokens / r.inputTokens * 100) + '%')
            : null
        ]) },
  { title: '成本', key: 'costUsd', width: 90, align: 'right', render: (r) => h('span', { class: 'num' }, '$' + Number(r.costUsd || 0).toFixed(4)) }
]
function fmtM(n) {
  n = Number(n || 0)
  return n >= 1_000_000 ? (n / 1_000_000).toFixed(2) + 'M' : n >= 1000 ? (n / 1000).toFixed(1) + 'K' : String(n)
}
function fmtTokens(m) {
  if (m.inputTokens == null) return '—'
  return fmtM(m.inputTokens) + ' / ' + fmtM(m.outputTokens) + ' tokens'
}
async function load() {
  loading.value = true
  try {
    records.value = await api.modelPrices() || []
    try {
      const u = await api.modelPriceUsage() || {}
      usageRows.value = u.rows || []
      unpriced.value = u.unpriced || []
    } catch (e) { usageRows.value = []; unpriced.value = [] }
  } finally { loading.value = false }
}
function openCreate() {
  editing.value = false
  Object.assign(form, { model: '', inputPerMillion: 0.15, outputPerMillion: 0.6, cachedInputPerMillion: null, currency: 'USD' })
  showEdit.value = true
}
function openCreateFor(model) {
  editing.value = false
  Object.assign(form, { model, inputPerMillion: 0.15, outputPerMillion: 0.6, cachedInputPerMillion: null, currency: 'USD' })
  showEdit.value = true
}
function openEdit(r) {
  editing.value = true
  editingId.value = r.id
  Object.assign(form, { model: r.model, inputPerMillion: Number(r.inputPerMillion), outputPerMillion: Number(r.outputPerMillion), cachedInputPerMillion: r.cachedInputPerMillion == null ? null : Number(r.cachedInputPerMillion), currency: r.currency })
  showEdit.value = true
}
async function doSave() {
  if (!form.model.trim()) { message.error('模型名必填'); return }
  saving.value = true
  try {
    if (editing.value) {
      await api.updateModelPrice(editingId.value, { inputPerMillion: form.inputPerMillion, outputPerMillion: form.outputPerMillion, cachedInputPerMillion: form.cachedInputPerMillion, currency: form.currency })
    } else {
      await api.createModelPrice({ ...form, model: form.model.trim() })
    }
    message.success('已保存')
    showEdit.value = false
    load()
  } catch (e) { message.error(e.message) }
  finally { saving.value = false }
}
async function doDelete(r) {
  try { await api.deleteModelPrice(r.id); message.success('已删除'); load() } catch (e) { message.error(e.message) }
}
onMounted(load)
</script>

<style scoped>
.mt12 { margin-top: 12px; }
.mono { font-family: "JetBrains Mono", ui-monospace, Menlo, monospace; }
.page-sub { font-size: 11.5px; color: var(--text-muted); font-weight: 400; }
.unpriced-list { display: flex; flex-direction: column; gap: 6px; max-height: 240px; overflow-y: auto; padding-right: 4px; }
.up-row { display: flex; align-items: center; gap: 14px; padding: 6px 10px; border: 1px solid var(--border); border-radius: 6px; font-size: 12.5px; }
.up-row .num { font-family: "JetBrains Mono", ui-monospace, Menlo, monospace; color: var(--text-secondary); }
</style>
