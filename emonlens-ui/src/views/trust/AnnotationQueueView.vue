<template>
  <div class="base-page">
    <div class="head-stack">
      <div class="page-toolbar">
        <div class="toolbar-actions">
          <n-button type="primary" size="small" @click="openCreate">＋ 新建队列</n-button>
        </div>
      </div>
    </div>

    <n-card size="small" class="table-card">
      <n-data-table class="main-table" flex-height style="height: 100%" :columns="columns" :data="records" size="small" :bordered="false" :loading="loading"
        :row-props="(row) => ({ style: 'cursor:pointer', onClick: () => router.push('/annotation-queues/' + row.id) })" >
        <template #empty><EmptyState text="还没有标注队列：点右上角「＋ 新建队列」，把待复核的 Trace 抽样入队，团队即可逐条领取打分（人工评分会计入信任分与评估统计）" />
</template>
      </n-data-table>
    </n-card>

    <Modal v-model:show="showCreate" :width="480" title="新建注解队列">
      <n-form label-placement="top">
        <n-form-item label="队列名称" required>
          <n-input v-model:value="form.name" placeholder="如 客服助手周度抽检" />
        </n-form-item>
        <n-form-item label="标注说明">
          <n-input v-model:value="form.description" type="textarea" :rows="3" placeholder="给标注员的复核标准…" />
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
import EmptyState from '@/components/EmptyState.vue'
import { h, onMounted, reactive, ref } from 'vue'
import { NButton, NPopconfirm, NTag } from 'naive-ui'
import { useRouter } from 'vue-router'
import { useMessage } from 'naive-ui'
import api from '@/api'

const router = useRouter()
const message = useMessage()
const records = ref([]), loading = ref(false)
const showCreate = ref(false), saving = ref(false)
const form = reactive({ name: '', description: '' })

const columns = [
  { title: '队列', key: 'name', width: 180, render: (r) => h('span', { class: 'mono', style: 'color:var(--primary)' }, r.name) },
  { title: '说明', key: 'description', minWidth: 180, ellipsis: { tooltip: true }, render: (r) => r.description || '—' },
  { title: '进度', key: 'progress', width: 130, render: (r) => {
      const total = r.total ?? 0
      const done = r.completed ?? 0
      const pct = total ? Math.round(done * 100 / total) : 0
      return h('div', { style: 'display:flex;align-items:center;gap:8px' }, [
        h('div', { style: 'flex:1;height:6px;background:var(--bg-subtle);border-radius:3px;overflow:hidden' }, [
          h('div', { style: `height:100%;width:${pct}%;background:var(--success);border-radius:3px` })
        ]),
        h('span', { class: 'num', style: 'font-size:11.5px;color:var(--text-muted);flex:0 0 42px' }, done + '/' + total)
      ])
    } },
  { title: '待标注', key: 'pending', width: 65, align: 'right', render: (r) => h('span', { class: 'num', style: r.pending ? 'color:var(--warn);font-weight:600' : '' }, String(r.pending ?? 0)) },
  { title: '创建人', key: 'createdBy', width: 75 },
  { title: '操作', key: 'ops', width: 115, render: (r) => [
      h(NButton, { size: 'tiny', type: 'primary', ghost: true, onClick: (e) => { e.stopPropagation(); router.push('/annotation-queues/' + r.id) } }, { default: () => '开始标注' }),
      h(NPopconfirm, { onPositiveClick: () => doDelete(r) }, {
        trigger: () => h(NButton, { size: 'tiny', style: 'margin-left:4px', onClick: (e) => e.stopPropagation() }, { default: () => '删除' }),
        default: () => `确认删除队列 ${r.name}？其样本记录将一并删除。`
      })
    ] }
]

async function load() {
  loading.value = true
  try { records.value = await api.annotationQueues() || [] } finally { loading.value = false }
}
function openCreate() {
  Object.assign(form, { name: '', description: '' })
  showCreate.value = true
}
async function doCreate() {
  if (!form.name.trim()) { message.error('请填写队列名称'); return }
  saving.value = true
  try {
    await api.createAnnotationQueue({ name: form.name.trim(), description: form.description })
    message.success('队列已创建')
    showCreate.value = false
    load()
  } catch (e) { message.error(e.message) }
  finally { saving.value = false }
}
async function doDelete(r) {
  try { await api.deleteAnnotationQueue(r.id); load() } catch (e) { message.error(e.message) }
}
onMounted(load)
</script>

<style scoped>
.mono { font-family: "JetBrains Mono", ui-monospace, Menlo, monospace; }
</style>
