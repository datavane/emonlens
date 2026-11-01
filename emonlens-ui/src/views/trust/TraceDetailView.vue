<template>
  <div class="base-page detail-page">
    <div class="page-head">
      <div class="head-left">
        <n-button size="small" quaternary @click="router.push('/traces')">← 返回列表</n-button>
      </div>
      <div class="head-right">
        <n-button size="small" @click="refreshKey++">刷新</n-button>
      </div>
    </div>

    <n-card size="small" class="mt12 detail-card" :bordered="true">
      <TraceDetailPanel :key="traceId + '-' + refreshKey" :trace-id="traceId" mode="page" />
    </n-card>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NButton } from 'naive-ui'
import TraceDetailPanel from '@/components/TraceDetailPanel.vue'

const route = useRoute()
const router = useRouter()
const traceId = computed(() => String(route.params.traceId || ''))
const refreshKey = ref(0)
</script>

<style scoped>
/* 整页详情：占满内容区高度，内容只在卡片内部滚动，页头固定可见。
   高度 = 视口 - 52px 顶栏 - .content 上下 padding（桌面 24px、窄屏 16px）。 */
.detail-page {
  height: calc(100vh - 100px);
  min-height: 480px;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
.mt12 { margin-top: 12px; }
.detail-card { flex: 1; min-height: 0; display: flex; flex-direction: column; }
/* naive-ui 2.x 实际类名是 .n-card-content；同时兼容 .n-card__content */
.detail-card :deep(.n-card-content),
.detail-card :deep(.n-card__content) {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
@media (max-width: 900px) {
  .detail-page { height: calc(100vh - 84px); }
}
</style>
