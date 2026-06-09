<script setup lang="ts">
/**
 * 动作目录(右侧栏)。
 *
 * 让用户知道当前 Agent 能干什么 —— 列出已注册的工具,以及若干常用提示词模板。
 * 点击模板把文案预填到输入框(emit fill-prompt),用户可继续编辑后发送。
 *
 * 工具列表通过 GET /api/agent/tools 拉取(只在挂载时一次)。
 *
 * 支持拖拽改宽(左边缘) + 折叠:拖到 200px 以下松手自动折叠成窄条。
 */
import { ref, onMounted, computed } from 'vue'
import { listTools } from '../../api/agent'
import {
  Wrench, Sparkles, ChevronDown, ChevronRight,
  PanelRightClose, PanelRightOpen,
} from 'lucide-vue-next'
import { useResizableSidebar } from '../../composables/useResizableSidebar'

const emit = defineEmits<{
  (e: 'fill-prompt', text: string): void
}>()

const tools = ref<{ name: string; description: string }[]>([])
const showTools = ref(true)
// 每个 sample group 独立折叠状态;之前用单一 ref 会让两个组同时收/展
const sampleOpen = ref<Record<string, boolean>>({})
const errorMsg = ref<string | null>(null)

const { width, collapsed, dragging, startDrag } = useResizableSidebar({
  storageKey: 'auteur.chat.action-palette',
  defaultWidth: 288,
  minWidth: 240,
  maxWidth: 440,
  collapseAtWidth: 200,
  side: 'right',
})

onMounted(async () => {
  try {
    const r = await listTools()
    tools.value = r.definitions.map((d: any) => ({
      name: d.function?.name || '?',
      description: d.function?.description || '',
    }))
  } catch (e) {
    errorMsg.value = (e as Error).message
  }
})

interface SampleGroup {
  title: string
  items: { label: string; prompt: string }[]
}

const samples = computed<SampleGroup[]>(() => [
  {
    title: '探索',
    items: [
      { label: 'Ping 测试', prompt: 'ping 一下,确认工具链路。' },
      { label: '列出全部预设', prompt: '列出当前所有预设(摘要)。' },
      { label: '列出系统配置', prompt: '列出全部系统配置项,告诉我哪些还没填值。' },
    ],
  },
  {
    title: '常用修改',
    items: [
      {
        label: '改 critic 阈值',
        prompt: '把 lifecopy 预设的脚本自审阈值(scriptCriticThreshold)改成 75,作为新版本保存,备注"提高门槛"。',
      },
      {
        label: '改默认 LLM 模型',
        prompt: '把 auteur.llm.default-model 改成 deepseek-chat。',
      },
      {
        label: '查看预设历史版本',
        prompt: '列出 lifecopy 预设的所有历史版本,标出最近 3 次的备注。',
      },
    ],
  },
])
</script>

<template>
  <div
    v-if="collapsed"
    class="w-9 shrink-0 border-l border-border-subtle bg-surface-secondary flex flex-col items-center py-2 relative"
  >
    <button
      type="button"
      class="w-7 h-7 rounded-md flex items-center justify-center text-text-muted hover:bg-surface-tertiary hover:text-text-primary"
      title="展开动作目录"
      @click="collapsed = false"
    >
      <PanelRightOpen :size="14" />
    </button>
    <!-- 折叠态拖拽手柄:从左边缘往外拖即可展开 -->
    <div
      class="absolute top-0 left-0 h-full w-1 cursor-col-resize group z-10"
      title="拖出展开动作目录"
      @mousedown="startDrag"
    >
      <div
        class="h-full w-px transition-colors"
        :class="dragging ? 'bg-accent' : 'bg-transparent group-hover:bg-accent/40'"
      />
    </div>
  </div>
  <div
    v-else
    :style="{ width: width + 'px' }"
    :class="[
      'shrink-0 border-l border-border-subtle bg-surface-secondary flex flex-col relative',
      dragging ? '' : 'transition-[width] duration-150',
    ]"
  >
    <div class="px-3 py-3 border-b border-border-subtle flex items-start gap-2">
      <div class="flex-1 min-w-0">
        <div class="text-xs font-semibold text-text-primary">动作目录</div>
        <div class="text-[10px] text-text-muted mt-0.5">点击下方条目预填到输入框</div>
      </div>
      <button
        type="button"
        class="w-7 h-7 rounded-md flex items-center justify-center text-text-muted hover:bg-surface-tertiary hover:text-text-primary shrink-0"
        title="收起动作目录"
        @click="collapsed = true"
      >
        <PanelRightClose :size="14" />
      </button>
    </div>

    <div class="flex-1 overflow-y-auto p-2 space-y-3 text-xs">
      <!-- 模板 -->
      <div v-for="g in samples" :key="g.title">
        <button
          type="button"
          class="w-full flex items-center gap-1.5 px-2 py-1 text-text-secondary hover:text-text-primary"
          @click="sampleOpen[g.title] = !(sampleOpen[g.title] ?? true)"
        >
          <component :is="(sampleOpen[g.title] ?? true) ? ChevronDown : ChevronRight" :size="11" />
          <Sparkles :size="11" class="text-accent" />
          <span class="font-medium">{{ g.title }}</span>
        </button>
        <div v-if="(sampleOpen[g.title] ?? true)" class="space-y-1 mt-0.5 pl-2">
          <button
            v-for="it in g.items"
            :key="it.label"
            type="button"
            class="w-full text-left px-2 py-1.5 rounded-md hover:bg-surface-tertiary text-text-secondary hover:text-text-primary"
            :title="it.prompt"
            @click="emit('fill-prompt', it.prompt)"
          >
            {{ it.label }}
          </button>
        </div>
      </div>

      <!-- 工具列表 -->
      <div>
        <button
          type="button"
          class="w-full flex items-center gap-1.5 px-2 py-1 text-text-secondary hover:text-text-primary"
          @click="showTools = !showTools"
        >
          <component :is="showTools ? ChevronDown : ChevronRight" :size="11" />
          <Wrench :size="11" class="text-text-muted" />
          <span class="font-medium">已注册工具 ({{ tools.length }})</span>
        </button>
        <div v-if="showTools" class="space-y-1 mt-0.5 pl-2">
          <div v-if="errorMsg" class="text-status-failed">{{ errorMsg }}</div>
          <div v-for="t in tools" :key="t.name" class="px-2 py-1.5">
            <div class="font-mono text-[10px] text-text-primary">{{ t.name }}</div>
            <div class="text-[10px] text-text-muted leading-snug mt-0.5">{{ t.description }}</div>
          </div>
        </div>
      </div>
    </div>

    <!-- 拖拽手柄:左边缘(右侧栏靠左拖) -->
    <div
      class="absolute top-0 left-0 h-full w-1 cursor-col-resize group z-10"
      title="拖动调整宽度;拖到很窄会自动收起"
      @mousedown="startDrag"
    >
      <div
        class="h-full w-px transition-colors"
        :class="dragging ? 'bg-accent' : 'bg-transparent group-hover:bg-accent/40'"
      />
    </div>
  </div>
</template>
