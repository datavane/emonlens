<template>
  <div class="filter-bar">
    <!-- 已激活条件 chips -->
    <n-popover v-for="f in activeFields" :key="f.key" :show="openPanelKey === f.key"
      trigger="manual" placement="bottom-start" :show-arrow="false" style="max-height: 320px; overflow: auto"
      @clickoutside="closePanel(f.key)" @update:show="(v) => { if (!v) closePanel(f.key) }">
      <template #trigger>
        <button class="chip" type="button" @click.stop="openPanelKey = openPanelKey === f.key ? null : f.key">
          <span class="chip-label">{{ f.label }}:</span>
          <span class="chip-value">{{ displayValue(f) }}</span>
          <span class="chip-x" @click.stop="clearField(f)">×</span>
        </button>
      </template>
      <div class="filter-panel">
        <template v-if="f.type === 'select'">
          <n-input v-model:value="panelSearch[f.key]" size="small" placeholder="搜索值…" class="panel-search" />
          <div class="option-list">
            <div
              v-for="opt in filteredOptions(f)"
              :key="opt.value"
              class="option-row"
              :class="{ selected: isSelected(f, opt.value) }"
              @click="toggleOption(f, opt.value)"
            >
              <n-checkbox
                :checked="isSelected(f, opt.value)"
                @click.stop
                @update:checked="() => toggleOption(f, opt.value)"
              />
              <span class="option-label">{{ opt.label }}</span>
            </div>
            <div v-if="!filteredOptions(f).length" class="option-empty">无匹配项</div>
          </div>
        </template>
        <template v-else>
          <n-input
            :value="String(modelValue[f.key] ?? '')"
            size="small" clearable
            :placeholder="f.placeholder || '输入后回车应用'"
            @update:value="(v) => setInput(f, v)"
            @keyup.enter="$emit('apply')"
          />
        </template>
      </div>
    </n-popover>

    <!-- 添加筛选 -->
    <n-dropdown trigger="click" :options="addOptions" @select="addField">
      <button class="chip chip-add" type="button">＋ 筛选</button>
    </n-dropdown>

    <slot name="right"></slot>
  </div>
</template>

<script setup>
import { computed, reactive, ref, toValue } from 'vue'
import { NCheckbox, NDropdown, NInput, NPopover } from 'naive-ui'

const props = defineProps({
  // fields: [{ key, label, type: 'select'|'input', options?, multiple?, placeholder? }]
  //   select 字段默认多选（值=数组）；multiple: false 为单选（值=标量，再次点击取消）
  fields: { type: Array, required: true },
  modelValue: { type: Object, required: true }
})
const emit = defineEmits(['update:modelValue', 'apply'])

const panelSearch = reactive({})
const openPanelKey = ref(null)

function openPanel(f) {
  openPanelKey.value = f.key
}
function closePanel(key) {
  if (openPanelKey.value === key) openPanelKey.value = null
}

const activeFields = computed(() => props.fields.filter((f) => {
  if (openPanelKey.value === f.key) return true      // 刚添加未选值的面板保持打开
  const v = props.modelValue[f.key]
  return Array.isArray(v) ? v.length > 0 : v !== undefined && v !== null && v !== ''
}))
const addOptions = computed(() => props.fields
  .filter((f) => !activeFields.value.includes(f))
  .map((f) => ({ label: f.label, key: f.key })))

function displayValue(f) {
  const v = props.modelValue[f.key]
  if (Array.isArray(v)) return v.length > 2 ? v.slice(0, 2).join(', ') + ` +${v.length - 2}` : v.join(', ')
  return String(v)
}
function filteredOptions(f) {
  const kw = (panelSearch[f.key] || '').toLowerCase()
  const opts = toValue(f.options) || []          // toValue 解包 ref/computed
  return kw ? opts.filter((o) => String(o.label).toLowerCase().includes(kw)) : opts
}
function isSelected(f, value) {
  const v = props.modelValue[f.key]
  if (f.multiple === false) return v === value   // 单选：标量等值
  return Array.isArray(v) && v.includes(value)   // 多选：数组包含
}
function toggleOption(f, value) {
  if (f.multiple === false) {
    // 单选：点已选中的 = 清除；点其它 = 替换
    const next = isSelected(f, value) ? '' : value
    emit('update:modelValue', { ...props.modelValue, [f.key]: next })
  } else {
    const cur = Array.isArray(props.modelValue[f.key]) ? [...props.modelValue[f.key]] : []
    const idx = cur.indexOf(value)
    if (idx >= 0) cur.splice(idx, 1)
    else cur.push(value)
    emit('update:modelValue', { ...props.modelValue, [f.key]: cur })
  }
  emit('apply')
}
function setInput(f, v) {
  emit('update:modelValue', { ...props.modelValue, [f.key]: v })
}
function clearField(f) {
  const empty = f.multiple === false ? '' : []
  emit('update:modelValue', { ...props.modelValue, [f.key]: empty })
  emit('apply')
}
function addField(key) {
  const f = props.fields.find((x) => x.key === key)
  if (!f) return
  const empty = f.type === 'select' ? (f.multiple === false ? '' : []) : ''
  emit('update:modelValue', { ...props.modelValue, [f.key]: empty })
  // activeFields 由 modelValue 推导，需等父组件更新后再打开面板
  requestAnimationFrame(() => { openPanelKey.value = key })
}
</script>

<style scoped>
.filter-bar { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }
.chip {
  display: inline-flex; align-items: center; gap: 4px;
  height: 26px; padding: 0 8px;
  font-size: 12px;
  color: var(--text-secondary);
  background: var(--bg-surface);
  border: 1px solid var(--border-strong);
  border-radius: 6px;
  cursor: pointer;
}
.chip:hover { border-color: var(--primary); color: var(--primary); }
.chip-label { color: var(--text-muted); }
.chip-value { max-width: 220px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.chip-x { margin-left: 2px; color: var(--text-muted); font-size: 13px; }
.chip-x:hover { color: var(--error); }
.chip-add { border-style: dashed; color: var(--text-muted); }
.filter-panel { width: 240px; padding: 4px; }
.panel-search { margin-bottom: 6px; }
.option-list { max-height: 240px; overflow: auto; }
.option-row {
  display: flex; align-items: center; gap: 8px;
  padding: 5px 6px; cursor: pointer; border-radius: 4px;
  user-select: none;
}
.option-row:hover { background: var(--bg-subtle); }
.option-row.selected .option-label { color: var(--primary); font-weight: 500; }
.option-label { font-size: 12.5px; color: var(--text-primary); }
.option-empty { padding: 8px; font-size: 12px; color: var(--text-muted); text-align: center; }
</style>
