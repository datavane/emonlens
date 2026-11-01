<template>
  <n-config-provider :theme="ui.dark ? darkTheme : null" :theme-overrides="ui.dark ? naiveThemeDark : naiveTheme" :locale="zhCN" :date-locale="dateZhCN" style="height: 100%">
    <n-message-provider>
      <n-dialog-provider>
        <router-view />
      </n-dialog-provider>
    </n-message-provider>
  </n-config-provider>
</template>

<script setup>
import { onMounted } from 'vue'
import { darkTheme, zhCN, dateZhCN } from 'naive-ui'
import { naiveTheme, naiveThemeDark } from '@/common/theme'
import { useUiStore } from '@/store/ui'

const ui = useUiStore()
onMounted(() => ui.init())
</script>
