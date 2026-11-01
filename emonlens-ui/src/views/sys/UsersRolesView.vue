<template>
  <div class="base-page">
    <div class="head-stack">
      <div class="page-head page-head--tabs">
        <div class="head-left">
          <n-tabs type="segment" size="small" class="tabs-segment-compact" :value="tab" @update:value="switchTab"
            style="--tabs-nav-width: 220px">
            <n-tab v-if="canUsers" name="users">成员</n-tab>
            <n-tab v-if="canRoles" name="roles">角色权限</n-tab>
          </n-tabs>
        </div>
      </div>
    </div>

    <div class="page-body">
      <UserPanel v-if="tab === 'users' && canUsers" />
      <RolePanel v-else-if="canRoles" />
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/store/auth'
import UserPanel from './UserPanel.vue'
import RolePanel from './RolePanel.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const canUsers = computed(() => auth.hasPermission('workspace:sys:user:manage'))
const canRoles = computed(() => auth.hasPermission('workspace:sys:role:manage'))
const tab = computed(() => {
  const q = route.query.tab === 'roles' ? 'roles' : 'users'
  if (q === 'roles' && canRoles.value) return 'roles'
  if (q === 'users' && canUsers.value) return 'users'
  return canUsers.value ? 'users' : 'roles'
})
function switchTab(v) {
  router.replace({ query: { ...route.query, tab: v } })
}
</script>
