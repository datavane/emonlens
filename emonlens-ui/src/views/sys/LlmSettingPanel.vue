<template>
  <div class="llm-panel">
    <n-card size="small" :loading="loading" title="LLM 端点（评估 / Playground 共用）">
      <template #header-extra>
        <n-tag size="small" :bordered="false" :type="form.source === 'DB' ? 'success' : 'default'">
          {{ form.source === 'DB' ? '运行时配置' : 'application.yml 默认' }}
        </n-tag>
      </template>
      <n-form label-placement="left" label-width="120" style="max-width: 760px">
        <n-form-item label="协议" required>
          <n-select v-model:value="form.protocol" :options="protocolOptions" style="width: 260px" />
          <span class="page-sub" style="margin-left:10px">{{ protocolHint }}</span>
        </n-form-item>
        <n-form-item label="Base URL" required>
          <n-input v-model:value="form.baseUrl" :placeholder="baseUrlPlaceholder" class="mono" />
        </n-form-item>
        <n-form-item label=" " :show-feedback="false">
          <span class="page-sub">
            填到 <code>/v1</code> 即可，系统按协议自动补 <code>/chat/completions</code> 或 <code>/messages</code>；
            也可直接粘贴完整端点。OpenCode Go 用
            <code>https://opencode.ai/inference/go/openai/v1</code>（OpenAI 协议）或
            <code>https://opencode.ai/inference/go/anthropic/v1</code>（Claude 模型），
            会话头 <code>x-opencode-session</code> 已由服务端自动发送。
          </span>
        </n-form-item>
        <n-form-item label="API Key">
          <n-input v-model:value="form.apiKey" type="password" show-password-on="click"
            :placeholder="form.apiKeyMasked ? '已保存：' + form.apiKeyMasked + '（输入新值覆盖，留空保持不变）' : 'sk-...'"
            class="mono" />
        </n-form-item>
        <n-form-item label="默认模型">
          <n-input v-model:value="form.model" placeholder="gpt-4o-mini / deepseek-chat / qwen-plus ..." class="mono" />
        </n-form-item>
        <n-form-item label="超时 (ms)">
          <n-input-number v-model:value="form.timeoutMs" :min="2000" :max="120000" :step="1000" style="width: 180px" />
        </n-form-item>
        <n-form-item label=" " :show-feedback="false">
          <div class="btn-row">
            <n-button size="small" type="primary" :loading="saving" @click="save">保存</n-button>
            <n-button size="small" :loading="testing" @click="test">测试连通性</n-button>
          </div>
        </n-form-item>
      </n-form>

      <n-alert v-if="testResult" size="small" :type="testResult.ok ? 'success' : 'error'" class="mt8" closable>
        <template #header>
          {{ testResult.ok
            ? '连通正常：' + (testResult.protocol || form.protocol) + ' · ' + (testResult.model || '') + ' · ' + testResult.latencyMs + ' ms'
            : '连通失败（' + (testResult.protocol || form.protocol) + ' 协议）' }}
        </template>
        <span class="mono" style="font-size: 12px">{{ testResult.ok ? '回复: ' + (testResult.reply || '（空）') : testResult.error }}</span>
      </n-alert>

      <div class="page-sub hint">
        保存后立即生效（无需重启）：Playground 试跑、LLM_JUDGE 评估器、数据集实验、自动评估均使用该端点。
        API Key 仅回显尾 4 位，留空表示沿用已保存值或 yml 配置。
      </div>
    </n-card>

    <n-card size="small" title="自动评估采样" class="mt12">
      <template #header-extra>
        <n-tag size="small" :bordered="false" :type="samplingForm.source === 'DB' ? 'success' : 'default'">
          {{ samplingForm.source === 'DB' ? '运行时配置' : 'yml 默认' }}
        </n-tag>
      </template>
      <n-form label-placement="left" label-width="120" style="max-width: 760px">
        <n-form-item label="自动评估">
          <n-switch v-model:value="samplingForm.autoRunEnabled" />
          <span class="page-sub" style="margin-left:10px">关闭后新完成的 trace 不再触发自动评估（手动补评/实验不受影响）</span>
        </n-form-item>
        <n-form-item label="采样比例">
          <n-input-number v-model:value="samplingForm.defaultRate" :min="0" :max="1" :step="0.01" style="width: 160px" />
          <span class="page-sub" style="margin-left:10px">0-1；0=仅评估含错误的 trace</span>
        </n-form-item>
        <n-form-item label="每小时上限">
          <n-input-number v-model:value="samplingForm.maxPerHour" :min="1" :max="100000" :step="100" style="width: 160px" />
        </n-form-item>
        <n-form-item label=" " :show-feedback="false">
          <n-button size="small" type="primary" :loading="savingSampling" @click="saveSampling">保存采样配置</n-button>
        </n-form-item>
      </n-form>
    </n-card>

    <n-card size="small" title="数据保留策略" class="mt12">
      <template #header-extra>
        <n-tag size="small" :bordered="false" :type="retentionForm.source === 'DB' ? 'success' : 'default'">
          {{ retentionForm.source === 'DB' ? '运行时配置' : 'yml 默认' }}
        </n-tag>
      </template>
      <n-form label-placement="left" label-width="120" style="max-width: 760px">
        <n-form-item label="Span 保留天数">
          <n-input-number v-model:value="retentionForm.spanDays" :min="0" :max="3650" :step="1" style="width: 160px" />
          <span class="page-sub" style="margin-left:10px">0 = 永久保留</span>
        </n-form-item>
        <n-form-item label="内容保留天数">
          <n-input-number v-model:value="retentionForm.contentDays" :min="0" :max="3650" :step="1" style="width: 160px" />
        </n-form-item>
        <n-form-item label="事件记录天数">
          <n-input-number v-model:value="retentionForm.eventDays" :min="0" :max="3650" :step="1" style="width: 160px" />
        </n-form-item>
        <n-form-item label=" " :show-feedback="false">
          <n-button size="small" type="primary" :loading="savingRetention" @click="saveRetention">保存保留策略</n-button>
        </n-form-item>
      </n-form>
    </n-card>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useMessage } from 'naive-ui'
import api from '@/api'

const message = useMessage()
const loading = ref(false), saving = ref(false), testing = ref(false)
const testResult = ref(null)
const protocolOptions = [
  { label: 'OpenAI 兼容（/chat/completions）', value: 'openai' },
  { label: 'Anthropic / Claude（/messages）', value: 'anthropic' }
]
const form = reactive({ protocol: 'openai', baseUrl: '', apiKey: '', model: '', timeoutMs: 20000, source: 'YML', apiKeyMasked: '' })
const protocolHint = computed(() => form.protocol === 'anthropic'
  ? 'Claude 原生 Messages API（x-api-key + anthropic-version）'
  : 'OpenAI 兼容 chat/completions（Bearer 鉴权），多数国产网关用这个')
const baseUrlPlaceholder = computed(() => form.protocol === 'anthropic'
  ? 'https://api.anthropic.com 或 https://opencode.ai/inference/go/anthropic/v1'
  : 'https://api.openai.com 或 https://opencode.ai/inference/go/openai/v1')

async function load() {
  loading.value = true
  try {
    const s = await api.getEvalLlmSetting()
    Object.assign(form, {
      protocol: s.protocol || 'openai',
      baseUrl: s.baseUrl || '', model: s.model || '', timeoutMs: s.timeoutMs || 20000,
      source: s.source || 'YML', apiKeyMasked: s.apiKeyMasked || '', apiKey: ''
    })
  } catch (e) { message.error(e.message || '加载失败') }
  finally { loading.value = false }
}

async function save() {
  saving.value = true
  try {
    await api.saveEvalLlmSetting({
      protocol: form.protocol,
      baseUrl: form.baseUrl.trim(),
      apiKey: form.apiKey.trim() || undefined,
      model: form.model.trim() || undefined,
      timeoutMs: form.timeoutMs
    })
    message.success(form.baseUrl.trim() ? '已保存，立即生效' : '已清空运行时配置，回退 application.yml 默认')
    form.apiKey = ''
    load()
  } catch (e) { message.error(e.message || '保存失败') }
  finally { saving.value = false }
}

async function test() {
  testing.value = true
  testResult.value = null
  try {
    testResult.value = await api.testEvalLlmSetting({
      protocol: form.protocol,
      baseUrl: form.baseUrl.trim() || undefined,
      apiKey: form.apiKey.trim() || undefined,
      model: form.model.trim() || undefined,
      timeoutMs: form.timeoutMs
    })
  } catch (e) { testResult.value = { ok: false, protocol: form.protocol, error: e.message || '请求失败' } }
  finally { testing.value = false }
}

// ---------- 自动评估采样 ----------
const samplingForm = reactive({ defaultRate: 0.01, maxPerHour: 5000, autoRunEnabled: true, source: 'YML' })
const savingSampling = ref(false)
async function loadSampling() {
  try {
    const s = await api.getEvalSamplingSetting()
    Object.assign(samplingForm, {
      defaultRate: s.defaultRate, maxPerHour: s.maxPerHour,
      autoRunEnabled: !!s.autoRunEnabled, source: s.source || 'YML'
    })
  } catch (e) { message.error(e.message || '采样配置加载失败') }
}
async function saveSampling() {
  savingSampling.value = true
  try {
    await api.saveEvalSamplingSetting({
      defaultRate: samplingForm.defaultRate,
      maxPerHour: samplingForm.maxPerHour,
      autoRunEnabled: samplingForm.autoRunEnabled
    })
    message.success('采样配置已保存，立即生效')
    loadSampling()
  } catch (e) { message.error(e.message || '保存失败') }
  finally { savingSampling.value = false }
}

// ---------- 数据保留策略 ----------
const retentionForm = reactive({ spanDays: 30, contentDays: 7, eventDays: 7, source: 'YML' })
const savingRetention = ref(false)
async function loadRetention() {
  try {
    const s = await api.getRetentionSetting()
    Object.assign(retentionForm, { spanDays: s.spanDays, contentDays: s.contentDays, eventDays: s.eventDays, source: s.source || 'YML' })
  } catch (e) { message.error(e.message || '保留策略加载失败') }
}
async function saveRetention() {
  savingRetention.value = true
  try {
    await api.saveRetentionSetting({
      spanDays: retentionForm.spanDays,
      contentDays: retentionForm.contentDays,
      eventDays: retentionForm.eventDays
    })
    message.success('保留策略已保存，下轮清理生效')
    loadRetention()
  } catch (e) { message.error(e.message || '保存失败') }
  finally { savingRetention.value = false }
}

onMounted(() => { load(); loadSampling(); loadRetention() })
</script>

<style scoped>
/* 内容限宽：避免宽屏下表单只占左侧、右侧留下大片空白 */
.llm-panel { max-width: 980px; }
.mt8 { margin-top: 8px; }
.hint { margin-top: 10px; line-height: 1.6; }
.btn-row { display: flex; gap: 8px; }
.mono { font-family: "JetBrains Mono", ui-monospace, Menlo, monospace; }
</style>
