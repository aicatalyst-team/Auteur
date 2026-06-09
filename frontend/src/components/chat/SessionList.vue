<script setup lang="ts">
/**
 * 历史会话列表(左侧栏)。点击切会话,新建按钮在顶部。
 *
 * 支持拖拽改宽(右边缘) + 折叠:
 *   - 拖到 160px 以下松手自动折叠为窄条;
 *   - 折叠态点击窄条按钮展开,恢复上次保存的宽度。
 */
import type { AgentSession } from '../../api/agent'
import { Plus, Trash2, MessageSquare, PanelLeftClose, PanelLeftOpen } from 'lucide-vue-next'
import { useResizableSidebar } from '../../composables/useResizableSidebar'

defineProps<{
  sessions: AgentSession[]
  activeId: number | null
}>()

const emit = defineEmits<{
  (e: 'select', id: number): void
  (e: 'create'): void
  (e: 'delete', id: number): void
}>()

const { width, collapsed, dragging, startDrag } = useResizableSidebar({
  storageKey: 'auteur.chat.session-list',
  defaultWidth: 256,
  minWidth: 200,
  maxWidth: 400,
  collapseAtWidth: 160,
  side: 'left',
})

function shortTitle(s: AgentSession): string {
  if (s.title && s.title.trim()) return s.title
  return `会话 #${s.id}`
}

function onDelete(e: MouseEvent, id: number) {
  e.stopPropagation()
  emit('delete', id)
}
</script>

<template>
  <div
    v-if="collapsed"
    class="w-9 shrink-0 border-r border-border-subtle bg-surface-secondary flex flex-col items-center py-2 relative"
  >
    <button
      type="button"
      class="w-7 h-7 rounded-md flex items-center justify-center text-text-muted hover:bg-surface-tertiary hover:text-text-primary"
      title="展开会话列表"
      @click="collapsed = false"
    >
      <PanelLeftOpen :size="14" />
    </button>
    <!-- 折叠态拖拽手柄:从右边缘往外拖即可展开 -->
    <div
      class="absolute top-0 right-0 h-full w-1 cursor-col-resize group z-10"
      title="拖出展开会话列表"
      @mousedown="startDrag"
    >
      <div
        class="h-full w-px ml-auto transition-colors"
        :class="dragging ? 'bg-accent' : 'bg-transparent group-hover:bg-accent/40'"
      />
    </div>
  </div>
  <div
    v-else
    :style="{ width: width + 'px' }"
    :class="[
      'shrink-0 border-r border-border-subtle bg-surface-secondary flex flex-col relative',
      dragging ? '' : 'transition-[width] duration-150',
    ]"
  >
    <div class="p-3 border-b border-border-subtle flex items-center gap-2">
      <button class="btn-primary flex-1 min-w-0 justify-center overflow-hidden" @click="emit('create')">
        <Plus :size="14" class="shrink-0" />
        <span class="truncate">新建会话</span>
      </button>
      <button
        type="button"
        class="w-8 h-8 rounded-md flex items-center justify-center text-text-muted hover:bg-surface-tertiary hover:text-text-primary shrink-0"
        title="收起会话列表"
        @click="collapsed = true"
      >
        <PanelLeftClose :size="14" />
      </button>
    </div>
    <div class="flex-1 overflow-y-auto p-2 space-y-1">
      <div v-if="sessions.length === 0" class="text-xs text-text-muted px-2 py-4 text-center">
        还没有会话
      </div>
      <button
        v-for="s in sessions"
        :key="s.id"
        type="button"
        class="w-full text-left px-2 py-2 rounded-md flex items-center gap-2 group"
        :class="
          s.id === activeId
            ? 'bg-accent-soft text-accent'
            : 'text-text-secondary hover:bg-surface-tertiary'
        "
        @click="emit('select', s.id)"
      >
        <MessageSquare :size="13" class="shrink-0" />
        <span class="flex-1 text-xs truncate">{{ shortTitle(s) }}</span>
        <span
          class="opacity-0 group-hover:opacity-100 text-text-muted hover:text-status-failed cursor-pointer"
          title="删除"
          @click="onDelete($event, s.id)"
        >
          <Trash2 :size="12" />
        </span>
      </button>
    </div>

    <!-- 拖拽手柄:右边缘 -->
    <div
      class="absolute top-0 right-0 h-full w-1 cursor-col-resize group z-10"
      title="拖动调整宽度;拖到很窄会自动收起"
      @mousedown="startDrag"
    >
      <div
        class="h-full w-px ml-auto transition-colors"
        :class="dragging ? 'bg-accent' : 'bg-transparent group-hover:bg-accent/40'"
      />
    </div>
  </div>
</template>
