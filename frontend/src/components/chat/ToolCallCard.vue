<script setup lang="ts">
/**
 * 工具调用结果卡片。默认折叠,点击 chip 展开看完整 args / result。
 * 状态可视化(toolStatus):
 *   - OK         绿勾,正常完成
 *   - ERROR      红框 + 警告图标,内部异常
 *   - REJECTED   橙框 + 拦截图标,用户审批拒绝(或 60s 超时按拒绝处理)
 *   - CANCELLED  灰框 + 圆圈图标,整轮被取消时给孤立 tool_call 补的 placeholder
 */
import { ref, computed } from 'vue'
import type { AgentMessage } from '../../api/agent'
import { ChevronRight, ChevronDown, Wrench, AlertCircle, CheckCircle2, ShieldOff, CircleSlash } from 'lucide-vue-next'

const props = defineProps<{ message: AgentMessage }>()

const expanded = ref(false)

const visualState = computed<'ok' | 'error' | 'rejected' | 'cancelled'>(() => {
  switch (props.message.toolStatus) {
    case 'ERROR':
      return 'error'
    case 'REJECTED':
      return 'rejected'
    case 'CANCELLED':
      return 'cancelled'
    case 'OK':
    case null:
    default:
      return 'ok'
  }
})

const containerClass = computed(() => {
  switch (visualState.value) {
    case 'error':
      return 'border-status-failed/40 bg-status-failed/5'
    case 'rejected':
      return 'border-status-running/40 bg-status-running/5'
    case 'cancelled':
      return 'border-border-subtle bg-surface-tertiary/40'
    default:
      return 'border-border-subtle bg-surface-secondary'
  }
})

const stateIcon = computed(() => {
  switch (visualState.value) {
    case 'error':
      return AlertCircle
    case 'rejected':
      return ShieldOff
    case 'cancelled':
      return CircleSlash
    default:
      return CheckCircle2
  }
})

const stateIconClass = computed(() => {
  switch (visualState.value) {
    case 'error':
      return 'text-status-failed shrink-0'
    case 'rejected':
      return 'text-status-running shrink-0'
    case 'cancelled':
      return 'text-text-muted shrink-0'
    default:
      return 'text-status-done shrink-0'
  }
})

const stateLabel = computed(() => {
  switch (visualState.value) {
    case 'error':
      return '失败'
    case 'rejected':
      return '已拒绝'
    case 'cancelled':
      return '已取消'
    default:
      return '完成'
  }
})

const stateLabelClass = computed(() => {
  switch (visualState.value) {
    case 'error':
      return 'text-status-failed'
    case 'rejected':
      return 'text-status-running'
    case 'cancelled':
      return 'text-text-muted'
    default:
      return 'text-text-muted'
  }
})

const prettyArgs = computed(() => prettyJson(props.message.toolArgsJson))
const prettyResult = computed(() => prettyJson(props.message.content))

function prettyJson(s: string | null): string {
  if (!s) return ''
  try {
    return JSON.stringify(JSON.parse(s), null, 2)
  } catch {
    return s
  }
}
</script>

<template>
  <div class="rounded-md border text-xs" :class="containerClass">
    <button
      type="button"
      class="w-full flex items-center gap-2 px-3 py-2 hover:bg-surface-tertiary/50 rounded-md"
      @click="expanded = !expanded"
    >
      <component :is="expanded ? ChevronDown : ChevronRight" :size="12" class="text-text-muted shrink-0" />
      <component :is="stateIcon" :size="12" :class="stateIconClass" />
      <Wrench :size="12" class="text-text-muted shrink-0" />
      <span class="font-mono text-text-primary">{{ message.toolName }}</span>
      <span :class="stateLabelClass">{{ stateLabel }}</span>
    </button>

    <div v-if="expanded" class="px-3 pb-3 space-y-2 border-t border-border-subtle">
      <div v-if="prettyArgs">
        <div class="text-[10px] uppercase tracking-wider text-text-muted mb-1">参数</div>
        <pre class="bg-surface-primary/50 rounded p-2 overflow-x-auto overflow-y-auto text-text-secondary max-h-60">{{ prettyArgs }}</pre>
      </div>
      <div v-if="prettyResult">
        <div class="text-[10px] uppercase tracking-wider text-text-muted mb-1">结果</div>
        <pre class="bg-surface-primary/50 rounded p-2 overflow-x-auto overflow-y-auto text-text-secondary max-h-80">{{ prettyResult }}</pre>
      </div>
    </div>
  </div>
</template>
