<template>
  <span class="time-text" :title="absolute">{{ relative }}</span>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'

const props = defineProps({
  value: { type: String, default: '' }
})

// 共享时钟：所有 TimeText 共用一个 30s 定时器，避免表格里每行一个 interval
const now = ref(Date.now())
let timer = null
let subscribers = 0
function acquire() {
  subscribers++
  if (!timer) timer = setInterval(() => { now.value = Date.now() }, 30000)
}
function release() {
  subscribers = Math.max(0, subscribers - 1)
  if (subscribers === 0 && timer) {
    clearInterval(timer)
    timer = null
  }
}
onMounted(acquire)
onUnmounted(release)

function parseTs(s) {
  if (!s) return null
  const t = new Date(String(s).length >= 23 ? String(s).slice(0, 23) : String(s)).getTime()
  return Number.isNaN(t) ? null : t
}
const relative = computed(() => {
  const t = parseTs(props.value)
  if (!t) return '—'
  const diff = now.value - t
  if (diff < 60000) return '刚刚'
  if (diff < 3600000) return Math.floor(diff / 60000) + ' 分钟前'
  if (diff < 86400000) return Math.floor(diff / 3600000) + ' 小时前'
  if (diff < 86400000 * 30) return Math.floor(diff / 86400000) + ' 天前'
  return absolute.value.slice(0, 10)
})
const absolute = computed(() => {
  const t = parseTs(props.value)
  if (!t) return '—'
  const d = new Date(t)
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
})
</script>

<style scoped>
.time-text { white-space: nowrap; }
</style>
