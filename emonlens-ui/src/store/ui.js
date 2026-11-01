import { defineStore } from 'pinia'

export const useUiStore = defineStore('ui', {
  state: () => ({ dark: localStorage.getItem('ui.theme') === 'dark' }),
  actions: {
    init() {
      document.documentElement.classList.toggle('dark', this.dark)
    },
    toggle() {
      this.dark = !this.dark
      localStorage.setItem('ui.theme', this.dark ? 'dark' : 'light')
      document.documentElement.classList.toggle('dark', this.dark)
    }
  }
})
