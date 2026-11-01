<template>
  <span class="copyable mono" :title="'点击复制 ' + full" @click.stop="copy">{{ short }}</span>
</template>

<script setup>
import { computed } from 'vue'
import { useMessage } from 'naive-ui'
import { copyToClipboard } from '@/common/clipboard'

const props = defineProps({
  value: { type: String, default: '' },
  length: { type: Number, default: 18 }
})
const message = useMessage()
const full = computed(() => props.value || '')
const short = computed(() => {
  const v = full.value
  return v.length > props.length ? v.slice(0, props.length) + '…' : v
})
async function copy() {
  const ok = await copyToClipboard(full.value)
  if (ok) message.success('已复制 ' + full.value)
  else message.warning('复制失败，请手动选择文本')
}
</script>

<style scoped>
.copyable { cursor: pointer; color: var(--primary); }
.copyable:hover { text-decoration: underline; }
</style>
