<script setup lang="ts">
// CoverDesigner —— per-script 封面设计页。后端 Java2D 渲染接管。
// 流程:
//   1. onMounted:listCovers + getCoverDefaults + listShots/listImages
//      - 已有 cover_asset → 表单从最新一行反构(用户改完再点"重新生成")
//      - 否则用 defaults 回填(topic.title + 钩子段)
//   2. 用户改文案/选模板/挑 hero
//   3. 点"生成 3 张封面" → POST generate-async → 拿 runId → 3s 轮询 → DONE 后再 listCovers
//   4. 右侧三比例区域:有 cover_asset 就显示 <img>,否则显示 CoverCanvas live 预览(让用户在生成前先看大概)
// embedded=true 模式给 CoverList 套娃用 —— 隐藏返回按钮 + 顶部 chrome
import { computed, onMounted, ref, watch } from 'vue'
import { ArrowLeft, Download, Image as ImageIcon, Loader2, RefreshCw, Sparkles } from 'lucide-vue-next'
import ErrorBanner from '../components/ErrorBanner.vue'
import CoverCanvas from '../components/cover/CoverCanvas.vue'
import {
  getScript, listImages, listShots, listCovers, getCoverDefaults, generateCoversAsync,
} from '../api/scripts'
import { useRunPoll } from '../composables/useRunPoll'
import { useBrandIdentity } from '../composables/useBrandIdentity'
import { useCoverDesign } from '../composables/useCoverDesign'
import { COVER_TEMPLATES } from '../lib/coverTemplates'
import { extractError } from '../lib/format'
import type {
  ImageAsset, StoryboardShot, CoverAsset, CoverRatio, PipelineRunStatus,
} from '../types'

const props = defineProps<{ scriptId: number; embedded?: boolean }>()
const scriptIdRef = computed(() => props.scriptId)

const { brand } = useBrandIdentity()
const { design, applyFromAsset } = useCoverDesign(scriptIdRef as any)

const scriptTitle = ref<string>('')
const shots = ref<StoryboardShot[]>([])
const images = ref<ImageAsset[]>([])
const covers = ref<CoverAsset[]>([])
const loading = ref(false)
const errorMsg = ref<string | null>(null)

// hero img element —— canvas live 预览要 HTMLImageElement
const heroImg = ref<HTMLImageElement | null>(null)
const logoImg = ref<HTMLImageElement | null>(null)

// canvas refs (作为 fallback 预览用,不再用作下载)
const canvas34 = ref<InstanceType<typeof CoverCanvas> | null>(null)
const canvas43 = ref<InstanceType<typeof CoverCanvas> | null>(null)
const canvas169 = ref<InstanceType<typeof CoverCanvas> | null>(null)

// 异步生成 run 状态
const runId = ref<number | null>(null)
const runStatus = ref<PipelineRunStatus | null>(null)
const runMsg = ref<string | null>(null)
const runProgress = ref<{ done: number; total: number }>({ done: 0, total: 3 })
const generating = ref(false)

// hero 候选:所有有 fileUrl 的 image_asset(不仅限 final)。final 用 isFinal 标记给前端高亮显示。
// 设计意图:final 是"主推图",但用户经常想从其它候选里挑一张当封面 hero(如有更"动作感"的镜头),
// 不应该被强制过滤掉。
const finalImageCandidates = computed(() => {
  return images.value
    .filter((img) => img.fileUrl)
    .map((img) => {
      const shot = shots.value.find((s) => s.id === img.shotId)
      return {
        id: img.id,
        url: img.fileUrl!,
        shotIndex: shot?.shotIndex ?? -1,
        shotType: shot?.shotType ?? null,
        isFinal: !!img.isFinal,
      }
    })
    .sort((a, b) => {
      // final 排前面,然后按 shotIndex
      if (a.isFinal !== b.isFinal) return a.isFinal ? -1 : 1
      return a.shotIndex - b.shotIndex
    })
})

// 按 ratio 分组取最新一张 cover_asset
const coversByRatio = computed<Record<CoverRatio, CoverAsset | null>>(() => {
  const out: Record<CoverRatio, CoverAsset | null> = { '3:4': null, '4:3': null, '16:9': null }
  for (const c of covers.value) {
    if (out[c.ratio] == null) out[c.ratio] = c    // covers 已按 id desc 排,首次遇到即最新
  }
  return out
})

// 主副标题双向绑定 — 底层 design.titleText 用 "\n" 拼"主\n副"。
// 模板拆主副(lifecopy-classic)或自然 wrap(其他模板,wrapText 已支持 \n)。
// 留空主标题 → 单行;留空副标题 → 单行(只显主)
const mainTitleInput = computed({
  get: (): string => {
    const t = design.value?.titleText || ''
    const lines = t.split('\n')
    return lines.length >= 2 ? lines[0] : ''
  },
  set: (val: string) => {
    if (!design.value) return
    const cur = design.value.titleText || ''
    const lines = cur.split('\n')
    const sub = lines.length >= 2 ? lines.slice(1).join('\n') : cur
    design.value.titleText = val.trim() ? `${val.trim()}\n${sub}` : sub
  },
})
const subTitleInput = computed({
  get: (): string => {
    const t = design.value?.titleText || ''
    const lines = t.split('\n')
    return lines.length >= 2 ? lines.slice(1).join('\n') : t
  },
  set: (val: string) => {
    if (!design.value) return
    const cur = design.value.titleText || ''
    const lines = cur.split('\n')
    const main = lines.length >= 2 ? lines[0] : ''
    design.value.titleText = main ? `${main}\n${val}` : val
  },
})

async function load() {
  loading.value = true
  errorMsg.value = null
  try {
    const [s, sh, im, cv, defaults] = await Promise.all([
      getScript(props.scriptId).catch(() => null),
      listShots(props.scriptId).catch(() => [] as StoryboardShot[]),
      listImages(props.scriptId).catch(() => [] as ImageAsset[]),
      listCovers(props.scriptId).catch(() => [] as CoverAsset[]),
      getCoverDefaults(props.scriptId).catch(() => ({ title: '' })),
    ])
    if (s?.script) {
      scriptTitle.value = `脚本 ${s.script.id} · v${s.script.version} · topic ${s.script.topicId}`
    } else {
      scriptTitle.value = `脚本 ${props.scriptId}`
    }
    shots.value = sh
    images.value = im
    covers.value = cv

    // 表单回填:已生成过 → 反构最新一行;否则 defaults
    if (cv.length > 0) {
      const latest = cv[0]
      applyFromAsset({
        templateId: latest.templateId,
        titleText: latest.titleText,
        heroImageUrl: latest.heroImageUrl,
      })
    } else if (design.value) {
      design.value.titleText = defaults.title || design.value.titleText
    }
  } catch (e: any) {
    errorMsg.value = extractError(e, 'fetch failed')
  } finally {
    loading.value = false
  }
}

function loadImageElement(url: string | null, target: 'hero' | 'logo') {
  if (!url) {
    if (target === 'hero') heroImg.value = null
    else logoImg.value = null
    return
  }
  const img = new Image()
  img.crossOrigin = 'anonymous'
  img.onload = () => {
    if (target === 'hero') heroImg.value = img
    else logoImg.value = img
  }
  img.onerror = () => {
    if (target === 'hero') heroImg.value = null
    else logoImg.value = null
  }
  img.src = url
}

watch(() => brand.value.logoDataUrl, (v) => loadImageElement(v, 'logo'), { immediate: true })
watch(() => design.value?.heroImageUrl, (v) => loadImageElement(v ?? null, 'hero'), { immediate: true })

function pickHero(url: string) {
  if (!design.value) return
  design.value.heroImageUrl = url
  design.value.heroSource = 'storyboard'
}

function clearHero() {
  if (!design.value) return
  design.value.heroImageUrl = null
}

const poll = useRunPoll({
  onUpdate: (r) => {
    runStatus.value = r.status
    runProgress.value = { done: r.lastCompletedIndex ?? 0, total: r.totalItems ?? 3 }
  },
  onDone: async () => {
    generating.value = false
    runMsg.value = '生成 ✓'
    covers.value = await listCovers(props.scriptId)
  },
  onFailed: (r) => {
    generating.value = false
    runMsg.value = `失败:${r.errorMsg ?? r.status}`
  },
})

async function triggerGen() {
  if (!design.value) return
  poll.reset()
  generating.value = true
  runStatus.value = 'PENDING'
  runMsg.value = null
  runProgress.value = { done: 0, total: 3 }
  errorMsg.value = null
  try {
    const { runId: rid } = await generateCoversAsync(props.scriptId, {
      templateId: design.value.templateId,
      titleText: design.value.titleText,
      heroImageUrl: design.value.heroImageUrl,
    })
    runId.value = rid
    await poll.start(rid)
  } catch (e: any) {
    generating.value = false
    runStatus.value = 'FAILED'
    runMsg.value = `失败:${extractError(e)}`
  }
}

function downloadCover(c: CoverAsset) {
  // file_url 同源,直接打开;浏览器 PNG 直接渲染。要触发下载就用 <a download>
  const a = document.createElement('a')
  a.href = c.fileUrl
  a.download = `cover-${props.scriptId}-${c.ratio.replace(':', 'x')}.png`
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
}

onMounted(load)
</script>

<template>
  <div :class="[embedded ? '' : 'min-h-full']">
    <!-- 非嵌入:sticky 顶栏 -->
    <div v-if="!embedded" class="sticky top-0 z-10 bg-surface-primary border-b border-border-subtle">
      <div class="px-8 py-3 max-w-[1400px] mx-auto flex flex-col gap-1">
        <div class="flex items-center gap-4 flex-wrap">
          <button
            class="flex items-center gap-1.5 text-sm text-text-muted hover:text-text-primary"
            @click="$router.push(`/scripts/${scriptId}`)"
          >
            <ArrowLeft :size="14" /> 返回脚本
          </button>
          <h1 class="text-lg font-semibold">封面设计器</h1>
          <span v-if="covers.length > 0" class="chip text-[11px] bg-status-done/15 text-status-done">
            已生成 {{ covers.length }} 张
          </span>
        </div>
        <div class="text-xs text-text-muted">{{ scriptTitle }}</div>
      </div>
    </div>

    <div :class="[embedded ? '' : 'px-8 py-5 max-w-[1400px] mx-auto']">
      <ErrorBanner :msg="errorMsg" />

      <div v-if="!design" class="card p-12 text-center text-text-muted">
        <Loader2 :size="20" class="animate-spin mx-auto" />
      </div>

      <template v-else>
        <!-- 操作条 -->
        <div class="card p-3 mb-4 flex items-center justify-between flex-wrap gap-3">
          <div class="text-sm flex items-center gap-2 flex-wrap">
            <span class="text-text-secondary">{{ scriptTitle || `script ${scriptId}` }}</span>
            <span v-if="embedded && covers.length > 0" class="chip bg-status-done/15 text-status-done text-xs">
              已生成 {{ covers.length }} 张
            </span>
          </div>
          <div class="flex items-center gap-3 flex-wrap">
            <span v-if="generating" class="text-xs text-status-running flex items-center gap-1.5">
              <Loader2 :size="11" class="animate-spin" />
              run {{ runId }} · {{ runProgress.done }}/{{ runProgress.total }}
            </span>
            <span v-else-if="runMsg" class="text-xs"
                  :class="runMsg.startsWith('失败') ? 'text-status-failed' : 'text-status-done'">
              {{ runMsg }}
            </span>
            <button class="btn-primary" :disabled="generating" @click="triggerGen">
              <Loader2 v-if="generating" :size="13" class="animate-spin" />
              <Sparkles v-else-if="covers.length === 0" :size="13" />
              <RefreshCw v-else :size="13" />
              {{ generating ? '生成中…' : (covers.length === 0 ? '生成 3 张封面' : '重新生成 3 张') }}
            </button>
          </div>
        </div>

        <div class="grid grid-cols-1 lg:grid-cols-[380px_1fr] gap-4">
        <!-- 左侧编辑表单 -->
        <div class="space-y-4">
          <div class="card p-5">
            <h2 class="text-base font-semibold mb-3">文案</h2>
            <div class="space-y-3 text-sm">
              <div>
                <div class="text-xs text-text-muted mb-1">主标题(小字 · 可空)</div>
                <input v-model="mainTitleInput"
                       placeholder="例:今天体验的人生副本(留空只显大字)"
                       class="w-full bg-surface-tertiary border border-border-subtle rounded px-2 py-1.5" />
              </div>
              <div>
                <div class="text-xs text-text-muted mb-1">副标题(大字主体)</div>
                <textarea v-model="subTitleInput" rows="2"
                          placeholder="例:高考超水平发挥的一生 / 朱元璋的死藏着六百年的秘密"
                          class="w-full bg-surface-tertiary border border-border-subtle rounded px-2 py-1.5 resize-y" />
              </div>
              <div class="text-[10px] text-text-muted">
                "录取通知书"模板会把主标题作小字、副标题作大字两行显示;其他模板按 wrap 自然展开。
              </div>
            </div>
          </div>

          <div class="card p-5">
            <h2 class="text-base font-semibold mb-3">模板</h2>
            <div class="grid grid-cols-2 gap-2">
              <button
                v-for="t in COVER_TEMPLATES" :key="t.id"
                :class="['p-3 rounded border text-left transition-colors',
                         design.templateId === t.id
                           ? 'border-accent bg-accent-soft text-accent'
                           : 'border-border-subtle bg-surface-tertiary text-text-secondary hover:border-accent/40']"
                @click="design.templateId = t.id"
              >
                <div class="text-sm font-semibold">{{ t.name }}</div>
                <div class="text-xs text-text-muted mt-1 leading-snug">{{ t.description }}</div>
              </button>
            </div>
          </div>

          <div class="card p-5">
            <div class="flex items-center justify-between mb-3">
              <h2 class="text-base font-semibold flex items-center gap-2">
                <ImageIcon :size="16" class="text-accent" /> 主图
              </h2>
              <button v-if="design.heroImageUrl" class="text-xs text-text-muted hover:text-status-failed"
                      @click="clearHero">
                清除
              </button>
            </div>

            <div v-if="design.heroImageUrl" class="mb-3">
              <img :src="design.heroImageUrl" class="max-h-32 rounded border border-border-subtle" />
              <p class="text-xs text-text-muted mt-1">来源:storyboard 关键帧</p>
            </div>

            <div class="text-xs text-text-muted mb-2">从 storyboard 选任一张已生成图作 hero(★ = 该镜的 final):</div>
            <div v-if="loading" class="py-3 text-center">
              <Loader2 :size="16" class="animate-spin text-text-muted mx-auto" />
            </div>
            <div v-else-if="finalImageCandidates.length === 0" class="text-xs text-text-muted py-3">
              这条 script 还没有任何已生成的图。先去 <a class="underline cursor-pointer"
              @click="$router.push(`/images/${scriptId}`)">/images/{{ scriptId }}</a> 触发生图。
            </div>
            <div v-else class="grid grid-cols-3 gap-2 max-h-56 overflow-y-auto">
              <button
                v-for="c in finalImageCandidates" :key="c.id"
                :class="['relative rounded border overflow-hidden cursor-pointer',
                         design.heroImageUrl === c.url
                           ? 'border-accent ring-2 ring-accent/40'
                           : 'border-border-subtle hover:border-accent/50']"
                @click="pickHero(c.url)"
              >
                <img :src="c.url" class="w-full h-20 object-cover" />
                <span class="absolute top-0.5 left-0.5 chip bg-black/60 text-white text-[9px] px-1 py-0">
                  {{ c.shotIndex }}
                </span>
                <span v-if="c.isFinal"
                  class="absolute top-0.5 right-0.5 chip bg-status-done/80 text-white text-[9px] px-1 py-0"
                  title="该 shot 的 final image">★</span>
              </button>
            </div>
          </div>

          <div class="card p-4 text-xs">
            <div class="flex items-center justify-between">
              <span class="text-text-muted">品牌包(全局共享)</span>
              <button class="text-accent hover:underline" @click="$router.push('/brand')">编辑 →</button>
            </div>
            <div class="mt-2 flex items-center gap-1.5">
              <div class="h-5 w-5 rounded" :style="{ background: brand.primaryColor }" :title="brand.primaryColor" />
              <div class="h-5 w-5 rounded" :style="{ background: brand.secondaryColor }" :title="brand.secondaryColor" />
              <div class="h-5 w-5 rounded" :style="{ background: brand.accentColor }" :title="brand.accentColor" />
              <div class="h-5 w-5 rounded border border-border-subtle"
                   :style="{ background: brand.bgColor }" :title="brand.bgColor" />
              <span class="ml-2 text-text-muted truncate">{{ brand.brandName || '未设频道名' }}</span>
            </div>
          </div>
        </div>

        <!-- 右侧三比例 —— 后端图优先,否则 canvas live 预览 -->
        <div class="space-y-4">
          <div class="card p-5">
            <h2 class="text-base font-semibold mb-4">三比例预览</h2>

            <div class="space-y-6">
              <div v-for="r in (['3:4', '4:3', '16:9'] as const)" :key="r"
                   class="flex flex-col md:flex-row md:items-start gap-4"
                   :class="r !== '3:4' ? 'pt-4 border-t border-border-subtle' : ''">
                <div>
                  <div class="text-xs text-text-muted mb-1.5 font-mono">
                    {{ r }} ·
                    <template v-if="r === '3:4'">1080×1440</template>
                    <template v-else-if="r === '4:3'">1440×1080</template>
                    <template v-else>1920×1080</template>
                  </div>
                  <!-- 已有后端图 -->
                  <img v-if="coversByRatio[r]"
                       :src="coversByRatio[r]!.fileUrl"
                       :alt="`cover ${r}`"
                       class="rounded border border-border-subtle bg-surface-tertiary"
                       :style="{ width: r === '3:4' ? '280px' : (r === '4:3' ? '320px' : '420px') }" />
                  <!-- 未生成 → live canvas 预览 -->
                  <CoverCanvas v-else
                    :ref="(el: any) => { if (r === '3:4') canvas34 = el; else if (r === '4:3') canvas43 = el; else canvas169 = el }"
                    :ratio="r" :brand="brand" :design="design"
                    :heroImg="heroImg" :logoImg="logoImg"
                    :displayWidth="r === '3:4' ? 280 : (r === '4:3' ? 320 : 420)" />
                </div>
                <div class="flex-1 text-xs text-text-muted leading-relaxed pt-6">
                  <div class="text-text-secondary text-sm mb-1">
                    <template v-if="r === '3:4'">小红书 / 微信视频号封面</template>
                    <template v-else-if="r === '4:3'">微博 / 视频号横向封面</template>
                    <template v-else>B站 / YouTube 封面</template>
                  </div>
                  <template v-if="coversByRatio[r]">
                    <div class="text-text-muted">
                      文件:{{ ((coversByRatio[r]!.fileSizeBytes ?? 0) / 1024).toFixed(0) }} KB
                    </div>
                    <button class="btn-ghost mt-3 text-xs"
                            @click="downloadCover(coversByRatio[r]!)">
                      <Download :size="12" /> 下载 PNG
                    </button>
                  </template>
                  <template v-else>
                    <div class="text-text-muted">
                      还没生成 —— 上方点"生成 3 张封面"
                    </div>
                  </template>
                </div>
              </div>
            </div>
          </div>

          <div class="card p-4 text-xs text-text-muted leading-relaxed">
            💡 改主色 / 字体 / logo 去 <a class="text-accent hover:underline cursor-pointer"
            @click="$router.push('/brand')">/brand</a>,这里只管这一条视频的标题、模板和主图。
            后端 Java2D 渲染,导出图原始分辨率直接达标。
          </div>
        </div>
      </div>
    </template>
  </div>
  </div>
</template>
