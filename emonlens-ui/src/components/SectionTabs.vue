<template>
  <div v-if="tabs.length > 1" class="section-tabs">
    <n-tabs type="segment" size="small" class="tabs-segment-compact" :style="navStyle" :value="active" @update:value="go">
      <n-tab v-for="tab in tabs" :key="tab.path" :name="tab.path">{{ tab.label }}</n-tab>
    </n-tabs>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { TAB_GROUPS } from '@/common/menu'
import { useAuthStore } from '@/store/auth'

const props = defineProps({ group: { type: String, required: true } })
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const tabs = computed(() => (TAB_GROUPS[props.group] || []).filter((t) => auth.hasPermission(t.perm)))
const navStyle = computed(() => ({ '--tabs-nav-width': `${Math.max(220, tabs.value.length * 110)}px` }))
const active = computed(() => {
  const match = tabs.value.find((t) => route.path === t.path || route.path.startsWith(t.path + '/'))
  return match?.path || tabs.value[0]?.path
})
function go(path) {
  if (path && path !== route.path) router.push(path)
}
</script>

<style scoped>
.section-tabs { min-width: 0; }
</style>
