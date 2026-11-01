<template>
  <n-tag size="small" :bordered="false" :type="tagType" class="score-chip">
    {{ name }} = {{ displayValue }}
  </n-tag>
</template>

<script setup>
import { computed } from 'vue'
import { NTag } from 'naive-ui'

const props = defineProps({
  name: { type: String, default: '' },
  value: { type: [String, Number], default: '' },
  label: { type: String, default: '' }
})
const tagType = computed(() => {
  const l = props.label || String(props.value)
  if (l === 'PASS' || l === 'RESOLVED' || l === 'TRUSTED') return 'success'
  if (l === 'FAIL' || l === 'OPEN' || l === 'ERROR') return 'error'
  if (l === 'EVAL_UNAVAILABLE' || l === 'ACKNOWLEDGED' || l === 'DEGRADED') return 'warning'
  return 'default'
})
const displayValue = computed(() => (props.value === '' ? props.label : props.value))
</script>

<style scoped>
.score-chip { font-family: "JetBrains Mono", ui-monospace, Menlo, monospace; }
</style>
