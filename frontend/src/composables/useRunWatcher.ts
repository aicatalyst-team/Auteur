import { ref } from 'vue'
import { getRun } from '../api/runs'
import type { PipelineRun, PipelineRunStatus } from '../types'

/**
 * Run 监视器:为 ACTION 工具触发产生的 runId 后台 polling,完成时回调。
 *
 * 设计:
 *   - **agent 不是常驻服务**,但 ChatVue 是常驻 UI。让 ChatVue 帮 agent 跑腿轮询,
 *     状态变化时**自动塞一条系统消息**给 agent 触发新一轮 chat。
 *   - 单例 composable:同一时刻只有一个 ChatVue 实例,共用一个 watcher 池。
 *
 * 自适应轮询频率(避免长任务一直猛查):
 *   < 5 分钟 :  每 10s
 *   5-15 分钟 : 每 30s
 *   > 15 分钟 : 自动放弃(用户大概率回头会主动问)
 */

interface WatchedRun {
  sessionId: number
  runId: number
  toolName: string
  startedAt: number
}

type Callback = (runId: number, run: PipelineRun, ctx: WatchedRun) => void

const watched = ref<WatchedRun[]>([])
let onCompleteCallback: Callback | null = null
let timer: number | null = null

/** 给定 startedAt(ms) 返回这一轮该睡多久才轮询。 */
function nextPollDelay(startedAt: number): number {
  const elapsedMin = (Date.now() - startedAt) / 60_000
  if (elapsedMin > 15) return -1 // 放弃
  if (elapsedMin > 5) return 30_000
  return 10_000
}

/** 终态:DONE / FAILED / CANCELLED;PAUSED 不算 — 被暂停的等用户恢复。 */
function isTerminal(status: PipelineRunStatus): boolean {
  return status === 'DONE' || status === 'FAILED' || status === 'CANCELLED'
}

async function pollOnce() {
  if (watched.value.length === 0) return
  // 复制一份遍历,polling 期间数组可能被修改
  const snapshot = [...watched.value]
  for (const w of snapshot) {
    const delay = nextPollDelay(w.startedAt)
    if (delay < 0) {
      console.warn(`[RunWatcher] runId=${w.runId} 超过 15 分钟未完成,停止监视`)
      removeWatch(w.runId)
      continue
    }
    try {
      const run = await getRun(w.runId)
      if (isTerminal(run.status)) {
        console.info(`[RunWatcher] runId=${w.runId} → ${run.status},触发回调`)
        removeWatch(w.runId)
        onCompleteCallback?.(w.runId, run, w)
      }
    } catch (e) {
      console.warn(`[RunWatcher] poll runId=${w.runId} 失败:`, (e as Error).message)
      // 不立即移除,下一轮再试;持续失败的最终会因 elapsed > 15min 被超时清理
    }
  }
}

function ensureTimer() {
  if (timer != null) return
  timer = window.setInterval(pollOnce, 10_000)
  // 立即跑一次,避免首次等待 10s
  setTimeout(pollOnce, 500)
}

function stopTimer() {
  if (timer != null) {
    clearInterval(timer)
    timer = null
  }
}

function removeWatch(runId: number) {
  watched.value = watched.value.filter((w) => w.runId !== runId)
  if (watched.value.length === 0) stopTimer()
}

/**
 * 注册一个 runId 进监视池。重复注册同 runId 自动去重。
 */
function watchRun(sessionId: number, runId: number, toolName: string) {
  if (!Number.isFinite(runId) || runId <= 0) return
  if (watched.value.some((w) => w.runId === runId)) return
  watched.value.push({ sessionId, runId, toolName, startedAt: Date.now() })
  console.info(`[RunWatcher] 监视 runId=${runId} (${toolName}) for session=${sessionId}`)
  ensureTimer()
}

/** 显式取消某 session 下所有监视(切会话/卸载时调,避免向旧 session 发系统消息) */
function clearForSession(sessionId: number) {
  watched.value = watched.value.filter((w) => w.sessionId !== sessionId)
  if (watched.value.length === 0) stopTimer()
}

/** 全清(组件卸载时调) */
function clearAll() {
  watched.value = []
  stopTimer()
}

/** 注册"完成回调";同一时刻只有一个 callback(后注册覆盖前) */
function onComplete(cb: Callback) {
  onCompleteCallback = cb
}

export function useRunWatcher() {
  return {
    watched,
    watchRun,
    clearForSession,
    clearAll,
    onComplete,
  }
}
