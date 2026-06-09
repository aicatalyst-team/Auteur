<script setup lang="ts">
/**
 * 输入框。Enter 发送,Shift+Enter 换行;busy 时禁用 + 显示 loading 文案。
 *
 * 暴露 fillText(text) 让父组件预填(ActionPalette 点击模板时用)。
 */
import { ref, watch, nextTick } from 'vue'
import { Send, Loader2 } from 'lucide-vue-next'

const props = defineProps<{
  busy?: boolean
  placeholder?: string
}>()

const emit = defineEmits<{
  (e: 'send', value: string): void
}>()

const text = ref('')
const ta = ref<HTMLTextAreaElement | null>(null)

function send() {
  const v = text.value.trim()
  if (!v || props.busy) return
  emit('send', v)
  text.value = ''
  resize()
}

function onKey(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
    e.preventDefault()
    send()
  }
}

function resize() {
  const el = ta.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = Math.min(el.scrollHeight, 200) + 'px'
}

async function fillText(t: string) {
  text.value = t
  await nextTick()
  resize()
  ta.value?.focus()
}

defineExpose({ fillText })

watch(text, resize)
</script>

<template>
  <div class="border-t border-border-subtle bg-surface-secondary p-4">
    <div class="flex gap-2 items-end max-w-4xl mx-auto">
      <textarea
        ref="ta"
        v-model="text"
        :placeholder="placeholder ?? '试着输入:列出所有预设 / 把竖屏故事的 critic 阈值改成 75 / ping'"
        :disabled="busy"
        rows="1"
        class="flex-1 resize-none bg-surface-primary border border-border-default rounded-md px-3 py-2 text-sm text-text-primary placeholder:text-text-muted focus:outline-none focus:border-accent disabled:opacity-50"
        @keydown="onKey"
      />
      <button
        type="button"
        class="btn-primary h-9 shrink-0"
        :disabled="busy || !text.trim()"
        @click="send"
      >
        <component :is="busy ? Loader2 : Send" :size="14" :class="busy ? 'animate-spin' : ''" />
        <span>{{ busy ? '处理中' : '发送' }}</span>
      </button>
    </div>
    <div class="text-[10px] text-text-muted text-center mt-2">
      Enter 发送 · Shift+Enter 换行 · 当前阶段仅开放只读 + 预设/系统配置写入
    </div>
  </div>
</template>
