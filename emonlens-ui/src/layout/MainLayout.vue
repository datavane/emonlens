<template>
  <div class="app-shell">
    <aside class="app-sider" :class="{ narrow }">
      <div class="brand">
        <img class="brand-logo" :src="brandLogoUrl" alt="EmonLens" />
      </div>
      <n-menu
        class="app-menu"
        :value="activeKey"
        :options="menuOptions"
        :collapsed="narrow"
        :collapsed-width="64"
        :collapsed-icon-size="20"
        @update:value="goto"
      />
    </aside>
    <section class="app-main">
      <header class="app-header">
        <n-breadcrumb>
          <n-breadcrumb-item>{{ route.path.startsWith('/sys') ? '系统管理' : 'Trust 中心' }}</n-breadcrumb-item>
          <n-breadcrumb-item>{{ route.meta.title }}</n-breadcrumb-item>
        </n-breadcrumb>
        <div class="header-actions">
          <button class="cmdk-entry" type="button" title="搜索（⌘K）" @click="palette?.openPalette()">
            <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4"><circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/></svg>
            <span>搜索</span>
            <kbd>⌘K</kbd>
          </button>
          <span class="workspace-label">工作空间</span>
          <n-select class="workspace-select" :value="auth.workspace?.id" :options="wsOptions" size="small" @update:value="onWorkspaceChange" />
          <n-dropdown :options="userOptions" @select="onUserMenu">
            <button class="user-entry" type="button"><span class="avatar">{{ initials }}</span><span class="username">{{ auth.user?.name || auth.user?.username }}</span></button>
          </n-dropdown>
        </div>
      </header>
      <main class="content" :class="{ 'has-tabs': activeGroup }">
        <SectionTabs v-if="activeGroup" :group="activeGroup" />
        <div class="content-view"><router-view /></div>
      </main>
    </section>
    <CommandPalette ref="palette" />
  </div>
</template>

<script setup>
import { computed, h, onMounted, onUnmounted, ref } from 'vue'
import { MENU, MENU_SECTIONS, MENU_SECTION_LABELS, renderMenuIcon } from '@/common/menu'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/store/auth'
import { useUiStore } from '@/store/ui'
import CommandPalette from '@/components/CommandPalette.vue'
import SectionTabs from '@/components/SectionTabs.vue'
import brandLogoUrl from '@/assets/brand-logo.png'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const ui = useUiStore()

const palette = ref(null)
// 窄屏自动把侧栏收成图标栏（菜单组标题/文字由 collapsed 模式与 CSS 隐藏）
const narrow = ref(false)
let narrowMedia = null
function onNarrowChange(e) {
  narrow.value = e.matches
}
onMounted(() => {
  narrowMedia = window.matchMedia('(max-width: 900px)')
  narrow.value = narrowMedia.matches
  if (narrowMedia.addEventListener) {
    narrowMedia.addEventListener('change', onNarrowChange)
  } else {
    narrowMedia.addListener(onNarrowChange)
  }
})
onUnmounted(() => {
  if (!narrowMedia) return
  if (narrowMedia.removeEventListener) {
    narrowMedia.removeEventListener('change', onNarrowChange)
  } else {
    narrowMedia.removeListener(onNarrowChange)
  }
})
function onKeydown(e) {
  if ((e.metaKey || e.ctrlKey) && (e.key === 'k' || e.key === 'K')) {
    e.preventDefault()
    palette.value?.openPalette()
  }
}
onMounted(() => window.addEventListener('keydown', onKeydown))
onUnmounted(() => window.removeEventListener('keydown', onKeydown))


const visibleMenu = computed(() => MENU.filter((item) => auth.hasPermission(item.perm)))
const menuOptions = computed(() => MENU_SECTIONS.map((section) => ({
  type: 'group', label: MENU_SECTION_LABELS[section] || section, key: section,
  children: visibleMenu.value.filter((item) => item.section === section).map((item) => ({
    ...item,
    icon: renderMenuIcon(item.icon)
  }))
})).filter((group) => group.children.length))
// route.name 与菜单 key 不同名，按路径反查当前菜单项（含子路径，如 /traces/:id、/sessions/:id）
const activeGroup = computed(() => route.meta.group || null)
const activeKey = computed(() => {
  const match = MENU.find((item) => item.group
    ? route.meta.group === item.group
    : route.path === item.path || route.path.startsWith(item.path + '/'))
  return match?.key || route.name
})
const wsOptions = computed(() => auth.workspaces.map((workspace) => ({ label: workspace.name, value: workspace.id })))
const initials = computed(() => (auth.user?.username || '?').slice(0, 1).toUpperCase())
const userOptions = computed(() => [
  { label: ui.dark ? '浅色模式' : '深色模式', key: 'theme' },
  { label: '退出登录', key: 'logout' }
])

function goto(key) {
  const item = MENU.find((menu) => menu.key === key)
  if (item) router.push(item.path)
}
function onWorkspaceChange(workspaceId) {
  auth.switchWorkspace(workspaceId)
  location.reload()
}
function onUserMenu(key) {
  if (key === 'theme') {
    ui.toggle()
    return
  }
  if (key === 'logout') {
    auth.logout()
    router.push('/login')
  }
}
</script>

<style scoped>
.app-shell { display: flex; width: 100%; height: 100%; background: var(--bg-chrome); }
.app-sider { width: 220px; min-width: 220px; display: flex; flex-direction: column; background: var(--bg-chrome); border-right: 1px solid var(--border); z-index: 2; }
.brand { height: 52px; flex: 0 0 52px; display: flex; align-items: center; justify-content: center; padding: 0 14px; border-bottom: 1px solid var(--border); overflow: hidden; }
.brand-logo { height: 26px; width: auto; display: block; }
.app-menu { flex: 1; min-height: 0; padding: 6px 0; overflow: auto; }
.app-main { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.app-header { height: 52px; flex: 0 0 52px; display: flex; align-items: center; justify-content: space-between; padding: 0 20px; background: var(--bg-chrome); border-bottom: 1px solid var(--border); z-index: 1; }
.header-actions { display: flex; align-items: center; gap: 12px; }
.cmdk-entry { display: inline-flex; align-items: center; gap: 6px; height: 26px; padding: 0 8px; font-size: 12px; color: var(--text-muted); background: var(--bg-subtle); border: 1px solid var(--border); border-radius: 6px; cursor: pointer; }
.cmdk-entry:hover { color: var(--text-secondary); border-color: var(--border-strong); }
.cmdk-entry kbd { font-size: 10px; color: var(--text-muted); background: var(--bg-subtle); border: 1px solid var(--border); border-radius: 3px; padding: 0 3px; }
.workspace-label { color: var(--text-muted); font-size: 12px; }
.workspace-select { width: 180px; }
.user-entry { display: flex; align-items: center; gap: 8px; padding: 3px 6px; color: var(--text-primary); background: transparent; border: 0; border-radius: var(--radius-ctl); cursor: pointer; }
.user-entry:hover { background: var(--bg-subtle); }
.avatar { width: 24px; height: 24px; display: flex; align-items: center; justify-content: center; color: var(--primary); background: var(--primary-bg); border-radius: 50%; font-size: 11.5px; font-weight: 600; }
.username { max-width: 100px; font-size: 12.5px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.content { flex: 1; min-height: 0; overflow: auto; padding: 24px; background: var(--bg-content); }
.content-view { min-height: 0; }
.content.has-tabs { display: flex; flex-direction: column; overflow: hidden; }
.content.has-tabs > .section-tabs { height: 44px; flex: 0 0 auto; }
.content.has-tabs .content-view { flex: 1; height: auto; }
/* 窄屏：侧栏收成 64px 图标栏，隐藏次要文字 */
.app-sider.narrow { width: 64px; min-width: 64px; }
.app-sider.narrow .brand { padding: 0 8px; }
.app-sider.narrow .app-menu :deep(.n-menu-item-content-header),
.app-sider.narrow .app-menu :deep(.n-menu-item-group-title) { display: none; }
@media (max-width: 900px) {
  .app-header { padding: 0 12px; }
  .header-actions { gap: 8px; }
  .workspace-label, .username { display: none; }
  .cmdk-entry span, .cmdk-entry kbd { display: none; }
  .workspace-select { width: 130px; }
  .content { padding: 16px; }
}
</style>
