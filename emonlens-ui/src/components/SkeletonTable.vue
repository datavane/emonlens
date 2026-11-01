<template>
  <div class="skeleton-table">
    <div v-for="i in rows" :key="i" class="sk-row">
      <div class="sk-bar" :style="{ width: (18 + ((i * 37) % 60)) + '%' }"></div>
      <div class="sk-bar" :style="{ width: (12 + ((i * 53) % 30)) + '%' }"></div>
      <div class="sk-bar" :style="{ width: (10 + ((i * 71) % 20)) + '%' }"></div>
    </div>
  </div>
</template>

<script setup>
defineProps({
  rows: { type: Number, default: 8 }
})
</script>

<style scoped>
.skeleton-table { display: flex; flex-direction: column; gap: 14px; padding: 16px 4px; }
.sk-row { display: flex; gap: 16px; }
.sk-bar { height: 12px; border-radius: 4px; background: linear-gradient(90deg, var(--border) 25%, var(--bg-subtle) 50%, var(--border) 75%); background-size: 200% 100%; animation: sk 1.2s infinite; }
@keyframes sk { 0% { background-position: 200% 0; } 100% { background-position: -200% 0; } }
</style>
