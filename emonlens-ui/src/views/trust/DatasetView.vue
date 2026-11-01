<template>
  <div class="base-page">
    <div class="head-stack">
      <div class="page-toolbar">
        <div class="toolbar-actions">
          <n-button size="small" @click="router.push('/traces')">从 Trace 收录</n-button>
          <n-button type="primary" size="small" @click="openCreate">＋ 新建数据集</n-button>
        </div>
      </div>
    </div>

    <n-card size="small" class="table-card">
      <n-data-table class="main-table" flex-height style="height: 100%" :columns="columns" :data="records" size="small" :bordered="false" :loading="loading"
        :row-props="(row) => ({ style: 'cursor:pointer', onClick: () => router.push('/datasets/' + row.id) })">
        <template #empty><EmptyState text="还没有数据集。点右上角「新建数据集」创建空白集，或到「调用链路」打开 Trace 详情点「收录数据集」，把线上问题沉淀成回归样本。进入数据集后点「运行实验」即可逐条评测。" /></template>
      </n-data-table>
    </n-card>

    <Modal v-model:show="showCreate" :width="480" title="新建数据集">
      <n-form label-placement="top">
        <n-form-item label="数据集 Key（唯一）" required>
          <n-input v-model:value="form.datasetKey" placeholder="如 support-qa-regression" />
        </n-form-item>
        <n-form-item label="描述">
          <n-input v-model:value="form.description" type="textarea" :rows="2" />
        </n-form-item>
      </n-form>
      <template #footer>
        <n-button size="small" :disabled="saving" @click="showCreate = false">取消</n-button>
        <n-button type="primary" size="small" :loading="saving" @click="doCreate">创建</n-button>
      </template>
    </Modal>
  </div>
</template>

<script setup>
import { h, onMounted, reactive, ref } from 'vue'
import { NButton, NPopconfirm } from 'naive-ui'
import { useRouter } from 'vue-router'
import { useMessage } from 'naive-ui'
import api from '@/api'
import EmptyState from '@/components/EmptyState.vue'

const router = useRouter()
const message = useMessage()
const records = ref([]), loading = ref(false)
const showCreate = ref(false), saving = ref(false)
const form = reactive({ datasetKey: '', description: '' })

const columns = [
  { title: '数据集', key: 'datasetKey', width: 180, render: (r) => h('span', { class: 'mono', style: 'color:var(--primary)' }, r.datasetKey) },
  { title: '描述', key: 'description', minWidth: 180, ellipsis: { tooltip: true }, render: (r) => r.description || '—' },
  { title: '样本数', key: 'itemCount', width: 70, align: 'right', render: (r) => h('span', { class: 'num' }, String(r.itemCount ?? 0)) },
  { title: '实验次数', key: 'runCount', width: 70, align: 'right', render: (r) => h('span', { class: 'num' }, String(r.runCount ?? 0)) },
  { title: '创建时间', key: 'createdAt', width: 140, render: (r) => h('span', { class: 'num', style: 'font-size:11.5px' }, String(r.createdAt || '').replace('T', ' ').slice(0, 19)) },
  { title: '', key: 'ops', width: 120, align: 'right', render: (r) => h('div', { style: 'display:flex;gap:6px;justify-content:flex-end', onClick: (e) => e.stopPropagation() }, [
      h(NButton, { size: 'tiny', ghost: true, type: 'primary',
        onClick: () => router.push('/datasets/' + r.id) }, { default: () => '进入' }),
      h(NPopconfirm, { onPositiveClick: () => doDelete(r) }, {
        trigger: () => h(NButton, { size: 'tiny', ghost: true, type: 'error' }, { default: () => '删除' }),
        default: () => `删除数据集「${r.datasetKey}」将同时删除其样本与历史实验记录，确认？`
      })
    ]) }
]

async function doDelete(row) {
  try {
    await api.deleteDataset(row.id)
    message.success('数据集已删除')
    load()
  } catch (e) { message.error(e.message) }
}

async function load() {
  loading.value = true
  try { records.value = await api.datasets() || [] }
  catch (e) { message.error(e.message || '数据集加载失败') }
  finally { loading.value = false }
}
function openCreate() {
  Object.assign(form, { datasetKey: '', description: '' })
  showCreate.value = true
}
async function doCreate() {
  if (!form.datasetKey.trim()) { message.error('请填写数据集 Key'); return }
  saving.value = true
  try {
    await api.createDataset({ datasetKey: form.datasetKey.trim(), description: form.description })
    message.success('数据集已创建')
    showCreate.value = false
    load()
  } catch (e) { message.error(e.message) }
  finally { saving.value = false }
}
onMounted(load)
</script>

<style scoped>
.mono { font-family: "JetBrains Mono", ui-monospace, Menlo, monospace; }
</style>
