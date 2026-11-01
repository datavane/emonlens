<template>
  <n-popover trigger="click" placement="bottom-end" :show-arrow="false">
    <template #trigger>
      <button class="col-btn" type="button">列配置</button>
    </template>
    <div class="col-panel">
      <label v-for="c in columns" :key="c.key" class="col-row">
        <n-checkbox
          :checked="visible.includes(c.key)"
          :disabled="c.key === lockedKey"
          @update:checked="() => toggle(c.key)"
        />
        <span class="col-label">{{ c.title }}</span>
      </label>
    </div>
  </n-popover>
</template>

<script setup>
import { computed } from 'vue'
import { NCheckbox, NPopover } from 'naive-ui'

const props = defineProps({
  columns: { type: Array, required: true }, // [{ key, title }]
  modelValue: { type: Array, required: true }, // 可见 key 列表
  lockedKey: { type: String, default: '' } // 不允许隐藏的列
})
const emit = defineEmits(['update:modelValue'])

const visible = computed(() => props.modelValue)
function toggle(key) {
  if (key === props.lockedKey) return
  const next = visible.value.includes(key)
    ? visible.value.filter((k) => k !== key)
    : [...visible.value, key]
  emit('update:modelValue', next)
}
</script>

<style scoped>
.col-btn {
  height: 26px; padding: 0 10px;
  font-size: 12px; color: var(--text-secondary);
  background: var(--bg-surface);
  border: 1px solid var(--border-strong);
  border-radius: 6px; cursor: pointer;
}
.col-btn:hover { border-color: var(--primary); color: var(--primary); }
.col-panel { width: 180px; padding: 4px; }
.col-row { display: flex; align-items: center; gap: 8px; padding: 4px; cursor: pointer; border-radius: 4px; }
.col-row:hover { background: var(--bg-subtle); }
.col-label { font-size: 12.5px; }
</style>
