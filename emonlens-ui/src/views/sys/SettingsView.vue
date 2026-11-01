<template>
  <div class="base-page settings-page">
    <div class="head-stack">
      <div class="page-head page-head--tabs">
        <div class="head-left">
          <n-tabs type="segment" size="small" class="tabs-segment-compact" :value="tab" @update:value="switchTab"
            style="--tabs-nav-width: 440px">
            <n-tab v-if="can('llm')" name="llm">LLM 端点</n-tab>
            <n-tab v-if="can('notify')" name="notify">通知</n-tab>
            <n-tab v-if="can('audit')" name="audit">审计日志</n-tab>
            <n-tab v-if="can('tokens')" name="tokens">API Token</n-tab>
          </n-tabs>
        </div>
      </div>
    </div>

    <div class="page-body">
      <LlmSettingPanel v-if="tab === 'llm' && can('llm')" />
      <NotifyPanel v-else-if="tab === 'notify' && can('notify')" />
      <AuditPanel v-else-if="tab === 'audit' && can('audit')" />
      <ApiTokenPanel v-else-if="tab === 'tokens' && can('tokens')" />
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/store/auth'
import NotifyPanel from './NotifyPanel.vue'
import AuditPanel from './AuditPanel.vue'
import ApiTokenPanel from './ApiTokenPanel.vue'
import LlmSettingPanel from './LlmSettingPanel.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
// 每个页签对应独立权限：系统设置页本身只对其中任意一项授权开放
const TAB_PERMS = {
  llm: 'workspace:sys:setting:manage',
  notify: 'workspace:sys:notify:manage',
  audit: 'workspace:sys:audit:query',
  tokens: 'workspace:sys:openapi:manage'
}
const can = (name) => auth.hasPermission(TAB_PERMS[name])
const allowedTabs = computed(() => Object.keys(TAB_PERMS).filter((name) => can(name)))
const tab = computed(() => {
  const q = String(route.query.tab || '')
  return allowedTabs.value.includes(q) ? q : (allowedTabs.value[0] || 'llm')
})
function switchTab(v) {
  router.replace({ query: { ...route.query, tab: v } })
}
</script>

<style scoped>
.settings-page { overflow: hidden; }
</style>
