<template>
  <div class="base-page alerts-page">
    <div class="head-stack">
      <div class="page-head page-head--tabs">
        <div class="head-left">
          <n-tabs type="segment" size="small" class="tabs-segment-compact" style="--tabs-nav-width: 330px" :value="tab" @update:value="onTabChange">
            <n-tab name="rules">规则</n-tab>
            <n-tab name="history">触发历史</n-tab>
            <n-tab name="findings">异常发现</n-tab>
          </n-tabs>
        </div>
      </div>
      <div class="page-toolbar">
        <div class="toolbar-filters">
          <template v-if="tab === 'history'">
            <n-select v-model:value="agentFilter" placeholder="全部 Agent" size="small" style="width: 160px" clearable
              :options="agentOptions" @update:value="onAgentFilterChange" />
          </template>
          <template v-else-if="tab === 'findings'">
            <n-select v-model:value="findingSeverity" placeholder="全部严重级" size="small" style="width: 150px" clearable
              :options="enumOptions('severity', ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'])" @update:value="onFindingFilterChange" />
            <n-select v-model:value="findingAgent" placeholder="全部 Agent" size="small" style="width: 160px" clearable
              :options="agentOptions" @update:value="onFindingFilterChange" />
          </template>
        </div>
        <div class="toolbar-actions">
          <n-button v-if="tab === 'rules'" type="primary" size="small" @click="openCreate">＋ 新建规则</n-button>
          <n-button v-else size="small" :loading="refreshing" @click="refreshCurrent">刷新</n-button>
        </div>
      </div>
    </div>

    <!-- Alert Rules -->
    <template v-if="tab === 'rules'">
      <n-card size="small" class="table-card">
        <template #header><span>监控规则（{{ monitors.length }}）</span></template>
        <n-data-table class="main-table" flex-height style="height: 100%" :columns="monitorColumns" :data="monitors" size="small" :bordered="false" />
      </n-card>
    </template>

    <!-- Alert History -->
    <template v-if="tab === 'history'">
      <!-- 状态统计条：一键过滤（样式对齐评估结果统计卡） -->
      <div class="stat-strip">
        <div class="s-item" :class="{ active: statusFilter === '' }" @click="setStatus('')">
          <span class="s-v">{{ incidentStats.total }}</span><span class="s-k">全部</span>
        </div>
        <div class="s-item" :class="{ active: statusFilter === 'OPEN' }" @click="setStatus('OPEN')">
          <span class="s-v" style="color:var(--error)">{{ incidentStats.open }}</span><span class="s-k">待处理</span>
        </div>
        <div class="s-item" :class="{ active: statusFilter === 'ACKNOWLEDGED' }" @click="setStatus('ACKNOWLEDGED')">
          <span class="s-v" style="color:var(--warn)">{{ incidentStats.acknowledged }}</span><span class="s-k">已确认</span>
        </div>
        <div class="s-item" :class="{ active: statusFilter === 'RESOLVED' }" @click="setStatus('RESOLVED')">
          <span class="s-v" style="color:var(--success)">{{ incidentStats.resolved }}</span><span class="s-k">已恢复</span>
        </div>
        <div class="s-item static">
          <span class="s-v" style="color:var(--error)">{{ incidentStats.critical }}</span><span class="s-k">严重待处理</span>
        </div>
        <div v-if="checkedIncidents.length" class="stat-actions">
          <n-button size="small" type="warning" ghost :loading="bulkLoading"
            @click="bulkAction('ack')">批量确认（{{ checkedIncidents.length }}）</n-button>
          <n-popconfirm @positive-click="bulkAction('resolve')">
            <template #trigger>
              <n-button size="small" type="success" ghost :loading="bulkLoading">批量恢复（{{ checkedIncidents.length }}）</n-button>
            </template>
            确认批量恢复 {{ checkedIncidents.length }} 条告警？
          </n-popconfirm>
        </div>
      </div>
      <n-card size="small" class="mt12 table-card">
        <n-data-table class="main-table" flex-height style="height: 100%" :columns="incidentColumns" :data="incidents"
          size="small" :bordered="false" remote
          :pagination="incidentPagination" :row-key="(r) => r.id" :checked-row-keys="checkedIncidents" :row-class-name="incidentRowClass"
          @update:checked-row-keys="(keys) => checkedIncidents = keys" />
      </n-card>

      <!-- 死信（重试超限事件）：可见 + 人工重投 -->
      <n-card size="small" class="mt12">
        <template #header>
          <span>死信（重试超限事件）{{ deadEvents.length ? '（' + deadEvents.length + '）' : '' }}</span>
        </template>
        <template #header-extra>
          <n-button size="tiny" quaternary @click="loadDead">刷新</n-button>
        </template>
        <div class="filters" style="margin-bottom:8px">
          <n-select v-model:value="deadEventType" placeholder="全部类型" size="small" style="width: 200px" clearable filterable tag
            :options="deadTypeOptions" @update:value="onDeadTypeChange" />
          <span class="page-sub">通知/Webhook 重试超限的事件（已置 DEAD 不再自动重试）。修复下游后「重新入队」。</span>
        </div>
        <n-data-table :columns="deadColumns" :data="deadEvents" size="small"
          :bordered="false" :max-height="300" remote
          :pagination="deadPagination" :loading="deadLoading" :row-key="(r) => r.id"
          :row-props="(row) => ({ style: 'cursor:pointer', onClick: () => openDeadDetail(row) })" />
        <EmptyState v-if="!deadLoading && !deadEvents.length" text="无死信事件——告警链路全部处理成功或仍在重试中" />
      </n-card>

      <!-- 死信 payload 详情 -->
      <n-drawer v-model:show="showDeadDetail" :width="560">
        <n-drawer-content title="死信事件详情" closable>
          <template v-if="deadDetail">
            <div class="desc-list" style="margin-bottom:12px">
              <span class="k">事件类型</span><span class="mono">{{ deadDetail.eventType }}</span>
              <span class="k">Agent</span><span class="mono">{{ deadDetail.agentKey || '—' }}</span>
              <span class="k">重试次数</span><span class="num">{{ deadDetail.retryCount ?? 0 }}</span>
              <span class="k">时间</span><span class="num">{{ deadDetail.createdAt ? String(deadDetail.createdAt).replace('T', ' ').slice(0, 19) : '—' }}</span>
            </div>
            <div class="panel-title">事件 Payload</div>
            <CodeBlock :content="deadDetail.payload || '（无 payload）'" label="Payload" :max-height="260" />
          </template>
        </n-drawer-content>
      </n-drawer>
    </template>

    <!-- Findings：检测引擎逐 span 异常发现（事件聚合前） -->
    <template v-if="tab === 'findings'">
      <n-card size="small" class="table-card">
        <n-data-table class="main-table" flex-height style="height: 100%" :columns="findingColumns" :data="findings" size="small" :bordered="false"
          remote :pagination="findingPagination" :loading="findingLoading"
          :row-class-name="findingRowClass"
          :row-props="(row) => ({ style: 'cursor:pointer', onClick: () => openFinding(row) })" />
      </n-card>
    </template>

    <!-- 新建规则 -->
    <Modal v-model:show="showCreate" :width="560" title="新建监控规则">
      <n-form label-placement="top">
        <n-form-item label="规则 Key（唯一）" required>
          <n-input v-model:value="createForm.monitorKey" placeholder="如 llm-call-error" />
        </n-form-item>
        <n-form-item label="作用 Agent" required>
          <n-select v-model:value="createForm.agentKey" placeholder="选择 Agent" :options="agentOptions" />
        </n-form-item>
        <n-form-item label="规则类型" required>
          <n-select v-model:value="createForm.kind" :options="kindOptions" />
        </n-form-item>
        <template v-if="createForm.kind === 'VALIDATION'">
          <n-form-item label="常用模板（点击填入，可再修改）">
            <div style="display:flex;flex-wrap:wrap;gap:6px">
              <n-button v-for="tpl in validationTemplates" :key="tpl.label" size="tiny" ghost type="primary"
                @click="applyTemplate(tpl)">{{ tpl.label }}</n-button>
            </div>
          </n-form-item>
          <n-form-item label="作用 Span 类型">
            <n-select v-model:value="createForm.spanType" placeholder="全部（不限定）" clearable
              :options="['agent', 'llm', 'tool', 'retrieval'].map((t) => ({ label: t, value: t }))" />
          </n-form-item>
          <n-form-item label="违规条件（表达式为 true 即违规）" required>
            <n-input v-model:value="createForm.where" placeholder="如 status == 'ERROR'" class="mono" />
          </n-form-item>
          <div class="page-sub mono" style="margin-top:-6px;margin-bottom:8px">
            将生成：{{ validationSpecPreview }}
          </div>
          <div v-if="whereHint" class="mono" style="color:var(--warn);font-size:11.5px;margin-top:-4px;margin-bottom:8px">
            {{ whereHint }}
          </div>
          <n-collapse style="margin-bottom:8px" :default-expanded-names="['help']">
            <n-collapse-item title="可用字段与语法速查（点字段名直接插入条件）" name="help">
              <div class="help-panel">
                <div class="help-title">Span 字段<span class="page-sub">（点击插入到条件表达式）</span></div>
                <div class="field-chips">
                  <span v-for="f in whereFields" :key="f.name" class="field-chip" :title="f.desc"
                    @click="insertField(f.name)">{{ f.name }}</span>
                </div>
                <div class="page-sub mt8">
                  attributes 内的全部字段（如 <span class="mono">cwd · turnId · cachedInputTokens</span>）同样可直接引用
                </div>
                <div class="help-title mt12">常用语法</div>
                <div class="syntax-list">
                  <div class="syntax-row"><code>status == 'ERROR'</code><span>比较：== != &gt; &lt; &gt;= &lt;=</span></div>
                  <div class="syntax-row"><code>a and b · or · not</code><span>逻辑（也可 &amp;&amp; / ||）</span></div>
                  <div class="syntax-row"><code>{'a','b'}.contains(x)</code><span>集合成员（取反在前面加 !）</span></div>
                  <div class="syntax-row"><code>x matches '.*RateLimit.*'</code><span>正则匹配</span></div>
                  <div class="syntax-row"><code>x == null / x != null</code><span>空判断</span></div>
                  <div class="syntax-row"><code>s.startsWith('query')</code><span>字符串前缀 / endsWith / contains</span></div>
                  <div class="syntax-row"><code>duration_ms &gt; 30000</code><span>数值比较（毫秒 / tokens）</span></div>
                </div>
              </div>
            </n-collapse-item>
          </n-collapse>
        </template>
        <template v-else-if="createForm.kind === 'EVALUATION'">
          <n-form-item label="评估器" required>
            <n-select v-model:value="createForm.evaluatorKey" placeholder="选择评估器"
              :options="evaluatorOptions" />
          </n-form-item>
          <n-form-item label="通过率阈值（低于即告警）" required>
            <n-input-number v-model:value="createForm.passRate" :min="0" :max="1" :step="0.1" style="width: 200px" />
          </n-form-item>
          <div class="page-sub">
            当前=通过率 <b class="num">{{ Math.round(createForm.passRate * 100) }}%</b> 以下即告警（0.5=半数失败触发）。
            窗口内最近 10 次评估结果判定；建议先在「评估器」页创建评估器并单条试跑。
          </div>
        </template>
        <template v-else-if="createForm.kind === 'METRIC'">
          <n-form-item label="指标" required>
            <n-select v-model:value="createForm.metric" :options="[
              { label: '错误率 error_rate', value: 'error_rate' },
              { label: '平均/分位耗时 duration_ms', value: 'duration_ms' },
              { label: '步数 steps_per_trace', value: 'steps_per_trace' },
              { label: '调用量 trace_count', value: 'trace_count' }
            ]" />
          </n-form-item>
          <div class="page-sub metric-hint">{{ metricHint }}</div>
          <n-form-item label="阈值（超过即告警）" required>
            <n-input-number v-model:value="createForm.thresholdValue"
              :min="0" :max="createForm.metric === 'error_rate' ? 1 : undefined"
              :step="createForm.metric === 'error_rate' ? 0.05 : 1" style="width: 200px" />
          </n-form-item>
          <n-form-item label="统计窗口（分钟）">
            <n-input-number v-model:value="createForm.windowMinutes" :min="1" style="width: 200px" />
          </n-form-item>
        </template>
        <n-form-item label="严重级" required>
          <n-select v-model:value="createForm.severity" :options="enumOptions('severity', ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'])" />
        </n-form-item>
      </n-form>
      <template #footer>
        <n-button size="small" :disabled="creating" @click="showCreate = false">取消</n-button>
        <n-button type="primary" size="small" :loading="creating" @click="doCreate">创建</n-button>
      </template>
    </Modal>

    <!-- 规则试跑 -->
    <Modal v-model:show="showDryRun" :width="620" :title="'试跑规则：' + (dryRunRule?.monitorKey || '')">
      <div class="page-sub" style="margin-bottom:8px">对最近 {{ dryRunItems.length }} 条 Trace 检验规则命中情况（不影响线上数据）</div>
      <n-spin v-if="dryRunLoading" style="width:100%;margin:24px 0" />
      <n-data-table v-else :columns="dryRunColumns" :data="dryRunItems" size="small" :bordered="false" :max-height="320" />
      <template #footer>
        <n-button size="small" @click="showDryRun = false">关闭</n-button>
      </template>
    </Modal>
    <!-- 异常发现详情 -->
    <n-drawer v-model:show="showFinding" :width="560">
      <n-drawer-content :title="'异常发现详情'" closable>
        <template v-if="findingDetail">
          <div class="desc-list" style="margin-bottom:12px">
            <span class="k">严重级</span><span><n-tag size="tiny" :bordered="false" :type="sevType(findingDetail.severity)">{{ enumText('severity', findingDetail.severity) }}</n-tag></span>
            <span class="k">维度 / 类型</span><span>{{ enumText('findingDimension', findingDetail.dimension) || '—' }} · {{ enumText('findingType', findingDetail.type) || '—' }}</span>
            <span class="k">Agent</span><span class="mono">{{ findingDetail.agentKey || '—' }}</span>
            <span class="k">检测器</span><span class="mono">{{ findingDetail.detectorId || '—' }}</span>
            <span class="k">时间</span><span class="num">{{ findingDetail.createdAt ? String(findingDetail.createdAt).replace('T', ' ').slice(0, 19) : '—' }}</span>
            <span class="k">指纹</span><span class="mono" style="font-size:11.5px;word-break:break-all">{{ findingDetail.fingerprint || '—' }}</span>
          </div>
          <n-button size="small" type="primary" ghost style="margin-bottom:12px"
            @click="router.push('/traces/' + encodeURIComponent(findingDetail.traceId))">
            查看完整 Trace →
          </n-button>
          <div class="panel-title">消息</div>
          <div style="font-size:13px;line-height:1.6;margin-bottom:12px">{{ findingDetail.message || '—' }}</div>
          <div class="panel-title">证据（Evidence）</div>
          <CodeBlock :content="findingDetail.evidence || '（无证据数据）'" label="Evidence" :max-height="260" />
        </template>
      </n-drawer-content>
    </n-drawer>
  </div>
</template>

<script setup>
import { computed, h, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NButton, NPopconfirm, NSwitch, NTag, useMessage } from 'naive-ui'
import api from '@/api'
import { MONITOR_KIND_COLORS, MUTED } from '@/common/colors'
import { enumText, enumOptions } from '@/common/i18n'
import { fetchAgentOptions } from '@/common/agents'
import Modal from '@/components/Modal.vue'
import TimeText from '@/components/TimeText.vue'
import CodeBlock from '@/components/CodeBlock.vue'
import EmptyState from '@/components/EmptyState.vue'

const message = useMessage()
const router = useRouter()
const route = useRoute()
// 页签从路由派生（与系统设置/用户角色一致）：支持深链、刷新后保持、可分享链接。
// 此前是本地 ref，刷新就跳回「规则」，外部链接也无法直达「触发历史/异常发现」。
const ALERT_TABS = ['rules', 'history', 'findings']
const tab = computed(() => (ALERT_TABS.includes(route.query.tab) ? route.query.tab : 'rules'))
const monitors = ref([])
const incidents = ref([])
const statusFilter = ref('OPEN')
const agentFilter = ref(null)
const agentOptions = ref([])
const refreshing = ref(false)
const showCreate = ref(false), creating = ref(false)
const createForm = reactive({ monitorKey: '', agentKey: null, kind: 'VALIDATION', spanType: null, where: "status == 'ERROR'", metric: 'error_rate', thresholdValue: 0.1, windowMinutes: 10, severity: 'HIGH', evaluatorKey: null, passRate: 0.5 })

// METRIC 指标解释（跟随选择联动，降低配置门槛）
const metricHint = computed(() => ({
  error_rate: '错误率：窗口内「错误 trace / 全部 trace」占比，0~1。建议 0.05~0.1（错误占比 5%~10% 触发）',
  duration_ms: '耗时：窗口内 trace 耗时均值/分位（毫秒）。建议按业务可接受的 P95 设定，如 30000=30s',
  steps_per_trace: '步数：窗口内 trace 平均 step 数。如 12 = 平均 12 步触发，用于发现流程异常膨胀',
  trace_count: '调用量：窗口内 trace 总量。如 1000 = 1000 次触发，可用于流量突增预警'
}[createForm.metric] || ''))

// VALIDATION 常用模板：点击即填入 spanType + where，降低手写表达式门槛
const validationTemplates = [
  { label: 'LLM 调用失败', spanType: 'llm', where: "status == 'ERROR'" },
  { label: '任意调用出错', spanType: null, where: "status == 'ERROR'" },
  { label: '工具执行失败', spanType: 'tool', where: "status == 'ERROR'" },
  { label: '慢调用 >30s', spanType: null, where: 'duration_ms > 30000' },
  { label: '限流类错误', spanType: 'llm', where: "error_type matches '.*RateLimit.*'" },
  { label: '非白名单模型', spanType: 'llm', where: "model_name != null and !({'gpt-4o-mini','gpt-4o'}.contains(model_name))" },
  { label: '输出超长 >4K tokens', spanType: 'llm', where: 'output_tokens > 4000' },
]
function applyTemplate(tpl) {
  createForm.spanType = tpl.spanType
  createForm.where = tpl.where
}
// 语法速查的可点击字段（desc 为 tooltip 说明）
const whereFields = [
  { name: 'status', desc: '状态：OK / ERROR' },
  { name: 'error_type', desc: '错误类型，如 RateLimitError / TimeoutError' },
  { name: 'span_type', desc: 'span 类型：agent / llm / tool / retrieval' },
  { name: 'span_name', desc: 'span 名称' },
  { name: 'tool_name', desc: '工具名（tool span）' },
  { name: 'model_name', desc: '模型名（llm span）' },
  { name: 'duration_ms', desc: '耗时（毫秒）' },
  { name: 'input_tokens', desc: '输入 tokens' },
  { name: 'output_tokens', desc: '输出 tokens' },
  { name: 'trace_id', desc: '所属 Trace ID' },
  { name: 'span_id', desc: 'Span ID' },
]
function insertField(name) {
  // 插入「字段 == 」比较骨架：裸字段名拼不出合法表达式（如 duration_ms > 30000 duration_ms）
  const base = (createForm.where || '').replace(/\s+$/, '')
  const endsWithOperator = /(==|!=|>=|<=|>|<|\band\b|\bor\b|\bnot\b|\(|!)$/.test(base)
  createForm.where = base
    ? (endsWithOperator ? base + ' ' + name + ' ' : base + ' and ' + name + ' == ')
    : name + ' == '
}
const validationSpecPreview = computed(() => {
  const spanType = createForm.spanType || '全部'
  return `{ spanType: ${spanType}, where: ${createForm.where || '（必填）'} }`
})
// 轻量合法性提示：表达式必须含比较/包含类运算，且不能以运算符结尾（SpEL 编译期才会报错，这里提前提醒）
const whereHint = computed(() => {
  const w = (createForm.where || '').trim()
  if (!w) return ''
  const hasCompare = /(==|!=|>=|<=|>|<)\s|\.contains\(|\.startsWith\(|\.endsWith\(|\.matches\(|\bisEmpty\(|\bmatches\b/.test(w)
  if (!hasCompare) return '表达式里没有比较运算：至少写成「字段 == 值」（点上方字段名可自动插入）'
  if (/(==|!=|>=|<=|>|<|\band\b|\bor\b|\bnot\b)\s*$/.test(w)) return '表达式以运算符结尾：补上右侧的值或字段'
  return ''
})
const evaluatorOptions = ref([])

const kindOptions = [
  { label: 'VALIDATION（单 span 确定性规则）', value: 'VALIDATION' },
  { label: 'METRIC（SLI 指标越限）', value: 'METRIC' },
  { label: 'EVALUATION（评估通过率阈值）', value: 'EVALUATION' }
]

// ---------- 格式化 ----------
function fmtRel(iso) {
  if (!iso) return '—'
  const t = new Date(String(iso).length >= 23 ? String(iso).slice(0, 23) : String(iso)).getTime()
  if (Number.isNaN(t)) return iso
  const diff = Date.now() - t
  if (diff < 60000) return '刚刚'
  if (diff < 3600000) return Math.floor(diff / 60000) + ' 分钟前'
  if (diff < 86400000) return Math.floor(diff / 3600000) + ' 小时前'
  return Math.floor(diff / 86400000) + ' 天前'
}
function sevType(s) {
  return s === 'CRITICAL' ? 'error' : s === 'HIGH' ? 'warning' : 'default'
}
function specPreview(r) {
  const spec = r.spec || {}
  if (r.kind === 'VALIDATION') return `${spec.spanType ? spec.spanType + ' · ' : ''}${spec.where || ''}`
  if (r.kind === 'METRIC') return `${spec.metric} ${spec.direction === 'below' ? '<' : '>'} ${spec.thresholdValue} / ${spec.windowMinutes}min`
  if (r.kind === 'EVALUATION') return `${spec.evaluatorKey}: pass_rate ${spec.op} ${spec.value}`
  return JSON.stringify(spec)
}

// ---------- 规则表 ----------
const monitorColumns = [
  { title: '规则名', key: 'monitorKey', width: 170 },
  { title: '类型', key: 'kind', width: 110, render: (r) => h(NTag, { size: 'small', bordered: false, color: { color: (MONITOR_KIND_COLORS[r.kind] || MUTED) + '1a', textColor: MONITOR_KIND_COLORS[r.kind] || MUTED } }, { default: () => enumText('monitorKind', r.kind) }) },
  { title: '规则定义', key: 'spec', minWidth: 210, render: (r) => h('span', { class: 'mono', style: 'font-size:11.5px;color:var(--text-secondary)' }, specPreview(r)) },
  { title: 'Agent', key: 'agentKey', width: 120 },
  { title: '命中(7天)', key: 'hits7d', width: 120, render: (r) => {
      const stat = ruleStatsOf(r.monitorKey)
      const max = Math.max(1, ...Object.values(ruleStats.value).map((s) => s.hits7d))
      return h('div', { style: 'display:flex;align-items:center;gap:8px' }, [
        h('div', { style: 'flex:1;height:6px;background:var(--bg-subtle);border-radius:3px;overflow:hidden',
          title: '近 7 天命中 ' + stat.hits7d + ' 次（占最活跃规则的相对比例）' }, [
          h('div', { style: `height:100%;width:${Math.round(stat.hits7d / max * 100)}%;background:${stat.hits7d ? MONITOR_KIND_COLORS[r.kind] || 'var(--primary)' : 'var(--bg-subtle)'};border-radius:3px` })
        ]),
        h('span', { class: 'num', style: 'font-size:11.5px;color:var(--text-muted);flex:0 0 30px;text-align:right' }, String(stat.hits7d))
      ])
    } },
  { title: '最近命中', key: 'lastHit', width: 100, render: (r) => {
      const stat = ruleStatsOf(r.monitorKey)
      return h('span', { title: stat.lastHit, style: stat.lastHit ? '' : 'color:var(--text-muted)' }, stat.lastHit ? fmtRel(stat.lastHit) : '—')
    } },
  { title: '严重级', key: 'severity', width: 95, render: (r) => h(NTag, { size: 'small', bordered: false, type: sevType(r.severity) }, { default: () => enumText('severity', r.severity) }) },
  { title: '启用', key: 'enabled', width: 70, render: (r) => h(NSwitch, { size: 'small', value: !!r.enabled, onUpdateValue: (v) => doToggle(r, v) }) },
  { title: '操作', key: 'ops', width: 150, render: (r) => h('span', { style: 'display:inline-flex;gap:6px;align-items:center' }, [
      h(NButton, { size: 'tiny', onClick: () => openDryRun(r) }, { default: () => '试跑' }),
      h(NPopconfirm, { onPositiveClick: () => doDeleteMonitor(r) }, {
        trigger: () => h(NButton, { size: 'tiny', type: 'error', ghost: true }, { default: () => '删除' }),
        default: () => `确认删除规则「${r.monitorKey}」？已产生的 Incident/Finding 会保留作为审计轨迹。`
      })
    ]) }
]

// ---------- 规则命中统计（findings.detectorId = 规则 key） ----------
const ruleStats = ref({})
function ruleStatsOf(monitorKey) {
  return ruleStats.value[monitorKey] || { hits7d: 0, lastHit: '' }
}
async function loadRuleStats() {
  try {
    const page = await api.findings({ pageNumber: 1, pageSize: 500 })
    const rows = page.records || []
    const since = Date.now() - 7 * 86400000
    const stats = {}
    for (const f of rows) {
      const key = f.detectorId
      if (!key) continue
      if (!stats[key]) stats[key] = { hits7d: 0, lastHit: '' }
      const t = Date.parse(String(f.createdAt || '').slice(0, 19).replace(' ', 'T'))
      if (!isNaN(t) && t >= since) stats[key].hits7d++
      if (!stats[key].lastHit || (String(f.createdAt) > stats[key].lastHit)) stats[key].lastHit = f.createdAt
    }
    ruleStats.value = stats
  } catch (e) { ruleStats.value = {} }
}

// ---------- 告警历史表 ----------
const incidentColumns = [
  { type: 'selection', width: 40 },
  { title: '严重级', key: 'severity', width: 85, render: (r) => h(NTag, { size: 'small', bordered: false, type: sevType(r.severity) }, { default: () => enumText('severity', r.severity) }) },
  { title: 'Agent', key: 'agentKey', width: 100 },
  { title: '标题', key: 'title', minWidth: 200, ellipsis: { tooltip: true } },
  { title: '状态', key: 'status', width: 105, render: (r) => h(NTag, { size: 'small', bordered: false, type: r.status === 'OPEN' ? 'error' : r.status === 'ACKNOWLEDGED' ? 'warning' : 'success' }, { default: () => enumText('incidentStatus', r.status) }) },
  { title: '命中', key: 'findingCount', width: 55, align: 'right', render: (r) => String(r.findingCount ?? 1) },
  { title: '首次', key: 'firstSeenAt', width: 90, render: (r) => h('span', { title: r.firstSeenAt }, fmtRel(r.firstSeenAt)) },
  { title: '最近', key: 'lastSeenAt', width: 90, render: (r) => h('span', { title: r.lastSeenAt }, fmtRel(r.lastSeenAt)) },
  { title: '操作', key: 'ops', width: 140, render: (r) => {
      const btns = []
      if (r.status === 'OPEN') btns.push(h(NButton, { size: 'tiny', onClick: () => doAck(r) }, { default: () => '确认' }))
      if (r.status !== 'RESOLVED') btns.push(h(NButton, { size: 'tiny', style: btns.length ? 'margin-left:6px' : '', onClick: () => doResolve(r) }, { default: () => '恢复' }))
      return btns.length ? btns : h('span', { style: 'color:var(--text-muted);font-size:12px' }, '—')
    } }
]
function incidentRowClass(row) {
  return row.status === 'OPEN' ? 'row-open' : ''
}

async function load() {
  if (tab.value === 'rules') await loadMonitors()
  else if (tab.value === 'findings') await loadFindings()
  else await loadIncidents()
}
async function refreshCurrent() {
  refreshing.value = true
  try {
    await load()
    if (tab.value === 'history') await loadDead()
  } finally {
    refreshing.value = false
  }
}
async function loadMonitors() {
  const page = await api.monitors({ pageNumber: 1, pageSize: 50 })
  monitors.value = page.records || []
  loadRuleStats()
}
const incidentPagination = reactive({
  page: 1, pageSize: 20, itemCount: 0, showSizePicker: true, pageSizes: [20, 50, 100],
  prefix: ({ itemCount }) => '共 ' + itemCount + ' 条',
  onChange: (page) => { incidentPagination.page = page; loadIncidents() },
  onUpdatePageSize: (pageSize) => { incidentPagination.pageSize = pageSize; incidentPagination.page = 1; loadIncidents() }
})
async function loadIncidents() {
  const page = await api.incidents({
    status: statusFilter.value || undefined, agentKey: agentFilter.value || undefined,
    pageNumber: incidentPagination.page, pageSize: incidentPagination.pageSize
  })
  const rows = page.records || []
  incidentPagination.itemCount = page.total || 0
  // 处置掉当前页最后一条后回退一页，避免停在空页
  if (!rows.length && incidentPagination.page > 1 && page.total > 0) {
    incidentPagination.page--
    return loadIncidents()
  }
  incidents.value = rows
  refreshStats()
}
function onAgentFilterChange() {
  incidentPagination.page = 1
  loadIncidents()
}
function onTabChange(v) {
  router.replace({ query: { ...route.query, tab: v } })
}

// 页签变化时按新页签重取数据（tab 由路由派生，watch 即时触发）
watch(tab, (v) => {
  load()
  if (v === 'history') loadDead()
})

// ---------- 死信（重试超限事件） ----------
const deadEvents = ref([])
const deadColumns = [
  { title: '事件类型', key: 'eventType', width: 200, render: (r) => h('span', { class: 'mono', style: 'font-size:12px' }, r.eventType) },
  { title: 'Agent', key: 'agentKey', width: 120 },
  { title: '重试次数', key: 'retryCount', width: 80, align: 'right', render: (r) => h('span', { class: 'num' }, String(r.retryCount ?? 0)) },
  { title: '时间', key: 'createdAt', width: 160, render: (r) => h('span', { class: 'num', style: 'font-size:11.5px' }, String(r.createdAt || '').replace('T', ' ').slice(0, 19)) },
  { title: '操作', key: 'ops', width: 100, render: (r) =>
      h(NPopconfirm, { onPositiveClick: () => doRequeue(r) }, {
        trigger: () => h(NButton, { size: 'tiny', type: 'primary', ghost: true }, { default: () => '重新入队' }),
        default: () => `确认重新入队事件「${r.eventType}」？将立即重试处理（含通知/Webhook 重发）。`
      }) }
]
const deadEventType = ref(null), deadLoading = ref(false)
const deadPagination = reactive({
  page: 1, pageSize: 10, itemCount: 0, simple: true,
  onChange: (page) => { deadPagination.page = page; loadDead() }
})
function onDeadTypeChange() {
  deadPagination.page = 1
  loadDead()
}
const deadTypeOptions = computed(() => {
  const set = new Set(deadEvents.value.map((r) => r.eventType).filter(Boolean))
  return [...set].map((t) => ({ label: t, value: t }))
})
async function loadDead() {
  deadLoading.value = true
  try {
    const page = await api.eventDeadList({
      eventType: deadEventType.value || undefined,
      pageNumber: deadPagination.page, pageSize: deadPagination.pageSize
    })
    deadEvents.value = page.records || []
    deadPagination.itemCount = page.total || 0
  } catch (e) { deadEvents.value = [] }
  finally { deadLoading.value = false }
}
// payload 查看抽屉
const showDeadDetail = ref(false), deadDetail = ref(null)
function openDeadDetail(row) {
  deadDetail.value = row
  showDeadDetail.value = true
}
async function doRequeue(r) {
  try {
    await api.eventDeadRequeue(r.id)
    message.success('已重新入队，将由实例下一轮认领处理')
    loadDead()
  } catch (e) { message.error(e.message) }
}

// ---------- Findings（异常发现） ----------
const findings = ref([])
const findingLoading = ref(false)
const findingSeverity = ref(null)
const findingAgent = ref(null)
const findingPagination = reactive({
  page: 1, pageSize: 20, itemCount: 0, showSizePicker: true, pageSizes: [20, 50, 100],
  prefix: ({ itemCount }) => '共 ' + itemCount + ' 条',
  onChange: (page) => { findingPagination.page = page; loadFindings() },
  onUpdatePageSize: (pageSize) => { findingPagination.pageSize = pageSize; findingPagination.page = 1; loadFindings() }
})
async function loadFindings() {
  findingLoading.value = true
  try {
    const page = await api.findings({
      severity: findingSeverity.value || undefined,
      agentKey: findingAgent.value || undefined,
      pageNumber: findingPagination.page, pageSize: findingPagination.pageSize
    })
    const rows = page.records || []
    findingPagination.itemCount = page.total || 0
    if (!rows.length && findingPagination.page > 1 && page.total > 0) {
      findingPagination.page--
      return loadFindings()
    }
    findings.value = rows
  } catch (e) { message.error(e.message || '加载失败') }
  finally { findingLoading.value = false }
}
function onFindingFilterChange() {
  findingPagination.page = 1
  loadFindings()
}
function sevTag(s) {
  const type = s === 'CRITICAL' ? 'error' : s === 'HIGH' ? 'warning' : 'default'
  return h(NTag, { size: 'small', bordered: false, type }, { default: () => enumText('severity', s) })
}
const findingColumns = [
  { title: '严重级', key: 'severity', width: 90, render: (r) => sevTag(r.severity) },
  { title: '维度', key: 'dimension', width: 90, render: (r) => enumText('findingDimension', r.dimension) },
  { title: '类型', key: 'type', width: 150, ellipsis: { tooltip: true }, render: (r) => enumText('findingType', r.type) },
  { title: 'Agent', key: 'agentKey', width: 100, ellipsis: { tooltip: true } },
  { title: '消息', key: 'message', minWidth: 220, ellipsis: { tooltip: true }, render: (r) => h('span', { title: '点击查看证据详情' }, r.message) },
  { title: 'Trace', key: 'traceId', width: 160, ellipsis: { tooltip: true }, render: (r) => r.traceId
    ? h('span', { class: 'mono link', style: 'color:var(--primary);cursor:pointer', title: r.traceId, onClick: () => router.push('/traces/' + encodeURIComponent(r.traceId)) }, r.traceId) : '—' },
  { title: '时间', key: 'createdAt', width: 150, render: (r) => h('span', { class: 'num', style: 'font-size:11.5px' }, r.createdAt ? String(r.createdAt).replace('T', ' ').slice(0, 19) : '—') }
]
function findingRowClass(r) {
  return r.severity === 'CRITICAL' ? 'sev-critical' : r.severity === 'HIGH' ? 'sev-high' : ''
}
// ---------- 异常发现详情抽屉 ----------
const showFinding = ref(false), findingDetail = ref(null)
function openFinding(row) {
  findingDetail.value = row
  showFinding.value = true
}
function onStatusChange(v) { statusFilter.value = v; incidentPagination.page = 1; loadIncidents() }

async function doDeleteMonitor(r) {
  try {
    await api.deleteMonitor(r.id)
    message.success(`已删除规则「${r.monitorKey}」（历史告警保留）`)
    loadMonitors()
    refreshStats()
  } catch (e) { message.error(e.message) }
}
async function doToggle(r, enabled) {
  try {
    if (enabled) await api.enableMonitor(r.id); else await api.disableMonitor(r.id)
  } catch (e) { message.error(e.message) }
  loadMonitors()
}
// 规则试跑
const showDryRun = ref(false), dryRunLoading = ref(false)
const dryRunRule = ref(null), dryRunItems = ref([])
const dryRunColumns = [
  { title: 'Trace', key: 'traceId', width: 170, ellipsis: { tooltip: true }, render: (r) => h('span', { class: 'mono' }, r.traceId) },
  { title: '命中', key: 'matched', width: 80, render: (r) => h(NTag, { size: 'small', bordered: false, type: r.matched ? 'error' : 'success' }, { default: () => (r.matched ? '命中' : '通过') }) },
  { title: '违规详情', key: 'violations', ellipsis: { tooltip: true }, render: (r) => (r.violations || []).map((v) => v.message || JSON.stringify(v)).join('; ') || '—' }
]
async function openDryRun(r) {
  dryRunRule.value = r
  dryRunItems.value = []
  showDryRun.value = true
  dryRunLoading.value = true
  try {
    const items = await api.dryRunMonitor(r.id, 20)
    dryRunItems.value = items || []
  } catch (e) { message.error(e.message || '试跑失败') }
  finally { dryRunLoading.value = false }
}

async function doAck(r) {
  try { await api.incidentAck(r.id); message.success('已确认'); await refreshStats(); loadIncidents() } catch (e) { message.error(e.message) }
}
async function doResolve(r) {
  try { await api.incidentResolve(r.id); message.success('已恢复'); await refreshStats(); loadIncidents() } catch (e) { message.error(e.message) }
}

// ---------- 状态统计条 + 批量处置 ----------
const incidentStats = reactive({ total: 0, open: 0, acknowledged: 0, resolved: 0, critical: 0 })
const checkedIncidents = ref([])
const bulkLoading = ref(false)
async function refreshStats() {
  // 统计走后端按状态过滤的 total，避免只统计第一页导致数字偏小
  try {
    const [all, openFirst, acknowledged, resolved] = await Promise.all([
      api.incidents({ pageNumber: 1, pageSize: 1 }),
      api.incidents({ status: 'OPEN', pageNumber: 1, pageSize: 500 }),
      api.incidents({ status: 'ACKNOWLEDGED', pageNumber: 1, pageSize: 1 }),
      api.incidents({ status: 'RESOLVED', pageNumber: 1, pageSize: 1 })
    ])
    incidentStats.total = all.total ?? 0
    incidentStats.open = openFirst.total ?? 0
    incidentStats.acknowledged = acknowledged.total ?? 0
    incidentStats.resolved = resolved.total ?? 0
    let critical = (openFirst.records || []).filter((r) => r.severity === 'CRITICAL').length
    const pages = Math.ceil((openFirst.total || 0) / 500)
    for (let pageNo = 2; pageNo <= pages; pageNo++) {
      const p = await api.incidents({ status: 'OPEN', pageNumber: pageNo, pageSize: 500 })
      critical += (p.records || []).filter((r) => r.severity === 'CRITICAL').length
    }
    incidentStats.critical = critical
  } catch (e) { /* 统计失败不打断页面 */ }
}
function setStatus(v) {
  statusFilter.value = v
  checkedIncidents.value = []
  incidentPagination.page = 1
  loadIncidents()
}
async function bulkAction(kind) {
  const ids = [...checkedIncidents.value]
  if (!ids.length) return
  bulkLoading.value = true
  let ok = 0
  try {
    for (const id of ids) {
      try {
        if (kind === 'ack') await api.incidentAck(id)
        else await api.incidentResolve(id)
        ok++
      } catch (e) { /* 单条失败不影响其余 */ }
    }
    message.success(`已${kind === 'ack' ? '确认' : '恢复'} ${ok}/${ids.length} 条`)
    checkedIncidents.value = []
    await refreshStats()
    loadIncidents()
  } finally { bulkLoading.value = false }
}

function openCreate() {
  Object.assign(createForm, { monitorKey: '', agentKey: agentOptions.value[0]?.value || null, kind: 'VALIDATION', spanType: null, where: "status == 'ERROR'", metric: 'error_rate', thresholdValue: 0.1, windowMinutes: 10, severity: 'HIGH' })
  showCreate.value = true
}
async function doCreate() {
  if (!createForm.monitorKey.trim()) { message.error('请填写规则 Key'); return }
  if (!createForm.agentKey) { message.error('请选择作用 Agent'); return }
  if (createForm.kind === 'EVALUATION' && !createForm.evaluatorKey) { message.error('请选择评估器'); return }
  if (createForm.kind === 'EVALUATION' && !createForm.passRate && createForm.passRate !== 0) { message.error('请填写通过率阈值'); return }
  creating.value = true
  try {
    let spec
    if (createForm.kind === 'VALIDATION') {
      spec = { kind: 'VALIDATION', where: createForm.where }
      if (createForm.spanType) spec.spanType = createForm.spanType
    } else if (createForm.kind === 'EVALUATION') {
      if (!createForm.evaluatorKey) { message.error('请选择评估器'); creating.value = false; return }
      spec = { evaluatorKey: createForm.evaluatorKey, metric: 'pass_rate', op: '<', value: createForm.passRate, windowCount: 10 }
    } else {
      spec = { metric: createForm.metric, windowMinutes: createForm.windowMinutes, thresholdType: 'static', thresholdValue: createForm.thresholdValue, direction: 'above', consecutiveWindows: 1 }
    }
    await api.createMonitor({ agentKey: createForm.agentKey, monitorKey: createForm.monitorKey.trim(), kind: createForm.kind, spec, severity: createForm.severity })
    message.success('规则已创建并启用。建议点列表中的「试跑」对最近 Trace 验证命中；命中后进入「触发历史 / 异常发现」')
    showCreate.value = false
    loadMonitors()
  } catch (e) { message.error(e.message || '创建失败') }
  finally { creating.value = false }
}

onMounted(async () => {
  try {
    agentOptions.value = await fetchAgentOptions()
  } catch (e) { /* ignore */ }
  try {
    const evs = await api.evaluators({ pageNumber: 1, pageSize: 100 })
    evaluatorOptions.value = (evs.records || []).map((r) => ({ label: r.evaluatorKey, value: r.evaluatorKey }))
  } catch (e) { /* ignore */ }
  load()
  if (tab.value === 'history') loadDead()
})
</script>

<style>
/* Incident OPEN 行左侧严重级色条（表格渲染在 body 层，需非 scoped） */
.row-open td:first-child { box-shadow: inset 3px 0 0 var(--error); }
</style>

<style scoped>
/* 统计卡样式对齐「评估结果」：独立卡片 + 大数字 + 标签；可点击项主色描边表示选中 */
.stat-strip { display: flex; align-items: stretch; gap: 12px; }
.s-item { flex: 1; min-width: 0; display: flex; align-items: baseline; gap: 8px;
  background: var(--bg-surface); border: 1px solid var(--border); border-radius: var(--radius-card);
  padding: 12px 16px; cursor: pointer; transition: border-color .15s, background .15s; }
.s-item:hover { border-color: var(--primary); }
.s-item.active { border-color: var(--primary); background: var(--primary-bg); }
.s-item.static { cursor: default; }
.s-item.static:hover { border-color: var(--border); }
.s-item .s-v { font-size: 21px; font-weight: 600; line-height: 1.1; font-family: "JetBrains Mono", ui-monospace, Menlo, monospace; }
.s-item .s-k { font-size: 12px; color: var(--text-muted); white-space: nowrap; }
.stat-actions { display: flex; align-items: center; gap: 8px; margin-left: auto; }
.desc-list { display: grid; grid-template-columns: 90px 1fr; row-gap: 6px; font-size: 12.5px; }
.desc-list .k { color: var(--text-muted); }
</style>

<style scoped>
.page-head { display: flex; justify-content: space-between; align-items: center; }
.mt12 { margin-top: 12px; }
.filters { display: flex; align-items: center; gap: 12px; }

/* ---- where 表达式速查面板 ---- */
.help-panel { background: var(--bg-subtle); border-radius: 8px; padding: 12px 14px; }
.help-title { font-size: 12.5px; font-weight: 600; color: var(--text-primary); margin-bottom: 8px; }
.help-title .page-sub { font-weight: 400; margin-left: 6px; }
.field-chips { display: flex; flex-wrap: wrap; gap: 6px; }
.field-chip { font-family: "JetBrains Mono", ui-monospace, Menlo, monospace; font-size: 11.5px;
  color: var(--text-secondary); background: var(--bg-surface); border: 1px solid var(--border);
  border-radius: 5px; padding: 3px 9px; cursor: pointer; transition: all .15s; }
.field-chip:hover { color: var(--primary); border-color: var(--primary); background: var(--primary-bg); }
.metric-hint { margin-top: -6px; margin-bottom: 8px; line-height: 1.6; }
.syntax-list { background: var(--bg-surface); border: 1px solid var(--border); border-radius: 6px; padding: 4px 12px; }
.syntax-row { display: flex; align-items: baseline; gap: 12px; padding: 6px 0; border-bottom: 1px dashed var(--border); }
.syntax-row:last-child { border-bottom: none; }
.syntax-row code { flex: 0 0 46%; font-family: "JetBrains Mono", ui-monospace, Menlo, monospace;
  font-size: 11.5px; color: var(--text-primary); word-break: break-all; }
.syntax-row span { flex: 1; font-size: 11px; color: var(--text-muted); }
</style>
