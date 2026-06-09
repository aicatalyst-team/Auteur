<script setup lang="ts">
/**
 * 工具调用结果卡片。默认折叠,点击 chip 展开看完整 args / result。
 * 错误状态加红色边框 + 错误图标。
 */
import { ref, computed } from 'vue'
import type { AgentMessage } from '../../api/agent'
import { ChevronRight, ChevronDown, Wrench, AlertCircle, CheckCircle2 } from 'lucide-vue-next'

const props = defineProps<{ message: AgentMessage }>()

const expanded = ref(false)
const isError = computed(() => props.message.toolStatus === 'ERROR')

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
  <div
    class="rounded-md border text-xs"
    :class="isError ? 'border-status-failed/40 bg-status-failed/5' : 'border-border-subtle bg-surface-secondary'"
  >
    <button
      type="button"
      class="w-full flex items-center gap-2 px-3 py-2 hover:bg-surface-tertiary/50 rounded-md"
      @click="expanded = !expanded"
    >
      <component :is="expanded ? ChevronDown : ChevronRight" :size="12" class="text-text-muted shrink-0" />
      <component
        :is="isError ? AlertCircle : CheckCircle2"
        :size="12"
        :class="isError ? 'text-status-failed shrink-0' : 'text-status-done shrink-0'"
      />
      <Wrench :size="12" class="text-text-muted shrink-0" />
      <span class="font-mono text-text-primary">{{ message.toolName }}</span>
      <span v-if="isError" class="text-status-failed">失败</span>
      <span v-else class="text-text-muted">完成</span>
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
