<script setup lang="ts">
/**
 * 简易 line-based diff 视图。不引入 diff 库,自己做 LCS 找最长公共子序列,标 added/removed/equal。
 *
 * 算法:经典 DP 求 LCS,然后回溯输出三类行(equal/add/del)。
 * 长文本(>500 行)性能 O(N*M) 可能慢,加个上限 fallback 到"上下并列"双块视图。
 */
import { computed } from 'vue'

const props = defineProps<{
  fieldName: string
  before: string
  after: string
  summary?: string | null
}>()

interface DiffLine {
  type: 'equal' | 'add' | 'del'
  text: string
}

const MAX_LINES_FOR_LCS = 500

const diffLines = computed<DiffLine[]>(() => {
  const a = (props.before ?? '').split('\n')
  const b = (props.after ?? '').split('\n')
  if (a.length > MAX_LINES_FOR_LCS || b.length > MAX_LINES_FOR_LCS) {
    // 太长跑 LCS 慢,降级:直接两段对照
    return [
      ...a.map((t) => ({ type: 'del' as const, text: t })),
      ...b.map((t) => ({ type: 'add' as const, text: t })),
    ]
  }
  return computeLcsDiff(a, b)
})

const stats = computed(() => {
  let add = 0, del = 0
  for (const l of diffLines.value) {
    if (l.type === 'add') add++
    else if (l.type === 'del') del++
  }
  return { add, del }
})

/**
 * LCS-based diff:
 *   1. 标准 DP 求最长公共子序列长度矩阵
 *   2. 回溯生成 (equal/add/del) 序列
 */
function computeLcsDiff(a: string[], b: string[]): DiffLine[] {
  const n = a.length, m = b.length
  const dp: number[][] = Array.from({ length: n + 1 }, () => new Array(m + 1).fill(0))
  for (let i = 1; i <= n; i++) {
    for (let j = 1; j <= m; j++) {
      if (a[i - 1] === b[j - 1]) dp[i][j] = dp[i - 1][j - 1] + 1
      else dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1])
    }
  }
  const out: DiffLine[] = []
  let i = n, j = m
  while (i > 0 && j > 0) {
    if (a[i - 1] === b[j - 1]) {
      out.push({ type: 'equal', text: a[i - 1] })
      i--; j--
    } else if (dp[i - 1][j] >= dp[i][j - 1]) {
      out.push({ type: 'del', text: a[i - 1] })
      i--
    } else {
      out.push({ type: 'add', text: b[j - 1] })
      j--
    }
  }
  while (i > 0) { out.push({ type: 'del', text: a[--i] }) }
  while (j > 0) { out.push({ type: 'add', text: b[--j] }) }
  return out.reverse()
}
</script>

<template>
  <div class="rounded border border-border-subtle overflow-hidden">
    <div class="px-3 py-1.5 bg-surface-tertiary text-[10px] flex items-center gap-2">
      <span class="font-mono text-text-primary">{{ fieldName }}</span>
      <span class="text-status-done">+{{ stats.add }}</span>
      <span class="text-status-failed">-{{ stats.del }}</span>
      <span v-if="summary" class="text-text-muted ml-auto truncate">{{ summary }}</span>
    </div>
    <div class="bg-surface-primary/50 max-h-80 overflow-auto font-mono text-[11px] leading-snug">
      <div
        v-for="(l, idx) in diffLines"
        :key="idx"
        :class="[
          'px-3 py-0.5 whitespace-pre-wrap break-words',
          l.type === 'add' && 'bg-status-done/10 text-status-done',
          l.type === 'del' && 'bg-status-failed/10 text-status-failed',
          l.type === 'equal' && 'text-text-muted',
        ]"
      >
        <span class="inline-block w-3 select-none">{{ l.type === 'add' ? '+' : l.type === 'del' ? '-' : ' ' }}</span><span>{{ l.text }}</span>
      </div>
    </div>
  </div>
</template>
