<script setup lang="ts">
// 单张图片卡:封面 + 状态浮层 + 元数据 + 操作 + inline 提示词编辑器
// 父持有所有 state(运行态、编辑草稿),通过 props 注入,子件 emit 行为。
import { Link2, Loader2, Pencil, RotateCcw, Save, ShieldCheck, X } from 'lucide-vue-next'
import TimeText from '../TimeText.vue'
import type { ImageAsset, PipelineRun } from '../../types'
import { REVIEW_DECISION_LABELS } from '../../types'

type RunState = { runId: number; status: PipelineRun['status']; msg?: string }

const props = defineProps<{
  img: ImageAsset
  protagonistRefAssetId: number | null
  shotHasFinal: boolean
  shotRun?: RunState
  assetRun?: RunState
  // inline 提示词编辑器:仅当 editingShotId === img.shotId 时显示
  isEditingPrompt: boolean
  draftPrompt: string
  promptSaving: boolean
  promptError: string | null
}>()

const emit = defineEmits<{
  (e: 'select-as-final', assetId: number): void
  (e: 'regenerate-shot', shotId: number): void
  (e: 'reaudit-asset', assetId: number): void
  (e: 'start-edit-prompt', shotId: number): void
  (e: 'cancel-edit-prompt'): void
  (e: 'update:draft-prompt', value: string): void
  (e: 'save-prompt-only'): void
  (e: 'save-prompt-and-regen'): void
}>()

const DECISION_STYLE: Record<string, string> = {
  PASS: 'bg-status-done/15 text-status-done',
  FAIL: 'bg-status-failed/15 text-status-failed',
  REJECT: 'bg-status-failed/15 text-status-failed',
  REGENERATE: 'bg-status-paused/15 text-status-paused',
  REVIEW: 'bg-status-paused/15 text-status-paused',
  MANUAL: 'bg-status-paused/15 text-status-paused',
}

function onCoverClick() {
  if (props.img.fileUrl && !props.img.isFinal && !props.shotRun && !props.assetRun) {
    emit('select-as-final', props.img.id)
  }
}
</script>

<template>
  <article class="card overflow-hidden p-0 flex flex-col group">
    <div
      class="aspect-video bg-surface-secondary flex items-center justify-center text-xs text-text-muted relative"
      :class="img.fileUrl && !img.isFinal && !shotRun && !assetRun ? 'cursor-pointer' : ''"
      @click="onCoverClick"
    >
      <img v-if="img.fileUrl" :src="img.fileUrl" class="w-full h-full object-cover" />
      <span v-else>无文件</span>
      <div v-if="shotRun" class="absolute inset-0 bg-black/40 flex items-center justify-center text-xs text-white">
        <Loader2 v-if="shotRun.status === 'RUNNING' || shotRun.status === 'PENDING'"
                 :size="20" class="animate-spin" />
        <span v-else>{{ shotRun.msg }}</span>
      </div>
      <div v-else-if="assetRun" class="absolute inset-0 bg-black/40 flex items-center justify-center text-xs text-white">
        <Loader2 v-if="assetRun.status === 'RUNNING' || assetRun.status === 'PENDING'"
                 :size="20" class="animate-spin" />
        <span v-else>{{ assetRun.msg }}</span>
      </div>
      <div
        v-if="img.fileUrl && !shotRun && !assetRun"
        class="absolute inset-0 flex items-center justify-center transition-all opacity-0 group-hover:opacity-100"
        :class="img.isFinal ? 'group-hover:bg-black/15' : 'group-hover:bg-black/40'"
      >
        <span class="text-[11px] font-medium px-2.5 py-1 rounded-full bg-black/30 text-white/90 backdrop-blur-sm border border-white/15">
          {{ img.isFinal ? '已选定' : (shotHasFinal ? '替换此图' : '设为选定') }}
        </span>
      </div>
    </div>
    <div class="p-3 text-xs flex-1 flex flex-col gap-1.5">
      <div class="flex items-center justify-between gap-1">
        <span class="font-mono text-text-muted">#{{ img.id }} · shot {{ img.shotId }}</span>
        <div class="flex items-center gap-1">
          <span v-if="protagonistRefAssetId === img.id" class="chip bg-accent-soft text-accent" title="基准照">基准照</span>
          <span v-else-if="img.usedProtagonistRef" class="chip bg-accent-soft/60 text-accent flex items-center gap-1" title="带主角参考">
            <Link2 :size="10" /> 主角
          </span>
          <span v-if="img.isFinal" class="chip bg-status-done/15 text-status-done">已选定</span>
        </div>
      </div>
      <div v-if="img.reviewDecision" class="flex items-center gap-1.5">
        <span class="chip" :class="DECISION_STYLE[img.reviewDecision] || 'bg-text-muted/15 text-text-muted'">
          {{ REVIEW_DECISION_LABELS[img.reviewDecision] || img.reviewDecision }}
        </span>
        <span class="text-text-muted">{{ img.reviewScore ?? '-' }} 分</span>
      </div>
      <div v-if="img.reviewIssues" class="text-status-failed/90 line-clamp-2 leading-snug">{{ img.reviewIssues }}</div>
      <div class="text-text-muted">
        <TimeText :value="img.createdAt" relative />
      </div>
      <div class="mt-auto pt-2 flex items-center gap-1.5 flex-wrap border-t border-border-subtle">
        <button
          class="chip cursor-pointer text-xs bg-surface-tertiary text-text-secondary hover:bg-accent-soft hover:text-accent disabled:opacity-50 disabled:cursor-not-allowed flex items-center gap-1"
          :disabled="!!shotRun || !!assetRun"
          @click="emit('regenerate-shot', img.shotId)"
        >
          <Loader2 v-if="shotRun && (shotRun.status === 'RUNNING' || shotRun.status === 'PENDING')"
                   :size="11" class="animate-spin" />
          <RotateCcw v-else :size="11" />
          {{ shotRun ? `run ${shotRun.runId}` : '重生' }}
        </button>
        <button
          class="chip cursor-pointer text-xs bg-surface-tertiary text-text-secondary hover:bg-accent-soft hover:text-accent disabled:opacity-50 disabled:cursor-not-allowed flex items-center gap-1"
          :disabled="!!shotRun || !!assetRun || isEditingPrompt"
          @click="emit('start-edit-prompt', img.shotId)"
        >
          <Pencil :size="11" /> 改提示词
        </button>
        <button
          v-if="img.fileUrl"
          class="chip cursor-pointer text-xs bg-surface-tertiary text-text-secondary hover:bg-accent-soft hover:text-accent disabled:opacity-50 disabled:cursor-not-allowed flex items-center gap-1"
          :disabled="!!shotRun || !!assetRun"
          @click="emit('reaudit-asset', img.id)"
        >
          <Loader2 v-if="assetRun && (assetRun.status === 'RUNNING' || assetRun.status === 'PENDING')"
                   :size="11" class="animate-spin" />
          <ShieldCheck v-else :size="11" />
          {{ assetRun ? `run ${assetRun.runId}` : '重审' }}
        </button>
      </div>

      <!-- inline 提示词编辑器 -->
      <div v-if="isEditingPrompt"
           class="mt-2 p-2 rounded-md bg-surface-tertiary/60 border border-accent/30">
        <div class="text-[10px] text-text-muted mb-1 flex items-center justify-between">
          <span>shot {{ img.shotId }} · 提示词(中文)</span>
          <button class="text-text-muted hover:text-text-primary"
                  :disabled="promptSaving" @click="emit('cancel-edit-prompt')">
            <X :size="11" />
          </button>
        </div>
        <textarea :value="draftPrompt" rows="4"
                  :disabled="promptSaving"
                  placeholder="改这一镜要画什么。改完点保存或保存并重生。"
                  class="w-full bg-surface-primary border border-border-subtle rounded p-1.5 text-xs leading-relaxed font-sans resize-y"
                  @input="emit('update:draft-prompt', ($event.target as HTMLTextAreaElement).value)" />
        <div v-if="promptError" class="text-[10px] text-status-failed mt-1">{{ promptError }}</div>
        <div class="flex items-center gap-1.5 mt-1.5">
          <button class="chip cursor-pointer text-[10px] bg-surface-tertiary text-text-secondary"
                  :disabled="promptSaving" @click="emit('save-prompt-only')">
            <Loader2 v-if="promptSaving" :size="10" class="animate-spin" />
            <Save v-else :size="10" />
            仅保存
          </button>
          <button class="chip cursor-pointer text-[10px] bg-accent text-white hover:bg-accent-hover"
                  :disabled="promptSaving" @click="emit('save-prompt-and-regen')">
            <Loader2 v-if="promptSaving" :size="10" class="animate-spin" />
            <RotateCcw v-else :size="10" />
            保存并重生
          </button>
        </div>
      </div>
    </div>
  </article>
</template>
