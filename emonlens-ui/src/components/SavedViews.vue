<template>
  <n-dropdown trigger="click" :options="viewOptions" @select="onSelect">
    <button class="sv-btn" type="button" :title="currentName ? '当前视图：' + currentName : '保存 / 切换筛选视图'">
      视图 <template v-if="currentName">· {{ currentName }} </template>▾
    </button>
  </n-dropdown>

  <n-modal v-model:show="showDelete" preset="card" title="删除视图" style="width: 360px">
    <div class="page-sub">确认删除视图「{{ pendingDelete }}」？删除后需要重新保存才能再用。</div>
    <template #footer>
      <div class="sv-footer">
        <n-button size="small" @click="showDelete = false">取消</n-button>
        <n-button size="small" type="error" @click="doDelete">删除</n-button>
      </div>
    </template>
  </n-modal>

  <n-modal v-model:show="showSave" preset="card" title="保存当前视图" style="width: 360px">
    <n-input v-model:value="name" placeholder="视图名称，如：高成本 trace" maxlength="40" show-count
      @keyup.enter="doSave" />
    <template #footer>
      <div class="sv-footer">
        <n-button size="small" @click="showSave = false">取消</n-button>
        <n-button size="small" type="primary" :disabled="!name.trim()" @click="doSave">保存</n-button>
      </div>
    </template>
  </n-modal>
</template>

<script setup>
import { computed, ref } from 'vue'
import { NDropdown, useMessage, NModal, NInput, NButton } from 'naive-ui'

const props = defineProps({
  storageKey: { type: String, required: true },
  current: { type: Object, required: true } // 当前筛选条件对象
})
const emit = defineEmits(['apply'])
const message = useMessage()

const views = ref(load())
const currentName = ref('')
const showSave = ref(false)
const name = ref('')
const showDelete = ref(false)
const pendingDelete = ref('')

function load() {
  try { return JSON.parse(localStorage.getItem('ui.savedViews.' + props.storageKey) || '[]') } catch (e) { return [] }
}
function persist() {
  localStorage.setItem('ui.savedViews.' + props.storageKey, JSON.stringify(views.value))
}
const viewOptions = computed(() => [
  { label: '＋ 保存当前视图', key: '__save' },
  ...(views.value.length ? [{ type: 'divider', key: 'd1' }] : []),
  ...views.value.flatMap((v) => [
    { label: v.name, key: v.name },
    { label: '删除「' + v.name + '」', key: '__del:' + v.name }
  ]),
  ...(views.value.length ? [{ type: 'divider', key: 'd2' }] : []),
  ...(views.value.length ? [{ label: '清空全部', key: '__clear' }] : [])
])

function onSelect(key) {
  if (key === '__save') {
    name.value = ''
    showSave.value = true
    return
  }
  if (key === '__clear') {
    views.value = []
    currentName.value = ''
    persist()
    message.success('已清空全部视图')
    return
  }
  if (key.startsWith('__del:')) {
    // 与全站破坏性操作一致：先确认再删（下拉项无法内嵌 popconfirm，用确认弹窗）
    pendingDelete.value = key.slice(6)
    showDelete.value = true
    return
  }
  const view = views.value.find((v) => v.name === key)
  if (view) {
    currentName.value = view.name
    emit('apply', JSON.parse(JSON.stringify(view.filters)))
  }
}
function doDelete() {
  const target = pendingDelete.value
  if (!target) return
  views.value = views.value.filter((v) => v.name !== target)
  if (currentName.value === target) currentName.value = ''
  persist()
  showDelete.value = false
  pendingDelete.value = ''
  message.success('已删除「' + target + '」')
}
function doSave() {
  const n = name.value.trim()
  if (!n) return
  views.value = [...views.value.filter((v) => v.name !== n), { name: n, filters: JSON.parse(JSON.stringify(props.current)) }]
  currentName.value = n
  persist()
  showSave.value = false
  message.success('视图已保存')
}
</script>

<style scoped>
.sv-btn {
  height: 26px; padding: 0 10px;
  font-size: 12px; color: var(--text-secondary);
  background: var(--bg-surface);
  border: 1px solid var(--border-strong);
  border-radius: 6px; cursor: pointer;
}
.sv-btn:hover { border-color: var(--primary); color: var(--primary); }
.sv-footer { text-align: right; }
</style>
