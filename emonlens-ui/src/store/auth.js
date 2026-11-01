import { defineStore } from 'pinia'
import api from '@/api'

function parseStored(value, fallback) {
  try {
    const parsed = JSON.parse(value)
    return parsed == null ? fallback : parsed
  } catch (e) {
    return fallback
  }
}

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem('emonlens.token') || '',
    user: parseStored(localStorage.getItem('emonlens.user'), null),
    workspace: parseStored(localStorage.getItem('emonlens.workspace'), null),
    workspaces: parseStored(localStorage.getItem('emonlens.workspaces'), []),
    permissions: parseStored(localStorage.getItem('emonlens.permissions'), [])
  }),
  getters: {
    isLoggedIn: (s) => !!s.token,
    hasPermission: (s) => (permission) =>
      s.user?.superUser || !permission || s.permissions.includes(permission)
  },
  actions: {
    async login(username, password) {
      const data = await api.login(username, password)
      this.token = data.token
      this.user = data.user
      this.workspace = data.workspace
      this.permissions = data.permissions || []
      this.persist()
      try {
        this.workspaces = await api.myWorkspaces()
      } catch (e) {
        // 工作区列表是附加信息：失败不应让已成功的登录整体失败
        this.workspaces = this.workspace ? [this.workspace] : []
      }
      localStorage.setItem('emonlens.workspaces', JSON.stringify(this.workspaces))
    },
    async restore() {
      if (!this.token) return false
      try {
        const me = await api.me()
        this.user = me.user
        this.workspace = me.workspace
        this.permissions = me.permissions || []
        this.persist()
        this.workspaces = await api.myWorkspaces()
        localStorage.setItem('emonlens.workspaces', JSON.stringify(this.workspaces))
        return true
      } catch (e) {
        // 401 时拦截器已清掉 localStorage token；网络/5xx 不应销毁会话
        if (!localStorage.getItem('emonlens.token')) {
          this.logout()
          return false
        }
        return true
      }
    },
    switchWorkspace(workspaceId) {
      this.workspace = this.workspaces.find((w) => w.id === workspaceId) || this.workspace
      localStorage.setItem('emonlens.workspace', JSON.stringify(this.workspace))
    },
    persist() {
      localStorage.setItem('emonlens.token', this.token)
      localStorage.setItem('emonlens.user', JSON.stringify(this.user))
      localStorage.setItem('emonlens.workspace', JSON.stringify(this.workspace))
      localStorage.setItem('emonlens.permissions', JSON.stringify(this.permissions))
    },
    logout() {
      this.token = ''
      this.user = null
      this.workspace = null
      this.permissions = []
      localStorage.removeItem('emonlens.token')
      localStorage.removeItem('emonlens.user')
      localStorage.removeItem('emonlens.workspace')
      localStorage.removeItem('emonlens.permissions')
      localStorage.removeItem('emonlens.workspaces')
    }
  }
})
