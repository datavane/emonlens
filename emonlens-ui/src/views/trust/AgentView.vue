<template>
  <div class="base-page">
    <div class="head-stack">
      <div class="page-head page-head--tabs">
        <div class="head-left">
          <n-tabs type="segment" size="small" class="tabs-segment-compact" :value="tab" @update:value="(v) => tab = v" style="--tabs-nav-width: 330px">
            <n-tab name="agents">Agent 列表</n-tab>
            <n-tab name="integrate">接入方式</n-tab>
            <n-tab name="keys">Ingest Keys</n-tab>
          </n-tabs>
        </div>
      </div>
      <div class="page-toolbar">
        <div class="toolbar-filters">
          <span v-if="tab === 'keys'" class="page-sub">明文仅签发时返回一次；SDK / OTLP 上报时通过 X-Trust-Key 头携带</span>
        </div>
        <div class="toolbar-actions">
          <lan-button v-if="tab === 'agents' && canManageAgent" type="primary" :icon="AddOutline" @click="openRegister">注册 / 同步 Agent</lan-button>
          <n-button v-else-if="tab === 'integrate'" size="small" @click="copyCode">复制代码</n-button>
          <lan-button v-else-if="tab === 'keys' && canManageKey" type="primary" size="small" ghost :loading="issuingKey" @click="issueKey">签发 Key</lan-button>
        </div>
      </div>
    </div>

    <n-card v-if="tab === 'agents'" size="small" class="table-card">
      <n-data-table class="main-table" flex-height style="height: 100%" :scroll-x="775" :columns="agentColumns" :data="agents" size="small" :bordered="false"
        :row-props="(row) => ({ style: 'cursor:pointer', onClick: () => openDetail(row) })" />
    </n-card>

    <n-card v-else-if="tab === 'integrate'" size="small" class="table-card">
      <div class="tab-scroll">
        <div class="panel-title">接入代码示例</div>
        <div class="integ-head">
          <span class="page-sub">推荐 OTLP 接口（<code>{{ ingestUrl }}</code>），下方为等价的 JSON 事件接口示例：</span>
        </div>
          <div class="mono code-block">curl -X POST {{ origin }}/api/v1/ingest/events \
  -H "X-Trust-Key: YOUR_INGEST_KEY" \
  -H "Content-Type: application/json" \
  -d '[{
    "traceId": "trace-001",
    "spanId": "root",
    "agentKey": "my-agent",
    "spanType": "agent",
    "spanName": "invoke_agent",
    "startTime": "2026-01-01T00:00:00Z",
    "endTime": "2026-01-01T00:00:03Z",
    "input": "用户输入的 prompt 内容",
    "output": "Agent 返回的回答内容"
  }]'</div>

          <div class="panel-title mt16">其他 Agent 接入（ZCode / Claude Code / Codex / Python）</div>
          <n-collapse>
            <n-collapse-item title="Claude Code（hooks，pip 一键接入）" name="claude-hooks">
              <div class="mono code-block"># 1) 安装 SDK
pip install emonlens
# 2) 一键注册（写配置 + 注册 ~/.claude/settings.json，保留已有钩子）
emonlens-claude-setup --host {{ origin }} --key tk-你的Key

# 之后正常使用 Claude Code 即可：根 span + 每次工具调用自动上报</div>
              <div class="page-sub mt8">Stop 钩子自动从会话记录提取最终回答；详见 <code>docs/Claude接入操作手册.md</code></div>
            </n-collapse-item>
            <n-collapse-item title="Python 自研 Agent（轻量客户端，复制即用）" name="py">
              <div class="page-sub" style="margin-bottom:6px">异步上报、观测失败不影响业务；同一 sessionId 自动聚合为会话：</div>
              <div class="mono code-block">import requests, threading, time
def report(spans):
    threading.Thread(target=lambda: requests.post(
        "{{ origin }}/api/v1/ingest/events",
        json=spans, headers={"X-Trust-Key": KEY}, timeout=5), daemon=True).start()

# 一次调用 = 1 个 agent 根 span + N 个 llm/tool 子 span
report([{
  "traceId": "my-trace-001", "spanId": "root",
  "agentKey": "my-agent", "spanType": "agent",
  "startTime": "2026-09-25T10:00:00", "endTime": "2026-09-25T10:00:03",
  "sessionId": "chat-42", "environment": "production",
  "input": "用户问题", "output": "Agent 回答"
}])</div>
            </n-collapse-item>
            <n-collapse-item title="Codex CLI（hooks，pip 一键接入）" name="codex">
              <div class="mono code-block"># 1) 安装 SDK
pip install emonlens
# 2) 一键注册（写配置 + 注册 ~/.codex/hooks.json，保留已有钩子）
emonlens-codex-setup --host {{ origin }} --key tk-你的Key

# 之后正常使用 Codex 即可：根 span + 每次 tool 调用自动上报</div>
              <div class="page-sub mt8">详见 <code>docs/Codex接入操作手册.md</code>（离线验证 / 故障排查 / 卸载）</div>
            </n-collapse-item>
            <n-collapse-item title="ZCode（hooks，pip 一键接入）" name="zcode">
              <div class="mono code-block"># 1) 安装 SDK
pip install emonlens
# 2) 一键注册（写配置 + 注册 ~/.zcode/cli/config.json，保留已有钩子）
emonlens-zcode-setup --host {{ origin }} --key tk-你的Key

# 新开一个 ZCode 会话后生效：根 span + 每次 tool 调用 + 每轮 tokens 自动上报</div>
              <div class="page-sub mt8">按 turnId 自动分组；详见 <code>docs/ZCode接入操作手册.md</code></div>
            </n-collapse-item>
            <n-collapse-item title="Claude Code（Stop 钩子）" name="claude-stop">
              <div class="page-sub" style="margin-bottom:6px">~/.claude/settings.json 注册 Stop 钩子，会话结束自动上报：</div>
              <div class="mono code-block">{
  "hooks": { "Stop": [{ "hooks": [
    { "type": "command", "command": "python3 ~/.emonlens/claude_stop.py" }
  ]}] }
}
# claude_stop.py：从 stdin 读事件 JSON，取 session_id 上报</div>
            </n-collapse-item>
          </n-collapse>
          <div class="page-sub mt8">完整字段说明、错误处理与生产建议见仓库文档 <code>docs/Agent接入教程.md</code></div>
      </div>
    </n-card>

    <n-card v-else size="small" class="table-card">
      <n-data-table class="main-table" flex-height style="height: 100%" :scroll-x="720" :columns="keyColumns" :data="keys" size="small" :bordered="false" />
    </n-card>

    <!-- Agent 详情抽屉：元信息 + 信任评分 + 工具清单 -->
    <n-drawer v-model:show="showDetail" :width="560">
      <n-drawer-content :title="'Agent：' + (detail?.agentKey || '')" closable>
        <template v-if="detail">
          <div class="desc-list">
            <span class="k">名称 / 版本</span><span>{{ detail.name || '—' }} · {{ detail.version || '—' }}</span>
            <span class="k">状态</span><span>{{ enumText('agentStatus', detail.status) || '—' }}</span>
            <span class="k">风险级 / Owner</span><span>{{ enumText('riskLevel', detail.riskLevel) || '—' }} / {{ detail.owner || '—' }}</span>
            <span class="k">注册时间</span><span class="mono">{{ detail.createdAt ? String(detail.createdAt).replace('T', ' ').slice(0, 19) : '—' }}</span>
          </div>
          <div class="panel-title">信任评分（近 24 小时）</div>
          <div v-if="score" class="score-line">
            <span class="score-big num" :style="{ color: scoreColor(score.score) }">{{ fmtScore(score.score) }}</span>
            <n-tag size="small" :bordered="false" :type="scoreStatusType(score.status)">{{ scoreStatusLabel(score.status) }}</n-tag>
            <span class="page-sub">快照 {{ score.timeWindow }}<template v-if="score.coverage != null"> · 证据覆盖 {{ score.coverage }}%</template></span>
          </div>
          <div v-else class="page-sub">暂无评分快照（Agent 近 24 小时无调用或证据不足）</div>
          <div v-if="score?.dimensions" class="dim-rows">
            <div v-for="(item, key) in dimRows" :key="key" class="dim-line">
              <span class="dim-name">{{ DIM_LABELS[key] || key }}</span>
              <div class="dim-bar"><i :style="{ width: (item.score ?? 0) + '%', background: dimColor(item.score) }"></i></div>
              <span class="dim-v num">{{ item.score == null ? '—' : Math.round(item.score) }}</span>
            </div>
          </div>
          <div class="panel-title">工具清单（{{ (detail.tools || []).length }}）</div>
          <div v-for="t in detail.tools || []" :key="t.name" class="tool-row">
            <span class="mono">{{ t.name }}</span>
            <n-tag size="tiny" :bordered="false" :type="TOOL_EFFECT[t.sideEffect] || 'default'">{{ enumText('toolSideEffect', t.sideEffect) }}</n-tag>
            <n-tag v-if="t.status" size="tiny" :bordered="false">{{ enumText('spanStatus', t.status) }}</n-tag>
          </div>
          <EmptyState v-if="!(detail.tools || []).length" text="尚无工具上报（tool span 自动登记）" />
        </template>
        <n-spin v-else style="width: 100%; margin: 40px 0" />
      </n-drawer-content>
    </n-drawer>

    <Modal v-model:show="showRegister" :width="520" title="注册 / 同步 Agent">
      <n-form label-placement="top">
        <n-form-item label="agentKey"><n-input v-model:value="form.agentKey" placeholder="如 fraud-agent" /></n-form-item>
        <n-form-item label="名称"><n-input v-model:value="form.name" /></n-form-item>
        <n-form-item label="版本"><n-input v-model:value="form.version" placeholder="v1" /></n-form-item>
        <n-form-item label="风险级">
          <n-select v-model:value="form.riskLevel" :options="['MEDIUM','HIGH','CRITICAL'].map(v => ({ label: v, value: v }))" />
        </n-form-item>
        <n-form-item label="Owner"><n-input v-model:value="form.owner" /></n-form-item>
      </n-form>
      <template #footer>
        <lan-button :disabled="saving" @click="showRegister = false">取消</lan-button>
        <lan-button type="primary" :loading="saving" @click="doSync">保存并同步</lan-button>
      </template>
    </Modal>

    <Modal v-model:show="showKeyName" :width="440" title="签发 Ingest Key">
      <n-form label-placement="top">
        <n-form-item label="名称（用途标签，便于日后识别该吊销哪把）">
          <n-input v-model:value="keyName" placeholder="如 prod-ingest / ci-key（可留空）" />
        </n-form-item>
      </n-form>
      <template #footer>
        <lan-button @click="showKeyName = false">取消</lan-button>
        <lan-button type="primary" :loading="issuingKey" @click="doIssueKey">签发</lan-button>
      </template>
    </Modal>

    <Modal v-model:show="showKeyIssued" :width="520" title="Key 已签发（明文仅此一次）">
      <div class="mono key-plain">{{ issuedKey }}</div>
      <div class="page-sub mt8">关闭后无法再次获取，请立即保存。</div>
      <template #footer>
        <lan-button @click="copyIssued">复制 Key</lan-button>
        <lan-button type="primary" @click="showKeyIssued = false; loadAll()">我已保存</lan-button>
      </template>
    </Modal>
  </div>
</template>

<script setup>
import { computed, h, onMounted, reactive, ref } from 'vue'
import { NButton, NPopconfirm, NTag, useMessage } from 'naive-ui'
import api from '@/api'
import { enumText } from '@/common/i18n'
import { AddOutline } from '@vicons/ionicons5'
import { useAuthStore } from '@/store/auth'
import EmptyState from '@/components/EmptyState.vue'
import TimeText from '@/components/TimeText.vue'
import { copyToClipboard } from '@/common/clipboard'

const message = useMessage()
const auth = useAuthStore()
const canManageAgent = computed(() => auth.hasPermission('workspace:trust:agent:manage'))
const canManageKey = computed(() => auth.hasPermission('workspace:trust:key:manage'))
const agents = ref([]), keys = ref([])
const saving = ref(false), issuingKey = ref(false), revokeLoadingId = ref('')
const showRegister = ref(false), showKeyIssued = ref(false), issuedKey = ref(''), showKeyName = ref(false), keyName = ref('')
const form = reactive({ agentKey: '', name: '', version: 'v1', riskLevel: 'MEDIUM', owner: '' })
const ingestUrl = location.origin + '/api/v1/ingest/otlp/v1/traces'
const origin = location.origin

const TOOL_EFFECT = { NONE: 'default', READ: 'info', WRITE: 'warning', DESTRUCTIVE: 'error', EXTERNAL_COMMUNICATION: 'default', FINANCIAL: 'error' }

const agentColumns = [
  { title: 'agentKey', key: 'agentKey', minWidth: 140, ellipsis: { tooltip: true }, render: (r) => h('span', { class: 'mono' }, r.agentKey) },
  { title: '名称', key: 'name', minWidth: 120, ellipsis: { tooltip: true } },
  { title: '健康', key: 'health', width: 75, render: (r) => {
      const hv = healthOf(r.agentKey)
      if (!hv) return h('span', { style: 'color:var(--text-muted);font-size:12px' }, '—')
      return h(NTag, { size: 'small', bordered: false,
        type: hv.health === 'HEALTHY' ? 'success' : hv.health === 'DEGRADED' ? 'error' : 'default' },
        { default: () => ({ HEALTHY: '健康', DEGRADED: '异常', IDLE: '闲置' }[hv.health] || hv.health) })
    } },
  { title: '24h 调用', key: 'traces24h', width: 70, align: 'right', render: (r) => {
      const hv = healthOf(r.agentKey)
      return h('span', { class: 'num' }, hv ? String(hv.traces24h) : '—')
    } },
  { title: '24h 错误', key: 'errors24h', width: 70, align: 'right', render: (r) => {
      const hv = healthOf(r.agentKey)
      if (!hv) return h('span', { class: 'num' }, '—')
      return h('span', { class: 'num', style: hv.errors24h > 0 ? 'color:var(--error)' : '' }, String(hv.errors24h))
    } },
  { title: '最近上报', key: 'lastSeenAt', width: 85, render: (r) => {
      const hv = healthOf(r.agentKey)
      return h(TimeText, { value: hv ? hv.lastSeenAt : null })
    } },
  { title: '状态', key: 'status', width: 65, render: (r) => h(NTag, { size: 'small', bordered: false, type: r.status === 'ACTIVE' ? 'success' : 'default' }, { default: () => enumText('agentStatus', r.status) || '—' }) },
  { title: '风险级', key: 'riskLevel', width: 75, render: (r) => h(NTag, { size: 'small', bordered: false, type: r.riskLevel === 'CRITICAL' ? 'error' : r.riskLevel === 'HIGH' ? 'warning' : 'default' }, { default: () => enumText('riskLevel', r.riskLevel) }) },
  { title: '操作', key: 'ops', width: 75, render: (r) => canManageAgent.value
      ? h(NPopconfirm, { onPositiveClick: () => doDeleteAgent(r) }, {
          trigger: () => h(NButton, { size: 'tiny', type: 'error', ghost: true }, { default: () => '注销' }),
          default: () => `确认注销 Agent「${r.agentKey}」？注册信息与工具清单会被移除，已上报的 Trace 历史保留。`
        })
      : h('span', { style: 'color:var(--text-muted)' }, '—') }
]

const keyColumns = [
  { title: '名称', key: 'name', width: 130, render: (r) => r.name
      ? h('span', {}, r.name)
      : h('span', { style: 'color:var(--text-muted);font-size:12px', title: '签发时未命名' }, '未命名') },
  { title: 'Key（掩码）', key: 'keyMasked', width: 200, render: (r) => h('span', { class: 'mono' }, r.keyMasked) },
  { title: '状态', key: 'status', width: 90, render: (r) => h(NTag, { size: 'small', bordered: false, type: r.status === 'ACTIVE' ? 'success' : 'error' }, { default: () => (r.status === 'ACTIVE' ? '启用中' : '已吊销') }) },
  { title: '签发时间', key: 'createdAt', width: 160, render: (r) => h('span', { class: 'mono', style: 'font-size:11.5px' }, r.createdAt ? String(r.createdAt).replace('T', ' ').slice(0, 19) : '—') },
  { title: '操作', key: 'ops', width: 100, render: (r) => canManageKey.value && r.status === 'ACTIVE'
      ? h(NButton, { size: 'tiny', ghost: true, loading: revokeLoadingId.value === r.id, disabled: !!revokeLoadingId.value, onClick: () => doRevoke(r) }, { default: () => '吊销' })
      : null }
]

async function loadAll() {
  agents.value = (await api.listAgents()) || []
  try { keys.value = (await api.ingestKeys()) || [] } catch (e) { keys.value = [] }
  try { healthRows.value = await api.agentHealth() || [] } catch (e) { healthRows.value = [] }
}

// ---------- 接入健康检查（agents/health 聚合） ----------
const healthRows = ref([])
function healthOf(agentKey) {
  return healthRows.value.find((r) => r.agentKey === agentKey) || null
}

// ---------- 详情下钻 ----------
const showDetail = ref(false), detail = ref(null), score = ref(null)
const tab = ref('agents')
const DIM_LABELS = { performance: 'Performance', behavior: 'Behavior', output: 'Output', security: 'Security', context: 'Context' }
const dimRows = computed(() => {
  if (!score.value?.dimensions) return {}
  try { return JSON.parse(score.value.dimensions) } catch (e) { return {} }
})
function fmtScore(v) { return v == null ? '—' : Number(v).toFixed(1) }
function scoreColor(v) {
  if (v == null) return 'var(--text-muted)'
  return v >= 80 ? 'var(--success)' : v >= 60 ? 'var(--warn)' : 'var(--error)'
}
function dimColor(v) { return v == null ? 'var(--text-muted)' : scoreColor(v) }
function scoreStatusType(s) {
  if (s === 'TRUSTED') return 'success'
  if (s === 'INSUFFICIENT_EVIDENCE' || s === 'NO_DATA') return 'default'
  if (s === 'WATCH' || s === 'DEGRADED') return 'warning'
  return 'error'
}
function scoreStatusLabel(s) {
  return ({ TRUSTED: '可信', AT_RISK: '有风险', CRITICAL: '严重', INSUFFICIENT_EVIDENCE: '证据不足', WATCH: '观察', DEGRADED: '退化', UNTRUSTED: '不可信' }[s] || s)
}
async function openDetail(row) {
  showDetail.value = true
  detail.value = null
  score.value = null
  try { detail.value = await api.agentDetail(row.agentKey) } catch (e) { detail.value = row }
  try { score.value = await api.agentScore(row.agentKey, '24h') } catch (e) { score.value = null }
}
function openRegister() {
  form.agentKey = ''; form.name = ''; form.version = 'v1'; form.riskLevel = 'MEDIUM'; form.owner = ''
  showRegister.value = true
}
async function doDeleteAgent(r) {
  try {
    await api.deleteAgent(r.agentKey)
    message.success(`已注销 ${r.agentKey}（历史 Trace 保留）`)
    loadAll()
  } catch (e) { message.error(e.message) }
}
async function doSync() {
  if (!form.agentKey) { message.error('agentKey 必填'); return }
  saving.value = true
  try {
    await api.syncAgent(form.agentKey, { name: form.name, version: form.version, riskLevel: form.riskLevel, owner: form.owner })
    message.success('已同步（PUT 幂等）'); showRegister.value = false; loadAll()
  } catch (e) { message.error(e.message) }
  finally { saving.value = false }
}
function issueKey() {
  keyName.value = ''
  showKeyName.value = true
}
async function doIssueKey() {
  issuingKey.value = true
  try {
    const issued = await api.issueIngestKey(keyName.value.trim() ? { name: keyName.value.trim() } : undefined)
    issuedKey.value = issued.key
    showKeyName.value = false
    showKeyIssued.value = true
    loadAll()
  } catch (e) { message.error(e.message) }
  finally { issuingKey.value = false }
}
async function copyCode() {
  const el = document.querySelector('.code-block')
  const ok = await copyToClipboard(el?.textContent || '')
  if (ok) message.success('代码已复制')
  else message.warning('复制失败，请手动选择文本')
}
async function copyIssued() {
  const ok = await copyToClipboard(issuedKey.value || '')
  if (ok) message.success('Key 已复制')
  else message.warning('复制失败，请手动选择文本')
}
async function doRevoke(r) {
  revokeLoadingId.value = r.id
  try { await api.revokeIngestKey(r.id); message.success('Key 已吊销，上报立即 40101'); loadAll() }
  catch (e) { message.error(e.message) }
  finally { revokeLoadingId.value = '' }
}
onMounted(loadAll)
</script>

<style scoped>
code { background: var(--bg-subtle); border-radius: 3px; padding: 1px 5px; font-size: 12px; }
.code-block { background: var(--code-chrome); color: var(--code-text); border-radius: 6px; padding: 12px 16px; font-family: Menlo, monospace; font-size: 12px; white-space: pre-wrap; word-break: break-all; line-height: 1.6; }
.key-plain { background: var(--warn-bg); border: 1px dashed var(--warn); border-radius: 4px; padding: 10px 12px; user-select: all; }
.desc-list { display: grid; grid-template-columns: 110px 1fr; row-gap: 8px; font-size: 12.5px; }
.desc-list .k { color: var(--text-muted); }
.panel-title { font-size: 11.5px; font-weight: 600; color: var(--text-muted); margin: 16px 0 8px; text-transform: uppercase; letter-spacing: 0.04em; }
.score-line { display: flex; align-items: center; gap: 10px; }
.score-big { font-size: 34px; font-weight: 700; line-height: 1; }
.dim-rows { margin-top: 10px; display: flex; flex-direction: column; gap: 7px; }
.dim-line { display: flex; align-items: center; gap: 10px; }
.dim-name { flex: 0 0 90px; font-size: 12px; color: var(--text-secondary); }
.dim-bar { flex: 1; height: 6px; background: var(--bg-subtle); border-radius: 3px; overflow: hidden; }
.dim-bar i { display: block; height: 100%; border-radius: 3px; }
.dim-v { flex: 0 0 30px; text-align: right; font-size: 11.5px; color: var(--text-secondary); }
.tool-row { display: flex; align-items: center; gap: 8px; padding: 4px 0; font-size: 12.5px; }
.mono { font-family: "JetBrains Mono", ui-monospace, Menlo, monospace; }
.page-sub { font-size: 11.5px; color: var(--text-muted); }
.integ-head { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-bottom: 8px; }
.tab-scroll { flex: 1; min-height: 0; overflow: auto; overscroll-behavior: contain; }
.num { font-family: "JetBrains Mono", ui-monospace, Menlo, monospace; font-variant-numeric: tabular-nums; }
</style>
