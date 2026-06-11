<script setup lang="ts">
// 总导演"导演笔记"编辑器(用户手动填写,与 preset 配合使用)。
// 持久化:走 PATCH /api/topics/{id} 的 directorNote 字段。
import { computed, ref, watch } from 'vue'
import { Loader2, Plus, Save, Sparkles, X } from 'lucide-vue-next'
import { updateTopic } from '../api/topics'
import { extractError } from '../lib/format'
import type { DirectorNote } from '../types'
import DirectorNoteOptimizeDialog from './DirectorNoteOptimizeDialog.vue'

const props = defineProps<{
  open: boolean
  topicId: number
  /** 父组件传入现有 directorNote(topic.directorNote);空 → 抽屉初始为空表单 */
  initialNote?: DirectorNote | null
}>()
const emit = defineEmits<{
  (e: 'close'): void
  (e: 'saved', note: DirectorNote): void
}>()

const ARC_SECTIONS: Array<'A' | 'B' | 'C' | 'D' | 'E'> = ['A', 'B', 'C', 'D', 'E']
const ARC_LABELS: Record<string, string> = {
  A: 'A 段 · 钩子',
  B: 'B 段 · 累积',
  C: 'C 段 · 中段',
  D: 'D 段 · 揭秘',
  E: 'E 段 · 留白',
}

function emptyNote(): DirectorNote {
  return {
    tone: '',
    pacing: '',
    narrativeArc: ARC_SECTIONS.map((section) => ({ section, guidance: '' })),
    visualStyle: { palette: '', depthOfField: '', lighting: '', avoidWords: [] },
    protagonistVibe: { appearance: '', voiceVibe: '', speakingPace: '' },
    keyMoments: [],
    highlightThemes: [],
    directorNotes: '',
  }
}

const form = ref<DirectorNote>(emptyNote())
const newAvoidWord = ref('')
const newTheme = ref('')
const newMomentTime = ref('')
const newMomentWhat = ref('')
const saving = ref(false)
const saveError = ref<string | null>(null)
const optimizeOpen = ref(false)

watch(
  () => [props.open, props.initialNote] as const,
  ([open, note]) => {
    if (!open) return
    form.value = note ? normalizeNote(note) : emptyNote()
    saveError.value = null
  },
  { immediate: true },
)

// 后端返回的 narrativeArc 应该已经按 A→E 顺序,但加保险:补齐缺失的段、丢弃多余的
function normalizeNote(note: DirectorNote): DirectorNote {
  const arcMap = new Map<string, string>(
    (note.narrativeArc ?? []).map((a) => [a.section, a.guidance ?? '']),
  )
  return {
    tone: note.tone ?? '',
    pacing: note.pacing ?? '',
    narrativeArc: ARC_SECTIONS.map((section) => ({
      section,
      guidance: arcMap.get(section) ?? '',
    })),
    visualStyle: {
      palette: note.visualStyle?.palette ?? '',
      depthOfField: note.visualStyle?.depthOfField ?? '',
      lighting: note.visualStyle?.lighting ?? '',
      avoidWords: [...(note.visualStyle?.avoidWords ?? [])],
    },
    protagonistVibe: {
      appearance: note.protagonistVibe?.appearance ?? '',
      voiceVibe: note.protagonistVibe?.voiceVibe ?? '',
      speakingPace: note.protagonistVibe?.speakingPace ?? '',
    },
    keyMoments: (note.keyMoments ?? []).map((m) => ({ time: m.time ?? '', what: m.what ?? '' })),
    highlightThemes: [...(note.highlightThemes ?? [])],
    directorNotes: note.directorNotes ?? '',
  }
}

const canSave = computed(
  () =>
    !!form.value.tone.trim() &&
    !!form.value.pacing.trim() &&
    form.value.narrativeArc.every((a) => !!a.guidance.trim()),
)

function addAvoidWord() {
  const w = newAvoidWord.value.trim()
  if (!w || form.value.visualStyle.avoidWords.includes(w)) return
  form.value.visualStyle.avoidWords.push(w)
  newAvoidWord.value = ''
}
function removeAvoidWord(idx: number) {
  form.value.visualStyle.avoidWords.splice(idx, 1)
}
function addTheme() {
  const t = newTheme.value.trim()
  if (!t || form.value.highlightThemes.includes(t)) return
  form.value.highlightThemes.push(t)
  newTheme.value = ''
}
function removeTheme(idx: number) {
  form.value.highlightThemes.splice(idx, 1)
}
function addMoment() {
  const time = newMomentTime.value.trim()
  const what = newMomentWhat.value.trim()
  if (!time || !what) return
  form.value.keyMoments.push({ time, what })
  newMomentTime.value = ''
  newMomentWhat.value = ''
}
function removeMoment(idx: number) {
  form.value.keyMoments.splice(idx, 1)
}

async function save() {
  saving.value = true
  saveError.value = null
  try {
    await updateTopic(props.topicId, { directorNote: JSON.stringify(form.value) })
    emit('saved', form.value)
    emit('close')
  } catch (e) {
    saveError.value = extractError(e)
  } finally {
    saving.value = false
  }
}

function close() {
  if (saving.value) return
  emit('close')
}

/** AI 智能填充弹窗 apply 回调:全量替换 form。复用 normalizeNote 兜底,确保 5 段补齐 / 字符串字段不为 undefined。 */
function onAiApplied(note: DirectorNote) {
  form.value = normalizeNote(note)
  saveError.value = null
}
</script>

<template>
  <div v-if="open" class="fixed inset-0 z-50 flex justify-end">
    <div class="absolute inset-0 bg-black/40" @click="close" />
    <aside class="relative h-full w-[640px] bg-white shadow-2xl flex flex-col">
      <header class="px-6 py-4 border-b flex items-center justify-between">
        <div>
          <h2 class="text-lg font-semibold">配置导演笔记</h2>
          <p class="text-sm text-gray-500 mt-0.5">总导演产出全片 vision · 编剧/摄影/美术/剪辑都参考</p>
        </div>
        <div class="flex items-center gap-2">
          <button
            class="text-xs px-2.5 py-1.5 border rounded hover:bg-amber-50 hover:border-amber-300 disabled:opacity-50 flex items-center gap-1 text-amber-700"
            :disabled="saving"
            @click="optimizeOpen = true"
            title="LLM 综合你已填的内容 + 自然语言诉求重写整份笔记"
          >
            <Sparkles class="w-3.5 h-3.5" />
            AI 智能填充
          </button>
          <button class="p-1 hover:bg-gray-100 rounded" @click="close" :disabled="saving">
            <X class="w-5 h-5" />
          </button>
        </div>
      </header>

      <div class="flex-1 overflow-y-auto p-6 space-y-6">
        <!-- tone / pacing -->
        <section class="grid grid-cols-1 gap-4">
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">整体调性 (tone)</label>
            <input
              v-model="form.tone"
              type="text"
              placeholder="如:孤独少年皇帝的隐忍回忆 / 沉重克制"
              class="w-full px-3 py-2 border rounded text-sm"
            />
          </div>
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">整体节奏 (pacing)</label>
            <input
              v-model="form.pacing"
              type="text"
              placeholder="如:快入慢出 / 缓慢铺陈"
              class="w-full px-3 py-2 border rounded text-sm"
            />
          </div>
        </section>

        <!-- narrativeArc -->
        <section>
          <h3 class="text-sm font-semibold text-gray-700 mb-2">五段节奏指导 (narrativeArc)</h3>
          <div class="space-y-2">
            <div v-for="(arc, idx) in form.narrativeArc" :key="arc.section">
              <label class="block text-xs font-medium text-gray-500 mb-1">{{ ARC_LABELS[arc.section] || arc.section }}</label>
              <textarea
                v-model="form.narrativeArc[idx].guidance"
                rows="2"
                :placeholder="'25-50 字,具体到镜头类型 / 情绪'"
                class="w-full px-3 py-2 border rounded text-sm"
              />
            </div>
          </div>
        </section>

        <!-- visualStyle -->
        <section>
          <h3 class="text-sm font-semibold text-gray-700 mb-2">美术指导 (visualStyle)</h3>
          <div class="grid grid-cols-1 gap-3">
            <div>
              <label class="block text-xs font-medium text-gray-500 mb-1">色调 palette</label>
              <input v-model="form.visualStyle.palette" type="text" class="w-full px-3 py-2 border rounded text-sm" placeholder="如:暗调冷色调,雪夜烛火" />
            </div>
            <div>
              <label class="block text-xs font-medium text-gray-500 mb-1">景深 depthOfField</label>
              <input v-model="form.visualStyle.depthOfField" type="text" class="w-full px-3 py-2 border rounded text-sm" placeholder="如:浅景深 / 大光圈虚化" />
            </div>
            <div>
              <label class="block text-xs font-medium text-gray-500 mb-1">光线 lighting</label>
              <input v-model="form.visualStyle.lighting" type="text" class="w-full px-3 py-2 border rounded text-sm" placeholder="如:电影感低 key / 高对比侧光" />
            </div>
            <div>
              <label class="block text-xs font-medium text-gray-500 mb-1">禁用词 avoidWords (生图防翻车)</label>
              <div class="flex flex-wrap gap-1.5 mb-2">
                <span
                  v-for="(w, idx) in form.visualStyle.avoidWords"
                  :key="idx"
                  class="inline-flex items-center gap-1 px-2 py-0.5 bg-red-50 text-red-700 text-xs rounded"
                >
                  {{ w }}
                  <button class="hover:text-red-900" @click="removeAvoidWord(idx)"><X class="w-3 h-3" /></button>
                </span>
              </div>
              <div class="flex gap-2">
                <input
                  v-model="newAvoidWord"
                  type="text"
                  placeholder="如:网红脸"
                  class="flex-1 px-3 py-1.5 border rounded text-sm"
                  @keydown.enter.prevent="addAvoidWord"
                />
                <button class="px-2 py-1 border rounded hover:bg-gray-50" @click="addAvoidWord">
                  <Plus class="w-4 h-4" />
                </button>
              </div>
            </div>
          </div>
        </section>

        <!-- protagonistVibe -->
        <section>
          <h3 class="text-sm font-semibold text-gray-700 mb-2">演员气质 (protagonistVibe)</h3>
          <div class="grid grid-cols-1 gap-3">
            <div>
              <label class="block text-xs font-medium text-gray-500 mb-1">外貌气质 appearance</label>
              <input v-model="form.protagonistVibe.appearance" type="text" class="w-full px-3 py-2 border rounded text-sm" placeholder="如:决绝隐忍,瘦削,孤独" />
            </div>
            <div>
              <label class="block text-xs font-medium text-gray-500 mb-1">声音气质 voiceVibe (录音师选 TTS 音色用)</label>
              <input v-model="form.protagonistVibe.voiceVibe" type="text" class="w-full px-3 py-2 border rounded text-sm" placeholder="如:少年清冷,带克制" />
            </div>
            <div>
              <label class="block text-xs font-medium text-gray-500 mb-1">语速节奏 speakingPace</label>
              <input v-model="form.protagonistVibe.speakingPace" type="text" class="w-full px-3 py-2 border rounded text-sm" placeholder="如:稍慢,留白多" />
            </div>
          </div>
        </section>

        <!-- keyMoments -->
        <section>
          <h3 class="text-sm font-semibold text-gray-700 mb-2">关键瞬间 (keyMoments)</h3>
          <p class="text-xs text-gray-500 mb-2">2-4 个,剪辑师会在这些时刻强调(in 推近 / 静止 / 高亮)</p>
          <div class="space-y-2 mb-3">
            <div v-for="(_m, idx) in form.keyMoments" :key="idx" class="flex gap-2 items-start">
              <input v-model="form.keyMoments[idx].time" type="text" class="w-28 px-2 py-1.5 border rounded text-sm" placeholder="时间锚点" />
              <input v-model="form.keyMoments[idx].what" type="text" class="flex-1 px-2 py-1.5 border rounded text-sm" placeholder="描述" />
              <button class="p-1.5 hover:bg-gray-100 rounded" @click="removeMoment(idx)"><X class="w-4 h-4" /></button>
            </div>
          </div>
          <div class="flex gap-2">
            <input v-model="newMomentTime" type="text" class="w-28 px-2 py-1.5 border rounded text-sm" placeholder="约第 30 秒" />
            <input v-model="newMomentWhat" type="text" class="flex-1 px-2 py-1.5 border rounded text-sm" placeholder="八岁登基那一刻" @keydown.enter.prevent="addMoment" />
            <button class="px-2 py-1 border rounded hover:bg-gray-50" @click="addMoment"><Plus class="w-4 h-4" /></button>
          </div>
        </section>

        <!-- highlightThemes -->
        <section>
          <h3 class="text-sm font-semibold text-gray-700 mb-2">字幕高亮主题词 (highlightThemes)</h3>
          <p class="text-xs text-gray-500 mb-2">3-8 个,副导演 LLM 提取关键词时优先匹配</p>
          <div class="flex flex-wrap gap-1.5 mb-2">
            <span
              v-for="(t, idx) in form.highlightThemes"
              :key="idx"
              class="inline-flex items-center gap-1 px-2 py-0.5 bg-amber-50 text-amber-800 text-xs rounded"
            >
              {{ t }}
              <button class="hover:text-amber-900" @click="removeTheme(idx)"><X class="w-3 h-3" /></button>
            </span>
          </div>
          <div class="flex gap-2">
            <input v-model="newTheme" type="text" placeholder="如:权谋" class="flex-1 px-3 py-1.5 border rounded text-sm" @keydown.enter.prevent="addTheme" />
            <button class="px-2 py-1 border rounded hover:bg-gray-50" @click="addTheme"><Plus class="w-4 h-4" /></button>
          </div>
        </section>

        <!-- directorNotes -->
        <section>
          <h3 class="text-sm font-semibold text-gray-700 mb-2">补充指令 (directorNotes)</h3>
          <textarea
            v-model="form.directorNotes"
            rows="4"
            placeholder="散文体补充:还想强调的、不能犯的错,如&quot;用第一人称'朕',慎用感叹号,情绪靠停顿不靠喊&quot;"
            class="w-full px-3 py-2 border rounded text-sm"
          />
        </section>
      </div>

      <footer class="px-6 py-4 border-t flex items-center justify-between gap-3">
        <p v-if="saveError" class="text-xs text-red-600 flex-1">{{ saveError }}</p>
        <p v-else class="text-xs text-gray-400 flex-1">tone/pacing/五段都填了才能保存</p>
        <button
          class="px-4 py-2 bg-blue-600 hover:bg-blue-700 disabled:opacity-50 text-white rounded text-sm flex items-center gap-1.5"
          :disabled="!canSave || saving"
          @click="save"
        >
          <Loader2 v-if="saving" class="w-4 h-4 animate-spin" />
          <Save v-else class="w-4 h-4" />
          保存
        </button>
      </footer>
    </aside>

    <DirectorNoteOptimizeDialog
      :topic-id="topicId"
      :open="optimizeOpen"
      :current-values="form"
      @close="optimizeOpen = false"
      @applied="onAiApplied"
    />
  </div>
</template>
