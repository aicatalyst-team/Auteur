<script setup lang="ts">
/**
 * 工具审批卡片(HITL gate)。
 *
 * 收到 SSE tool_approval_request 时由 Chat.vue 在消息流中插入一张本卡。
 * 用户必须显式点"批准"或"拒绝";60 秒不响应后端会超时算拒绝。
 *
 * Props:
 *   - request:      后端发的 tool_approval_request 数据(id, name, argsJson, risk, timeoutSeconds, diff?)
 *   - submitting:   父组件正在调 approveTool API 中,按钮置灰
 *   - submitError:  上次提交失败的错误信息;非空时把本地 decision 清掉,让用户能重试
 *
 * Events:
 *   - decided: 用户做出决定(approved: bool, reason?: string),由父组件调 approveTool API
 *
 * 时序保护(避免与服务端 60s race):
 *   - 客户端剩余 ≤ 2s 时主动禁用按钮 — 留出 ≥1 RTT 余量,避免用户在边界点击却撞上服务端 orTimeout
 *   - 剩余 = 0 时只切换显示状态,不主动 emit decided(让服务端自己 orTimeout 发 REJECTED tool_result,
 *     避免客户端、服务端各自时钟漂移产生"UI 已批准但后端已拒"的相反状态)
 */
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { ShieldAlert, Check, X, Wrench, Clock, ChevronDown, ChevronRight, Loader2 } from 'lucide-vue-next'
import DiffView from './DiffView.vue'

interface ApprovalDiff {
  fieldName: string
  before: string
  after: string
  summary?: string | null
}

interface ApprovalRequest {
  id: string         // toolCallId
  name: string       // tool 名
  argsJson: string
  risk: 'WRITE' | 'ACTION' | 'READ'
  timeoutSeconds: number
  diff?: ApprovalDiff | null
}

const props = defineProps<{
  request: ApprovalRequest
  submitting?: boolean
  submitError?: string | null
}>()

const emit = defineEmits<{
  (e: 'decided', payload: { toolCallId: string; approved: boolean; reason?: string }): void
}>()

const decision = ref<null | 'approved' | 'rejected' | 'timeout'>(null)
const reason = ref('')
const showReason = ref(false)
const showRawArgs = ref(false)
const remaining = ref(props.request.timeoutSeconds)
let timer: number | null = null

// 服务端报错回来时,把本地 decision 清掉,让按钮重新可点 — 用户可以重试
watch(
  () => props.submitError,
  (err) => {
    if (err) decision.value = null
  },
)

onMounted(() => {
  timer = window.setInterval(() => {
    remaining.value = Math.max(0, remaining.value - 1)
    if (remaining.value === 0 && decision.value === null) {
      // 切到 timeout 显示状态,不主动 emit — 服务端自己的 orTimeout 会发 REJECTED tool_result,
      // 与客户端争夺谁先到 0 反而会引入 race(见 ApprovalGate 单 future + cancelSession 设计)。
      decision.value = 'timeout'
      stopTimer()
    }
  }, 1000)
})

onBeforeUnmount(stopTimer)

function stopTimer() {
  if (timer != null) {
    clearInterval(timer)
    timer = null
  }
}

// 按钮锁:已决定 / 父组件提交中 / 倒计时 ≤ 2s(留 RTT 余量,避免点了刚好撞上服务端超时)
const buttonsLocked = computed(
  () => decision.value !== null || (props.submitting ?? false) || remaining.value <= 2,
)

function approve() {
  if (buttonsLocked.value) return
  decision.value = 'approved'
  stopTimer()
  emit('decided', { toolCallId: props.request.id, approved: true })
}

function reject() {
  if (buttonsLocked.value) return
  decision.value = 'rejected'
  stopTimer()
  emit('decided', { toolCallId: props.request.id, approved: false, reason: reason.value || undefined })
}

const prettyArgs = computed(() => {
  if (!props.request.argsJson) return ''
  try {
    return JSON.stringify(JSON.parse(props.request.argsJson), null, 2)
  } catch {
    return props.request.argsJson
  }
})

const hasDiff = computed(() => props.request.diff != null)

// Tailwind 不扫描动态拼接的类名,所以全部用字面字符串
const containerClass = computed(() => {
  if (decision.value !== null) return 'border-border-subtle bg-surface-secondary'
  return props.request.risk === 'ACTION'
    ? 'border-status-failed/50 bg-status-failed/5'
    : 'border-status-running/50 bg-status-running/5'
})
const iconClass = computed(() => {
  if (decision.value !== null) return 'text-text-muted'
  return props.request.risk === 'ACTION' ? 'text-status-failed' : 'text-status-running'
})
const chipClass = computed(() => {
  if (decision.value !== null) return 'bg-surface-tertiary text-text-muted'
  return props.request.risk === 'ACTION'
    ? 'bg-status-failed/15 text-status-failed'
    : 'bg-status-running/15 text-status-running'
})
const riskLabel = computed(() => (props.request.risk === 'ACTION' ? '动作(可能产生成本)' : '写入(改配置/预设/内容)'))
</script>

<template>
  <div class="rounded-lg border-2 px-4 py-3" :class="containerClass">
    <div class="flex items-start gap-3">
      <ShieldAlert :size="18" class="shrink-0 mt-0.5" :class="iconClass" />
      <div class="flex-1 min-w-0">
        <div class="text-sm font-semibold text-text-primary flex items-center gap-2 flex-wrap">
          <span>需要批准</span>
          <span class="chip text-[10px]" :class="chipClass">{{ riskLabel }}</span>
        </div>
        <div class="text-xs text-text-secondary mt-1 flex items-center gap-1">
          <Wrench :size="11" /> <span class="font-mono">{{ request.name }}</span>
        </div>

        <!-- 有 diff 时优先展示 DiffView -->
        <div v-if="hasDiff && request.diff" class="mt-2">
          <DiffView
            :field-name="request.diff.fieldName"
            :before="request.diff.before"
            :after="request.diff.after"
            :summary="request.diff.summary"
          />
          <button
            type="button"
            class="mt-1 flex items-center gap-1 text-[10px] text-text-muted hover:text-text-primary"
            @click="showRawArgs = !showRawArgs"
          >
            <component :is="showRawArgs ? ChevronDown : ChevronRight" :size="10" />
            原始参数 (JSON)
          </button>
          <pre
            v-if="showRawArgs && prettyArgs"
            class="mt-1 bg-surface-primary/50 rounded p-2 overflow-x-auto text-[10px] text-text-secondary max-h-32"
            >{{ prettyArgs }}</pre
          >
        </div>

        <!-- 无 diff:展示完整 args -->
        <pre
          v-else-if="prettyArgs"
          class="mt-2 bg-surface-primary/50 rounded p-2 overflow-x-auto text-[11px] text-text-secondary max-h-48"
          >{{ prettyArgs }}</pre
        >

        <!-- 上一次提交错误,允许重试 -->
        <div
          v-if="submitError && decision === null"
          class="mt-2 text-xs text-status-failed bg-status-failed/10 rounded px-2 py-1"
        >
          ✗ {{ submitError }}(可再次点击决定按钮重试)
        </div>

        <!-- 决定区 -->
        <div v-if="decision === null" class="mt-3 flex items-center gap-2 flex-wrap">
          <button class="btn-primary" :disabled="buttonsLocked" @click="approve">
            <Check :size="14" /> 批准并执行
          </button>
          <button class="btn-ghost" :disabled="buttonsLocked" @click="showReason = !showReason">
            <X :size="14" /> 拒绝
          </button>
          <span class="ml-auto flex items-center gap-1 text-[10px] text-text-muted">
            <Clock :size="10" />
            <span v-if="remaining > 2">剩余 {{ remaining }}s</span>
            <span v-else-if="remaining > 0" class="text-status-failed">剩余 {{ remaining }}s,即将超时</span>
            <span v-else class="text-status-failed">服务器即将超时</span>
          </span>
        </div>
        <div v-if="decision === null && showReason" class="mt-2 flex gap-2">
          <input
            v-model="reason"
            type="text"
            placeholder="拒绝理由(可选,会回灌给 LLM 让它换思路)"
            class="flex-1 bg-surface-primary border border-border-default rounded px-2 py-1 text-xs"
            :disabled="buttonsLocked"
            @keydown.enter="reject"
          />
          <button class="btn-danger" :disabled="buttonsLocked" @click="reject">确认拒绝</button>
        </div>

        <!-- 已决定 / 提交中状态 -->
        <div v-else class="mt-2 text-xs flex items-center gap-1.5">
          <Loader2 v-if="submitting" :size="12" class="animate-spin text-text-muted" />
          <span v-if="decision === 'approved'" class="text-status-done">
            <template v-if="submitting">⌛ 已提交,等服务器确认…</template>
            <template v-else>✓ 已批准,正在执行…</template>
          </span>
          <span v-else-if="decision === 'rejected'" class="text-status-failed">
            <template v-if="submitting">⌛ 已提交,等服务器确认…</template>
            <template v-else>✗ 已拒绝<span v-if="reason"> · {{ reason }}</span></template>
          </span>
          <span v-else class="text-text-muted">⏱ 已超时,服务端会自动按拒绝处理</span>
        </div>
      </div>
    </div>
  </div>
</template>
