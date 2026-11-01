<template>
  <section class="flow-card">
    <div class="flow-head">
      <div>
        <div class="flow-title">从样本到准入结论</div>
        <div class="flow-desc">先按 1 → 4 跑完实验，再完成 Judge 校准、Gate 和质量案例闭环；结果可回溯、可对比。</div>
      </div>
      <n-tag v-if="runningRun" size="small" type="info" :bordered="false">正在运行</n-tag>
      <n-tag v-else-if="latestRun" size="small" :type="latestType" :bordered="false">
        最近一次 · {{ latestStatus }}
      </n-tag>
    </div>

    <div class="flow-steps">
      <div class="flow-step" :class="stepClass(1)">
        <div class="flow-num">1</div>
        <div class="flow-body">
          <div class="flow-label">准备样本</div>
          <div class="flow-meta">
            <template v-if="itemCount">{{ activeItemCount }} / {{ itemCount }} 条可用</template>
            <template v-else>还没有样本</template>
          </div>
          <n-button text size="tiny" type="primary" @click="$emit('manage-items')">
            {{ itemCount ? '管理样本' : '添加样本' }}
          </n-button>
        </div>
      </div>

      <div class="flow-step" :class="stepClass(2)">
        <div class="flow-num">2</div>
        <div class="flow-body">
          <div class="flow-label">选择评估器</div>
          <div class="flow-meta">
            <template v-if="evaluatorCount">{{ evaluatorCount }} 个可用</template>
            <template v-else>还没有评估器</template>
          </div>
          <n-button text size="tiny" type="primary" @click="$emit('manage-evaluators')">
            {{ evaluatorCount ? '管理评估器' : '创建评估器' }}
          </n-button>
        </div>
      </div>

      <div class="flow-step" :class="stepClass(3)">
        <div class="flow-num">3</div>
        <div class="flow-body">
          <div class="flow-label">运行实验</div>
          <div class="flow-meta">{{ blocker || '选择评估器并提交' }}</div>
          <n-button text size="tiny" type="primary" :disabled="!canRun" :loading="creating" @click="$emit('run')">
            运行实验
          </n-button>
        </div>
      </div>

      <div class="flow-step" :class="stepClass(4)">
        <div class="flow-num">4</div>
        <div class="flow-body">
          <div class="flow-label">看结果与证据</div>
          <div class="flow-meta">
            <template v-if="latestRun">
              {{ latestStatus }}<template v-if="latestRun.passRate != null"> · 通过率 {{ latestRate }}</template>
            </template>
            <template v-else>运行后在此查看</template>
          </div>
          <n-button v-if="latestRun" text size="tiny" type="primary" @click="$emit('view-result')">
            查看最近结果
          </n-button>
          <span v-else class="flow-muted">逐样本结论 + 任务账本</span>
        </div>
      </div>
    </div>

    <div class="flow-closure">
      <div class="closure-step" :class="calibrationClass">
        <div class="closure-head">
          <span class="closure-label">5 · Judge 校准</span>
          <n-tag size="tiny" :bordered="false" :type="calibrationType">{{ calibrationText }}</n-tag>
        </div>
        <div class="closure-meta">{{ calibrationDetail || '含 LLM Judge 的运行需人工盲审；无 Judge 可跳过。' }}</div>
        <n-button v-if="latestRun" text size="tiny" type="primary" @click="$emit('view-report')">
          查看校准与报告
        </n-button>
      </div>
      <div class="closure-step" :class="gateClass">
        <div class="closure-head">
          <span class="closure-label">6 · 准入 Gate</span>
          <n-tag size="tiny" :bordered="false" :type="gateType">{{ gateText }}</n-tag>
        </div>
        <div class="closure-meta">{{ gateDetail || 'Gate 只消费已结束、绑定 revision/version 的 run；缺证据返回阻断。' }}</div>
        <n-button v-if="latestRun" text size="tiny" type="primary" @click="$emit('view-report')">
          查看 Gate 检查项
        </n-button>
      </div>
      <div class="closure-step" :class="caseClass">
        <div class="closure-head">
          <span class="closure-label">7 · 质量案例</span>
          <n-tag size="tiny" :bordered="false" :type="openCaseCount ? 'warning' : 'default'">
            {{ openCaseCount ? openCaseCount + ' 个待处理' : '无待处理' }}
          </n-tag>
        </div>
        <div class="closure-meta">把未通过 / 无法判定登记为案例；修复后用回归 run 的通过 Gate 关闭。</div>
        <n-button text size="tiny" type="primary" @click="$emit('open-cases')">打开质量案例</n-button>
      </div>
    </div>
  </section>
</template>

<script setup>
import { computed } from 'vue'
import { NButton, NTag } from 'naive-ui'

const props = defineProps({
  itemCount: { type: Number, default: 0 },
  activeItemCount: { type: Number, default: 0 },
  evaluatorCount: { type: Number, default: 0 },
  latestRun: { type: Object, default: null },
  runningRun: { type: Object, default: null },
  canRun: { type: Boolean, default: false },
  blocker: { type: String, default: '' },
  creating: { type: Boolean, default: false },
  calibrationStatus: { type: String, default: '' },
  calibrationDetail: { type: String, default: '' },
  gateDecision: { type: String, default: '' },
  gateUsable: { type: Boolean, default: false },
  gateDetail: { type: String, default: '' },
  openCaseCount: { type: Number, default: 0 }
})
defineEmits(['manage-items', 'manage-evaluators', 'run', 'view-result', 'view-report', 'open-cases'])

const latestStatus = computed(() => ({
  COMPLETED: '执行完成',
  PARTIAL_FAILED: '部分失败',
  FAILED: '执行失败',
  INTERRUPTED: '已中断',
  CANCELLED: '已取消',
  PENDING: '排队中',
  RUNNING: '执行中'
}[props.latestRun?.status] || props.latestRun?.status || '—'))
const latestType = computed(() => ({
  COMPLETED: 'success',
  PARTIAL_FAILED: 'warning',
  FAILED: 'error',
  INTERRUPTED: 'warning',
  CANCELLED: 'default',
  PENDING: 'info',
  RUNNING: 'info'
}[props.latestRun?.status] || 'default'))
const latestRate = computed(() => props.latestRun?.passRate == null ? '—' : props.latestRun.passRate + '%')
const calibrationText = computed(() => {
  if (props.calibrationStatus) {
    return ({ OK: '已校准', IN_PROGRESS: '校准中', INSUFFICIENT_SAMPLE: '样本不足', LOW_AGREEMENT: '一致率低' }[props.calibrationStatus] || props.calibrationStatus)
  }
  return props.latestRun ? '无 Judge / 待校准' : '待运行'
})
const calibrationType = computed(() => props.calibrationStatus === 'OK' ? 'success'
  : props.calibrationStatus === 'LOW_AGREEMENT' ? 'error'
  : props.calibrationStatus ? 'warning' : 'default')
const calibrationClass = computed(() => props.calibrationStatus === 'OK' ? 'is-done'
  : props.calibrationStatus ? 'is-active' : 'is-todo')
const gateText = computed(() => props.gateDecision
  ? props.gateDecision + (props.gateUsable ? ' · 可用' : ' · 不可用')
  : (props.latestRun ? '待生成' : '待运行'))
const gateType = computed(() => props.gateDecision === 'PASS' && props.gateUsable ? 'success'
  : props.gateDecision === 'FAIL' ? 'error'
  : props.gateDecision ? 'warning' : 'default')
const gateClass = computed(() => props.gateDecision === 'PASS' && props.gateUsable ? 'is-done'
  : props.gateDecision ? 'is-active' : 'is-todo')
const caseClass = computed(() => props.openCaseCount ? 'is-active' : (props.latestRun ? 'is-done' : 'is-todo'))
function stepClass(n) {
  if (n === 1) return props.itemCount ? 'is-done' : 'is-active'
  if (n === 2) return props.evaluatorCount ? 'is-done' : (props.itemCount ? 'is-active' : 'is-todo')
  if (n === 3) return props.canRun ? 'is-active' : 'is-todo'
  return props.latestRun ? 'is-done' : 'is-todo'
}
</script>

<style scoped>
.flow-card {
  margin-top: 12px;
  padding: 16px;
  background: var(--bg-surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
}
.flow-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 14px; }
.flow-title { font-size: 14px; font-weight: 600; color: var(--text-primary); }
.flow-desc { margin-top: 2px; font-size: 12px; line-height: 18px; color: var(--text-muted); }
.flow-steps { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; }
.flow-step { position: relative; display: flex; gap: 9px; min-width: 0; padding: 10px; border: 1px solid var(--border); border-radius: 8px; background: var(--bg-subtle); }
.flow-step.is-active { border-color: var(--primary); background: var(--primary-bg); }
.flow-step.is-done { border-color: var(--border); }
.flow-num { flex: 0 0 22px; width: 22px; height: 22px; display: flex; align-items: center; justify-content: center; border-radius: 50%; background: var(--border); color: var(--text-secondary); font-size: 11px; font-weight: 700; }
.flow-step.is-active .flow-num { background: var(--primary); color: #fff; }
.flow-step.is-done .flow-num { background: var(--success); color: #fff; }
.flow-body { min-width: 0; display: flex; flex-direction: column; align-items: flex-start; gap: 3px; }
.flow-label { font-size: 12.5px; font-weight: 600; color: var(--text-primary); }
.flow-meta { min-height: 18px; font-size: 11.5px; line-height: 16px; color: var(--text-muted); }
.flow-muted { font-size: 11.5px; color: var(--text-muted); }
.flow-body :deep(.n-button) { padding: 0; height: 18px; font-size: 11.5px; }
.flow-closure { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; margin-top: 10px; padding-top: 10px; border-top: 1px dashed var(--border); }
.closure-step { min-width: 0; padding: 10px; border: 1px solid var(--border); border-radius: 8px; background: var(--bg-surface); }
.closure-step.is-active { border-color: var(--warn); background: var(--warn-bg, #fff8e6); }
.closure-step.is-done { border-color: var(--success); }
.closure-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.closure-label { font-size: 12.5px; font-weight: 600; color: var(--text-primary); }
.closure-meta { margin-top: 4px; min-height: 32px; font-size: 11.5px; line-height: 16px; color: var(--text-muted); }
.closure-step :deep(.n-button) { padding: 0; height: 18px; font-size: 11.5px; margin-top: 2px; }
@media (max-width: 900px) {
  .flow-steps { grid-template-columns: 1fr 1fr; }
  .flow-closure { grid-template-columns: 1fr; }
}
</style>
