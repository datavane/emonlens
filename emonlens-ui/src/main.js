import { createApp } from 'vue'
import { createPinia } from 'pinia'
import naive from 'naive-ui'
import App from './App.vue'
import router from './router'
import LanButton from './components/button.vue'
import Modal from './components/Modal.vue'
import './assets/css/global.css'

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.use(naive)
app.component('LanButton', LanButton)
app.component('Modal', Modal)
app.mount('#app')
