<script setup lang="ts">
// PresetOptimizeDialog —— 预设编辑器各 tab 顶部的"AI 优化"对话框。
// 用户描述对当前 section 的不满,后端调 LLM 重新生成该 section 涉及的字段,
// 用户可预览 explanation,选择"应用"覆盖编辑器 draft 或"再来一次"换说法重投。
//
// 不直接落库 — 应用后字段写回父组件 draft,父组件依旧需要点"保存"才落 DB。
import { computed, ref, watch } from 'vue'
import { Loader2, Sparkles, X, Check, RotateCcw } from 'lucide-vue-next'
import { optimizePreset } from '../api/presets'
import { extractError } from '../lib/format'

const props = defineProps<{
  presetId: number | null
  section: string | null
  sectionLabel: string
  /** 当前编辑器里这一节的草稿值(可能含未保存的改动)。后端会优先用这份。 */
  currentValues: Record<string, any>
}>()

const emit = defineEmits<{
  (e: 'close'): void
  (e: 'applied', fields: Record<string, any>): void
}>()

const open = computed(() => props.section !== null && props.presetId != null)

const userFeedback = ref('')
const busy = ref(false)
const error = ref<string | null>(null)
const result = ref<{ fields: Record<string, any>; explanation?: string | null } | null>(null)

watch(open, (v) => {
  if (v) {
    userFeedback.value = ''
    error.value = null
    result.value = null
  }
})

async function submit() {
  if (!props.presetId || !props.section) return
  if (!userFeedback.value.trim()) {
    error.value = '请先描述你对当前配置的不满或希望调整的方向'
    return
  }
  busy.value = true
  error.value = null
  try {
    const resp = await optimizePreset(props.presetId, {
      section: props.section,
      userFeedback: userFeedback.value.trim(),
      currentValues: props.currentValues,
    })
    result.value = { fields: resp.fields, explanation: resp.explanation }
  } catch (e: any) {
    error.value = extractError(e, '优化失败,LLM 可能超时,稍后再试')
  } finally {
    busy.value = false
  }
}

function apply() {
  if (!result.value) return
  emit('applied', result.value.fields)
  emit('close')
}

function regenerate() {
  result.value = null
  error.value = null
}

function previewKeys(): string[] {
  return result.value ? Object.keys(result.value.fields) : []
}

function previewValue(key: string): string {
  const v = result.value?.fields?.[key]
  if (v == null) return '(空)'
  if (typeof v === 'string') return v
  try {
    return JSON.stringify(v, null, 2)
  } catch {
    return String(v)
  }
}
</script>

<template>
  <div
    v-if="open"
    class="fixed inset-0 z-50 bg-black/60 flex items-center justify-center p-4"
    @click.self="!busy && emit('close')"
  >
    <div class="card p-6 max-w-[720px] w-full max-h-[90vh] overflow-y-auto bg-surface-secondary">
      <header class="flex items-center justify-between mb-1">
        <h2 class="text-lg font-semibold flex items-center gap-2">
          <Sparkles :size="18" class="text-accent" />
          AI 优化【{{ sectionLabel }}】
        </h2>
        <button
          class="text-text-muted hover:text-text-primary"
          :disabled="busy"
          @click="emit('close')"
        >
          <X :size="20" />
        </button>
      </header>
      <div class="text-xs text-text-muted mb-4">
        描述你对当前配置不满意的地方或希望调整的方向,LLM 会根据你的反馈重新生成本节字段。
        生成结果不会直接落库,你可在预览后选择"应用到编辑器"。
      </div>

      <div
        v-if="error"
        class="card p-3 mb-3 bg-status-failed/10 border-status-failed/30 text-xs text-status-failed"
      >
        {{ error }}
      </div>

      <!-- 输入阶段 -->
      <template v-if="!result">
        <label class="flex flex-col gap-1">
          <span class="text-xs text-text-muted">你希望怎么改?(越具体越好)</span>
          <textarea
            v-model="userFeedback"
            rows="6"
            :disabled="busy"
            placeholder="例:开篇 hook 太平,前 3 秒要立刻给一个数字冲击；&#10;系统提示词太啰嗦,精简到 200 字内；&#10;temperature 调到 0.6,让输出更稳定。"
            class="bg-surface-tertiary border border-border-subtle rounded px-3 py-2 text-sm font-sans resize-y focus:outline-none focus:border-accent"
          />
        </label>

        <div class="flex items-center gap-2 mt-5">
          <button
            class="btn"
            :disabled="busy"
            @click="emit('close')"
          >
            取消
          </button>
          <button
            class="btn-primary ml-auto"
            :disabled="busy || !userFeedback.trim()"
            @click="submit"
          >
            <Loader2 v-if="busy" :size="14" class="animate-spin" />
            <Sparkles v-else :size="14" />
            {{ busy ? 'LLM 生成中…(约 20-40s)' : '生成优化方案' }}
          </button>
        </div>
      </template>

      <!-- 预览阶段 -->
      <template v-else>
        <div class="card p-3 mb-3 bg-accent-soft border-accent/30 text-sm">
          <div class="text-xs text-text-muted mb-1 flex items-center gap-1.5">
            <Sparkles :size="11" class="text-accent" /> AI 改动说明
          </div>
          <div class="text-text-primary leading-relaxed">
            {{ result.explanation || '(LLM 未给出说明)' }}
          </div>
        </div>

        <div class="text-xs text-text-muted mb-2">
          受影响字段:{{ previewKeys().length }} 个 · 应用后会覆盖编辑器 draft,你仍可手动微调再保存。
        </div>

        <div class="space-y-3 mb-4">
          <div
            v-for="k in previewKeys()"
            :key="k"
            class="card p-3 bg-surface-tertiary"
          >
            <div class="flex items-center gap-2 mb-1.5">
              <span class="chip text-[10px] bg-surface-primary text-text-secondary font-mono">
                {{ k }}
              </span>
            </div>
            <pre
              class="text-xs font-mono text-text-secondary whitespace-pre-wrap break-all max-h-48 overflow-auto leading-relaxed"
            >{{ previewValue(k) }}</pre>
          </div>
        </div>

        <div class="flex items-center gap-2">
          <button
            class="btn"
            :disabled="busy"
            @click="emit('close')"
          >
            放弃
          </button>
          <button
            class="btn"
            :disabled="busy"
            @click="regenerate"
          >
            <RotateCcw :size="13" /> 换个说法再来
          </button>
          <button class="btn-primary ml-auto" @click="apply">
            <Check :size="14" /> 应用到编辑器
          </button>
        </div>
      </template>
    </div>
  </div>
</template>
