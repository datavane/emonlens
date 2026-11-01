<template>
  <div class="login-page">
    <div class="login-panel">
      <div class="login-brand">
        <img class="login-logo" :src="brandLogoUrl" alt="EmonLens" />
        <div class="login-sub">Agent Trust 可信观测平台</div>
      </div>
      <div class="login-title">登录到工作空间</div>
      <n-form :model="form" label-placement="top" size="large" :show-feedback="false">
        <n-form-item label="用户名">
          <n-input v-model:value="form.username" placeholder="用户名" @keyup.enter="submit" />
        </n-form-item>
        <n-form-item label="密码" class="mt8">
          <n-input v-model:value="form.password" type="password" show-password-on="click" placeholder="密码" @keyup.enter="submit" />
        </n-form-item>
        <lan-button class="login-button" type="primary" block :loading="loading" @click="submit">登 录</lan-button>
      </n-form>
      <div class="login-hint">登录即代表同意平台使用条款</div>
    </div>
    <div class="login-footer">EmonLens · Agent Trust Observability</div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useMessage } from 'naive-ui'
import { useAuthStore } from '@/store/auth'
import brandLogoUrl from '@/assets/brand-logo.png'

const router = useRouter()
const route = useRoute()
const message = useMessage()
const auth = useAuthStore()

const form = reactive({ username: '', password: '' })
const loading = ref(false)

async function submit() {
  if (!form.username || !form.password) {
    message.warning('请输入用户名和密码')
    return
  }
  loading.value = true
  try {
    await auth.login(form.username, form.password)
    router.push(route.query.redirect || '/')
  } catch (e) {
    message.error(e.message || '登录失败')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  height: 100vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  background:
    radial-gradient(1200px 500px at 70% -10%, rgba(59, 119, 241, 0.10), transparent 60%),
    radial-gradient(900px 400px at 10% 110%, rgba(59, 119, 241, 0.06), transparent 60%),
    var(--page-bg);
}
.login-panel {
  width: 400px;
  padding: 36px 36px 28px;
  background: var(--bg-surface);
  border: 1px solid var(--line-color);
  border-radius: 10px;
  box-shadow: 0 8px 30px rgba(16, 24, 40, 0.06), 0 1px 2px rgba(16, 24, 40, 0.04);
}
.login-brand { display: flex; flex-direction: column; align-items: center; gap: 8px; padding-bottom: 22px; border-bottom: 1px solid var(--line-color); }
.login-logo { height: 32px; width: auto; display: block; }
.login-sub { font-size: 12px; color: var(--text-color-tertiary); text-align: center; }
.login-title { margin: 22px 0 14px; font-size: 15px; font-weight: 600; color: var(--text-color); }
.login-button { margin-top: 20px; height: 38px; font-size: 13.5px; }
.login-hint { margin-top: 16px; font-size: 11.5px; color: var(--text-muted); text-align: center; }
.login-footer { position: fixed; bottom: 20px; font-size: 11.5px; color: var(--text-muted); letter-spacing: 0.04em; }
.mt8 { margin-top: 8px; }
</style>
