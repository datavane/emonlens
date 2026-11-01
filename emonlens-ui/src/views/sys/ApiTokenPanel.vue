<template>
  <div class="panel-root">
    <n-card size="small" class="table-card">
      <template #header>
        <span class="panel-card-title">OpenAPI Token（用于对外集成：回写评分、写入监控等）</span>
      </template>
      <template #header-extra>
        <n-button type="primary" size="small" @click="openIssue">签发 Token</n-button>
      </template>
      <n-data-table class="main-table" flex-height :columns="columns" :data="tokens" size="small" :bordered="false" :loading="loading" />
      <div class="page-sub mt8">
        调用方式：请求头 <code class="mono">X-Api-Token: &lt;token&gt;</code> · 明文仅在签发时返回一次，请立即保存 ·
        可选权限范围：<code class="mono">score:read</code>（读信任分/准入结论）、<code class="mono">monitors:write</code>（写监控规则）、
        <code class="mono">eval:run</code>（提交数据集评测）、<code class="mono">eval:read</code>（查询评测结果）
      </div>
      <n-collapse class="mt8">
        <n-collapse-item title="调用示例：读取 Agent 信任分" name="score">
          <div class="mono" style="font-size:11.5px;line-height:1.6;background:var(--bg-subtle);border-radius:6px;padding:8px 10px;word-break:break-all">curl "{{ origin }}/api/v1/open/score/agents/my-agent" \
  -H "X-Api-Token: eak-你的Token"</div>
        </n-collapse-item>
      </n-collapse>
    </n-card>

    <Modal v-model:show="showIssue" :width="480" title="签发 OpenAPI Token">
      <n-form label-placement="top">
        <n-form-item label="名称" required>
          <n-input v-model:value="form.name" placeholder="如 BI 报表集成" />
        </n-form-item>
        <n-form-item label="权限范围（可多选）">
          <n-checkbox-group v-model:value="form.scopeList">
            <n-space item-style="display:flex;">
              <n-checkbox v-for="s in SCOPE_OPTIONS" :key="s.value" :value="s.value" :label="s.label" />
            </n-space>
          </n-checkbox-group>
        </n-form-item>
      </n-form>
      <template #footer>
        <n-button size="small" :disabled="issuing" @click="showIssue = false">取消</n-button>
        <n-button type="primary" size="small" :loading="issuing" @click="doIssue">签发</n-button>
      </template>
    </Modal>

    <Modal v-model:show="showIssued" :width="520" title="Token 已签发（明文仅此一次）">
      <div class="mono key-plain">{{ issuedToken }}</div>
      <div class="page-sub mt8">关闭后无法再次获取，请立即保存。</div>
      <template #footer>
        <n-button size="small" @click="copyIssued">复制 Token</n-button>
        <n-button type="primary" size="small" @click="showIssued = false; load()">我已保存</n-button>
      </template>
    </Modal>
  </div>
</template>

<script setup>
import { h, onMounted, reactive, ref } from 'vue'
import { NButton, NPopconfirm, NTag, NCollapse, NCollapseItem, NCheckbox, NCheckboxGroup, NSpace } from 'naive-ui'
import { useMessage } from 'naive-ui'
import api from '@/api'
import { copyToClipboard } from '@/common/clipboard'

const message = useMessage()
const origin = location.origin

// 与后端 OpenTokenController.ALLOWED_SCOPES 一致：未知 scope 会被后端拒绝
const SCOPE_OPTIONS = [
  { value: 'score:read', label: 'score:read 读信任分/准入' },
  { value: 'eval:run', label: 'eval:run 提交评测' },
  { value: 'eval:read', label: 'eval:read 查询评测' },
  { value: 'monitors:write', label: 'monitors:write 写监控' },
]

const tokens = ref([]), loading = ref(false)
const showIssue = ref(false), issuing = ref(false)
const showIssued = ref(false), issuedToken = ref('')
const form = reactive({ name: '', scopeList: ['score:read'] })

const columns = [
  { title: '名称', key: 'name', minWidth: 130, ellipsis: { tooltip: true } },
  { title: 'Token（掩码）', key: 'tokenMasked', width: 150, render: (r) => h('span', { class: 'mono' }, r.tokenMasked || '—') },
  { title: '权限范围', key: 'scopes', minWidth: 150, render: (r) => (r.scopes || '').split(',')
      .filter(Boolean).map((s) => h(NTag, { size: 'tiny', bordered: false, style: 'margin-right:4px' }, { default: () => s })) },
  { title: '状态', key: 'status', width: 75, render: (r) => h(NTag, { size: 'small', bordered: false, type: r.status === 'ACTIVE' ? 'success' : 'error' }, { default: () => (r.status === 'ACTIVE' ? '启用中' : '已吊销') }) },
  { title: '签发时间', key: 'createdAt', width: 90, render: (r) => h('span', { class: 'num', style: 'font-size:11px' }, String(r.createdAt || '').slice(5, 16).replace('T', ' ')) },
  { title: '最近使用', key: 'lastUsedAt', width: 90, render: (r) => h('span', { class: 'num', style: 'font-size:11px', title: r.lastUsedAt || '从未使用' },
      r.lastUsedAt ? String(r.lastUsedAt).slice(5, 16).replace('T', ' ') : '从未') },
  { title: '操作', key: 'ops', width: 65, render: (r) => r.status === 'ACTIVE'
      ? h(NPopconfirm, { onPositiveClick: () => doRevoke(r) }, {
          trigger: () => h(NButton, { size: 'tiny' }, { default: () => '吊销' }),
          default: () => '确认吊销该 Token？使用它的集成将立即 401。'
        })
      : h('span', { style: 'color:var(--text-muted);font-size:12px' }, '—') }
]

async function load() {
  loading.value = true
  try { tokens.value = (await api.openTokens()) || [] }
  catch (e) { message.error(e.message || 'API Token 加载失败') } finally { loading.value = false }
}
function openIssue() {
  Object.assign(form, { name: '', scopeList: ['score:read'] })
  showIssue.value = true
}
async function doIssue() {
  if (!form.name.trim()) { message.error('请填写名称'); return }
  if (!form.scopeList.length) { message.error('请至少选择一个权限范围'); return }
  issuing.value = true
  try {
    const issued = await api.issueOpenToken({ name: form.name.trim(), scopes: form.scopeList.join(',') })
    issuedToken.value = issued.token
    showIssue.value = false
    showIssued.value = true
  } catch (e) { message.error(e.message) }
  finally { issuing.value = false }
}
async function copyIssued() {
  const ok = await copyToClipboard(issuedToken.value || '')
  if (ok) message.success('Token 已复制')
  else message.warning('复制失败，请手动选择文本')
}
async function doRevoke(r) {
  try { await api.revokeOpenToken(r.id); message.success('已吊销'); load() } catch (e) { message.error(e.message) }
}
onMounted(load)
</script>

<style scoped>
.mono { font-family: "JetBrains Mono", ui-monospace, Menlo, monospace; }
.page-sub { font-size: 11.5px; color: var(--text-muted); }
.mt8 { margin-top: 8px; }
.key-plain { background: var(--warn-bg); border: 1px dashed var(--warn); border-radius: 4px; padding: 10px 12px; user-select: all; word-break: break-all; }
</style>
