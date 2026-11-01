<template>
  <div class="panel-root">

    <div class="notify-top">
      <!-- 通知渠道 -->
      <n-card size="small" :title="'通知渠道（' + ((senders || []).length) + '）'">
        <template #header-extra>
          <n-button type="primary" size="small" ghost @click="openSender">＋ 新建渠道</n-button>
        </template>
        <n-data-table :columns="senderColumns" :data="senders" size="small" :bordered="false" :loading="loading" :max-height="240" />
      </n-card>

      <!-- 通知模板 -->
      <n-card size="small" :title="'通知模板（' + ((templates || []).length) + '）'">
        <template #header-extra>
          <n-button type="primary" size="small" ghost @click="openTemplate">＋ 新建模板</n-button>
        </template>
        <n-data-table :columns="templateColumns" :data="templates" size="small" :bordered="false" :loading="loading" :max-height="240" />
      </n-card>
    </div>

    <!-- 发送记录 -->
    <n-card size="small" class="mt12" title="发送记录">
      <n-data-table :columns="recordColumns" :data="records" size="small" :bordered="false" remote
        :pagination="pagination" :loading="loading" :max-height="320" />
    </n-card>

    <!-- 新建渠道 -->
    <Modal v-model:show="showSender" :width="560" title="新建通知渠道">
      <n-form label-placement="top">
        <n-form-item label="渠道类型" required>
          <n-select v-model:value="senderForm.channel" :options="[
            { label: 'EMAIL（邮件）', value: 'EMAIL' },
            { label: 'WEBHOOK（Webhook）', value: 'WEBHOOK' }
          ]" />
        </n-form-item>
        <n-form-item label="名称" required>
          <n-input v-model:value="senderForm.name" placeholder="如 运维邮件组" />
        </n-form-item>
        <n-form-item label="配置（JSON）" required>
          <n-input v-model:value="senderForm.config" type="textarea" :rows="6" class="mono"
            :placeholder="senderPlaceholders[senderForm.channel]" />
        </n-form-item>
        <div class="page-sub">JSON 字段说明：{{ configHint }}</div>
      </n-form>
      <template #footer>
        <n-button size="small" :disabled="senderSubmitting" @click="showSender = false">取消</n-button>
        <n-button type="primary" size="small" :loading="senderSubmitting" @click="doCreateSender">创建</n-button>
      </template>
    </Modal>

    <!-- 新建/编辑模板 -->
    <Modal v-model:show="showTemplate" :width="640" :title="editingTemplateId ? '编辑模板' : '新建模板'">
      <n-form label-placement="top">
        <div class="tpl-row">
          <n-form-item label="模板 code（前缀决定匹配）" required>
            <n-input v-model:value="templateForm.code" placeholder="incident-default" :disabled="!!editingTemplateId" />
          </n-form-item>
          <n-form-item label="渠道" required>
            <n-select v-model:value="templateForm.channel" :options="[
              { label: '邮件（EMAIL）', value: 'EMAIL' },
              { label: 'Webhook', value: 'WEBHOOK' }
            ]" />
          </n-form-item>
        </div>
        <n-form-item label="名称" required>
          <n-input v-model:value="templateForm.name" placeholder="Incident 通知（邮件）" />
        </n-form-item>
        <n-form-item label="主题（EMAIL 必填，WEBHOOK 留空）">
          <n-input v-model:value="templateForm.subject" placeholder="EmonLens Incident 通知" />
        </n-form-item>
        <n-form-item label="内容（支持变量）" required>
          <n-input v-model:value="templateForm.content" type="textarea" :rows="5" class="mono"
            placeholder="Agent {{agent}} 触发 {{severity}} 级 Incident：{{title}}" />
        </n-form-item>
        <div class="var-hint">
          <span class="page-sub">可用变量：</span>
          <n-tag v-for="v in TEMPLATE_VARS" :key="v" size="tiny" :bordered="false" class="mono var-chip">&#123;&#123;{{ v }}&#125;&#125;</n-tag>
        </div>
        <div class="tpl-preview">
          <div class="page-sub" style="margin-bottom:4px">实时预览（示例变量）：</div>
          <div v-if="templateForm.subject" class="prev-line"><span class="k">主题</span><span>{{ renderPreview(templateForm.subject) }}</span></div>
          <div class="prev-line"><span class="k">内容</span><span>{{ renderPreview(templateForm.content) || '—' }}</span></div>
        </div>
      </n-form>
      <template #footer>
        <n-button size="small" :disabled="templateSubmitting" @click="showTemplate = false">取消</n-button>
        <n-button type="primary" size="small" :loading="templateSubmitting" @click="doSaveTemplate">保存</n-button>
      </template>
    </Modal>
    <!-- 测试发送结果 -->
    <Modal v-model:show="showTestResult" :width="560" :title="'测试发送 · ' + testSenderName">
      <template v-if="testResultRow">
        <n-alert size="small" :type="testResultRow.ok ? 'success' : 'error'" class="mb8">
          {{ testResultRow.ok ? '测试通知已发送' : '发送失败：' + (testResultRow.error || '未知错误') }}
        </n-alert>
        <div class="desc-list" style="margin-bottom:10px">
          <span class="k">渲染主题</span><span>{{ testResultRow.subject || '（空）' }}</span>
          <span class="k" v-if="testResultRow.ok">送达目标</span><span v-if="testResultRow.ok" class="mono">{{ testResultRow.target || '—' }}</span>
        </div>
        <CodeBlock :content="testResultRow.content || ''" label="渲染内容" :max-height="220" />
      </template>
    </Modal>
  </div>
</template>

<script setup>
import { computed, h, onMounted, reactive, ref } from 'vue'
import { NButton, NPopconfirm, NTag, useMessage } from 'naive-ui'
import api from '@/api'
import { enumText } from '@/common/i18n'
import CodeBlock from '@/components/CodeBlock.vue'

const message = useMessage()
const senders = ref([]), templates = ref([]), records = ref([]), loading = ref(false)
const senderSubmitting = ref(false), templateSubmitting = ref(false), deleteLoadingId = ref('')
const showSender = ref(false), showTemplate = ref(false)
const senderForm = reactive({ channel: 'EMAIL', name: '', config: '' })
const senderPlaceholders = {
  EMAIL: '{"host":"smtp.x.com","port":465,"ssl":true,"username":"...","password":"...","from":"...","to":"ops@x.com"}',
  WEBHOOK: '{"url":"https://...","secret":"...","keyVersion":"v1"}'
}
const configHint = computed(() => senderForm.channel === 'EMAIL'
  ? 'host SMTP 主机 · port 端口 · ssl 是否 TLS · username/password 认证 · from 发件人 · to 收件人（多个逗号分隔）'
  : 'url 回调地址 · secret 签名密钥（HMAC-SHA256） · keyVersion 密钥版本')
const templateForm = reactive({ code: '', name: '', channel: 'EMAIL', subject: '', content: '' })
const TEMPLATE_VARS = ['agent', 'severity', 'title', 'message', 'traceId']
const SAMPLE_VARS = {
  agent: 'support-agent', severity: 'HIGH',
  title: 'LLM 调用错误（support-agent）',
  message: 'LLM call failed: upstream timeout after 20s',
  traceId: 'trace-8f3a2b'
}
function renderPreview(tpl) {
  return String(tpl || '').replace(/\{\{\s*([a-zA-Z0-9_]+)\s*}}/g, (m, k) =>
    SAMPLE_VARS[k] != null ? SAMPLE_VARS[k] : m)
}
const pagination = reactive({
  page: 1, pageSize: 10, itemCount: 0, showSizePicker: true, pageSizes: [10, 20, 50, 100],
  prefix: ({ itemCount }) => '共 ' + itemCount + ' 条',
  onChange: (page) => { pagination.page = page; load() },
  onUpdatePageSize: (pageSize) => { pagination.pageSize = pageSize; pagination.page = 1; load() }
})

let editingTemplateId = null

const senderColumns = [
  { title: '渠道', key: 'channel', width: 100, render: (r) => h(NTag, { size: 'small', bordered: false }, { default: () => enumText('channel', r.channel) }) },
  { title: '名称', key: 'name', minWidth: 140, ellipsis: { tooltip: true } },
  { title: '状态', key: 'status', width: 90, render: (r) => h(NTag, { size: 'small', bordered: false, type: r.status === 'ACTIVE' ? 'success' : 'default' }, { default: () => (r.status === 'ACTIVE' ? '启用' : '停用') }) },
  { title: '操作', key: 'ops', width: 130, render: (r) => [
      h(NButton, { size: 'tiny', type: 'primary', ghost: true, loading: testLoadingId.value === r.id,
        disabled: !!deleteLoadingId.value || r.status !== 'ACTIVE',
        title: '用示例 Incident 变量渲染模板并发送一条测试通知',
        onClick: () => doTestSender(r) }, { default: () => '测试' }),
      h(NPopconfirm, { onPositiveClick: () => doDeleteSender(r) }, {
        trigger: () => h(NButton, { size: 'tiny', style: 'margin-left:4px', loading: deleteLoadingId.value === r.id,
          disabled: !!deleteLoadingId.value || !!testLoadingId.value }, { default: () => '删除' }),
        default: () => `确认删除渠道「${r.name}」？`
      })
    ] }
]
const templateColumns = [
  { title: 'code', key: 'code', width: 150, ellipsis: { tooltip: true }, render: (r) => h('span', { class: 'mono' }, r.code) },
  { title: '名称', key: 'name', minWidth: 130, ellipsis: { tooltip: true } },
  { title: '渠道', key: 'channel', width: 80, render: (r) => h(NTag, { size: 'small', bordered: false }, { default: () => enumText('channel', r.channel) }) },
  { title: '操作', key: 'ops', width: 60, render: (r) =>
      h(NButton, { size: 'tiny', onClick: () => openEditTemplate(r) }, { default: () => '编辑' }) }
]
const recordColumns = [
  { title: '时间', key: 'createdAt', width: 160, render: (r) => h('span', { class: 'num', style: 'font-size:11.5px' }, fmtTs(r.createdAt)) },
  { title: '渠道', key: 'channel', width: 90, render: (r) => h(NTag, { size: 'small', bordered: false }, { default: () => enumText('channel', r.channel) }) },
  { title: '事件', key: 'eventType', width: 170 },
  { title: '状态', key: 'status', width: 90, render: (r) => h(NTag, { size: 'small', bordered: false, type: r.status === 'SUCCESS' ? 'success' : r.status === 'DEAD' ? 'error' : 'warning' }, { default: () => (r.status === 'SUCCESS' ? '成功' : r.status === 'DEAD' ? '失败' : '重试中') }) },
  { title: '错误', key: 'error', ellipsis: { tooltip: true }, render: (r) => r.error || '—' }
]

function fmtTs(s) {
  if (!s) return '—'
  return String(s).replace('T', ' ').slice(0, 19)
}

async function load() {
  loading.value = true
  try {
    senders.value = (await api.notifySenders()) || []
    templates.value = (await api.notifyTemplates()) || []
    const page = await api.notifyRecords({ pageNumber: pagination.page, pageSize: pagination.pageSize })
    records.value = page.records || []
    pagination.itemCount = page.total || 0
  } catch (e) { message.error(e.message || '通知配置加载失败') } finally { loading.value = false }
}
function openSender() {
  Object.assign(senderForm, { channel: 'EMAIL', name: '', config: '' })
  showSender.value = true
}
function openTemplate() {
  editingTemplateId = null
  Object.assign(templateForm, { code: '', name: '', channel: 'EMAIL', subject: '', content: '' })
  showTemplate.value = true
}
function openEditTemplate(r) {
  editingTemplateId = r.id
  Object.assign(templateForm, { code: r.code, name: r.name, channel: r.channel, subject: r.subject || '', content: r.content })
  showTemplate.value = true
}
async function doCreateSender() {
  if (!senderForm.name.trim()) { message.error('请填写渠道名称'); return }
  try { JSON.parse(senderForm.config) } catch (e) { message.error('配置不是合法 JSON：' + e.message); return }
  senderSubmitting.value = true
  try { await api.createNotifySender({ ...senderForm }); message.success('渠道已创建'); showSender.value = false; load() }
  catch (e) { message.error(e.message) }
  finally { senderSubmitting.value = false }
}
async function doDeleteSender(r) {
  deleteLoadingId.value = r.id
  try { await api.deleteNotifySender(r.id); message.success('已删除'); load() } catch (e) { message.error(e.message) }
  finally { deleteLoadingId.value = '' }
}

// ---------- 渠道测试发送 ----------
const testLoadingId = ref('')
const showTestResult = ref(false), testResultRow = ref(null), testSenderName = ref('')
async function doTestSender(r) {
  testLoadingId.value = r.id
  testSenderName.value = r.name
  try {
    const res = await api.notifyTestSender(r.id)
    testResultRow.value = res
    showTestResult.value = true
  } catch (e) { message.error(e.message) }
  finally { testLoadingId.value = '' }
}
async function doSaveTemplate() {
  if (!templateForm.code.trim()) { message.error('请填写模板 code'); return }
  if (!templateForm.content.trim()) { message.error('请填写模板内容'); return }
  templateSubmitting.value = true
  try {
    if (editingTemplateId) await api.updateNotifyTemplate(editingTemplateId, { ...templateForm })
    else await api.createNotifyTemplate({ ...templateForm })
    message.success('已保存'); showTemplate.value = false; editingTemplateId = null; load()
  } catch (e) { message.error(e.message) }
  finally { templateSubmitting.value = false }
}
onMounted(load)
</script>

<style scoped>
.notify-top { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 12px; }
@media (max-width: 1100px) { .notify-top { grid-template-columns: 1fr; } }
.mt12 { margin-top: 12px; }
.mono { font-family: "JetBrains Mono", ui-monospace, Menlo, monospace; }
.page-sub { font-size: 11.5px; color: var(--text-muted); }
.tpl-row { display: grid; grid-template-columns: 1fr 140px; gap: 12px; }
.var-hint { display: flex; align-items: center; gap: 4px; flex-wrap: wrap; }
.var-chip { color: var(--primary); background: var(--primary-bg); }
.desc-list { display: grid; grid-template-columns: 90px 1fr; row-gap: 6px; font-size: 12.5px; }
.desc-list .k { color: var(--text-muted); }
.mb8 { margin-bottom: 8px; }
.tpl-preview { margin-top: 10px; padding: 8px 10px; background: var(--bg-subtle); border-radius: 6px; }
.prev-line { display: flex; gap: 8px; font-size: 12.5px; line-height: 1.6; }
.prev-line .k { flex: 0 0 34px; color: var(--text-muted); }
</style>
