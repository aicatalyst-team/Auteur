<script setup lang="ts">
/**
 * 对话流。每条消息按 role 渲染:
 *   - user      : 右对齐紫色气泡
 *   - assistant : 左对齐普通气泡(可能带工具调用预告:hasToolCalls=true 时只是个状态标识)
 *   - tool      : 折叠卡片,展示工具名 + 参数 + 结果
 *   - system    : 不渲染
 *
 * tool 行用 ToolCallCard 组件,默认折叠(只显示一行 chip),点击展开看 args/result。
 */
import type { AgentMessage } from '../../api/agent'
import ToolCallCard from './ToolCallCard.vue'
import MarkdownContent from './MarkdownContent.vue'
import { Sparkles, User, Loader2 } from 'lucide-vue-next'

defineProps<{
  messages: AgentMessage[]
  /** assistant 是否还在生成中(显示个 loading 气泡) */
  busy?: boolean
}>()
</script>

<template>
  <div class="flex flex-col gap-4 px-4 py-6">
    <template v-for="msg in messages" :key="msg.id">
      <!-- USER -->
      <div v-if="msg.role === 'user'" class="flex justify-end">
        <div class="max-w-[80%] flex gap-2 items-start">
          <div class="bg-accent-soft text-text-primary px-4 py-2 rounded-lg whitespace-pre-wrap break-words">
            {{ msg.content }}
          </div>
          <div class="w-7 h-7 rounded-full bg-accent flex items-center justify-center shrink-0">
            <User :size="14" class="text-white" />
          </div>
        </div>
      </div>

      <!-- ASSISTANT -->
      <div v-else-if="msg.role === 'assistant'" class="flex justify-start">
        <div class="max-w-full flex-1 flex gap-2 items-start min-w-0">
          <div class="w-7 h-7 rounded-full bg-surface-tertiary flex items-center justify-center shrink-0">
            <Sparkles :size="14" class="text-accent" />
          </div>
          <div class="bg-surface-secondary border border-border-subtle px-4 py-2 rounded-lg break-words text-sm min-w-0 flex-1">
            <MarkdownContent v-if="msg.content" :source="msg.content" />
            <span v-else class="text-text-muted italic">(已发起工具调用)</span>
          </div>
        </div>
      </div>

      <!-- TOOL -->
      <div v-else-if="msg.role === 'tool'" class="flex justify-start pl-9">
        <ToolCallCard :message="msg" class="max-w-[80%] w-full" />
      </div>
    </template>

    <!-- LOADING -->
    <div v-if="busy" class="flex justify-start">
      <div class="flex gap-2 items-center">
        <div class="w-7 h-7 rounded-full bg-surface-tertiary flex items-center justify-center shrink-0">
          <Sparkles :size="14" class="text-accent" />
        </div>
        <div class="bg-surface-secondary border border-border-subtle px-4 py-2 rounded-lg flex items-center gap-2 text-sm text-text-muted">
          <Loader2 :size="14" class="animate-spin" /> 思考中…
        </div>
      </div>
    </div>
  </div>
</template>
