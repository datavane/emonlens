<template>
  <div class="empty-state">
    <div class="es-icon">◍</div>
    <div class="es-text">{{ text }}</div>
    <div v-if="actionText" class="es-action">
      <n-button size="small" @click="$emit('action')">{{ actionText }}</n-button>
    </div>
  </div>
</template>

<script setup>
import { NButton } from 'naive-ui'

defineProps({
  text: { type: String, default: '暂无数据' },
  actionText: { type: String, default: '' }
})
defineEmits(['action'])
</script>

<style scoped>
.empty-state { display: flex; flex-direction: column; align-items: center; justify-content: center; padding: 48px 0; gap: 8px; }
.es-icon { font-size: 26px; color: var(--border-strong); }
.es-text { font-size: 12.5px; color: var(--text-muted); }
</style>
