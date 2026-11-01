<template>
  <div class="stat-card">
    <div class="s-title">{{ title }}</div>
    <div class="s-row">
      <span class="s-value" :style="valueStyle">{{ displayValue }}</span>
      <span v-if="delta != null" class="s-delta" :class="deltaClass">
        {{ delta >= 0 ? '▲' : '▼' }} {{ Math.abs(delta).toFixed(1) }}%
      </span>
    </div>
    <div v-if="hint" class="s-hint">{{ hint }}</div>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  title: { type: String, required: true },
  value: { type: [String, Number], default: null },
  delta: { type: Number, default: null }, // 相对上一窗口的百分比变化
  hint: { type: String, default: '' },
  valueStyle: { type: [String, Object], default: '' },
  /** 该指标“越低越好”（错误率/延迟/成本）：上升应标红 */
  invertDelta: { type: Boolean, default: false }
})
const displayValue = computed(() => (props.value == null ? '—' : props.value))
const deltaClass = computed(() => {
  if (props.delta == null) return ''
  const lowerIsBetter = props.invertDelta || props.title.toLowerCase().includes('error')
  const good = lowerIsBetter ? props.delta < 0 : props.delta >= 0
  return good ? 'good' : 'bad'
})
</script>

<style scoped>
.stat-card {
  background: var(--bg-surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
  padding: 14px 16px;
}
.s-title { font-size: 12px; color: var(--text-muted); }
.s-row { display: flex; align-items: baseline; gap: 8px; margin-top: 4px; }
.s-value { font-size: 21px; font-weight: 600; font-family: "JetBrains Mono", ui-monospace, Menlo, monospace; font-variant-numeric: tabular-nums; }
.s-delta { font-size: 11.5px; font-weight: 500; }
.s-delta.good { color: var(--success); }
.s-delta.bad { color: var(--error); }
.s-hint { margin-top: 2px; font-size: 11.5px; color: var(--text-muted); }
</style>
