<script setup lang="ts">
/**
 * Agent 对话主界面。
 *
 * 状态:
 *   - sessions      左栏列表
 *   - activeId      当前选中会话
 *   - messages      当前会话消息流(初次进入 GET 历史,之后由 SSE 增量 push)
 *   - busy          一轮 chat 流期间为 true,期间禁输入
 *
 * SSE 事件处理:
 *   - user_saved      把刚发的用户消息正式塞进 messages(乐观插入的 placeholder 已经在了,这里更新 id)
 *   - assistant_done  追加一条 assistant 消息(可能 hasToolCalls=true)
 *   - tool_call       占位 placeholder tool 行,seq 暂用临时值
 *   - tool_result     用 messageId 替换/更新 placeholder
 *   - done            busy = false
 *   - error           弹错 + busy = false
 *
 * 刷新策略:done 事件后做一次 GET messages 兜底,把所有 SSE 期间错过的字段补齐(token/cost 等)。
 */
import { ref, onMounted, onBeforeUnmount, watch, nextTick } from 'vue'
import {
  type AgentSession,
  type AgentMessage,
  type AgentEventPayload,
  listSessions,
  createSession,
  getSessionMessages,
  deleteSession,
  sendChatStream,
  approveTool,
  cancelSession,
} from '../api/agent'
import SessionList from '../components/chat/SessionList.vue'
import MessageList from '../components/chat/MessageList.vue'
import ChatInput from '../components/chat/ChatInput.vue'
import ApprovalCard from '../components/chat/ApprovalCard.vue'
import ErrorBanner from '../components/ErrorBanner.vue'
import { useRunWatcher } from '../composables/useRunWatcher'

interface ApprovalRequest {
  id: string
  name: string
  argsJson: string
  risk: 'WRITE' | 'ACTION' | 'READ'
  timeoutSeconds: number
  diff?: { fieldName: string; before: string; after: string; summary?: string | null } | null
}

const sessions = ref<AgentSession[]>([])
const activeId = ref<number | null>(null)
const messages = ref<AgentMessage[]>([])
const busy = ref(false)
const errorMsg = ref<string | null>(null)
const scrollEl = ref<HTMLDivElement | null>(null)
const pendingApproval = ref<ApprovalRequest | null>(null)
// 审批提交态:仅给 ApprovalCard 用 — 调 approveTool API 时锁按钮 + 失败时把 decision 清掉允许重试
const approvalSubmitting = ref(false)
const approvalError = ref<string | null>(null)
let abortFn: (() => void) | null = null

// RunWatcher:ACTION 工具产生的 runId 后台轮询,完成时自动塞系统消息触发新一轮 chat
const runWatcher = useRunWatcher()
// busy 期间收到的完成通知排队,等 busy=false 再依次发出 — 避免 race(server 拒绝并发 chat)
const pendingNotifications = ref<string[]>([])

/**
 * 取消正在进行的 SSE 流。在切会话/删会话/卸载组件/出错时调用,避免后端 LLM 调用孤立运行。
 * 注意:fetch AbortController.abort() 只能切断前端的读流;后端的 LlmClient.chatWithTools 仍会跑完当前那一次 HTTP,
 * 但下一次 emitter.send() 会 IOException 而被 onError catch,turn() 整体退出。完美干净要等 AgentLoopService 接收 cancel 信号
 * (见 AgentController review 里 onTimeout/onError 的同类问题)。
 */
function cancelInFlight() {
  // 后端取消:让正在跑的 turn 立即抽身,不再花 LLM token。
  // fetch abort 只切断前端读流,后端默认会跑完当前 LLM 调用才退出 — 调 cancel 端点把信号塞进 registry。
  if (activeId.value != null && (busy.value || pendingApproval.value)) {
    cancelSession(activeId.value).catch((e) =>
      console.warn('[chat] cancelSession 失败,可能已结束:', (e as Error).message),
    )
  }
  if (abortFn) {
    abortFn()
    abortFn = null
  }
  pendingApproval.value = null
  approvalSubmitting.value = false
  approvalError.value = null
  busy.value = false
}

onMounted(async () => {
  // 注册 RunWatcher 完成回调:状态变 DONE/FAILED 时把通知排队,空闲时发出去触发新一轮 chat
  runWatcher.onComplete((runId, run, ctx) => {
    // 跨会话保护:回调时 activeId 可能已切走,通知只发给当时触发的 session
    if (ctx.sessionId !== activeId.value) {
      console.info(`[Chat] runId=${runId} 完成但 session 已切走,丢弃通知`)
      return
    }
    const verb = run.status === 'DONE' ? '已完成' : run.status === 'FAILED' ? '失败' : run.status
    const errSuffix = run.errorMsg ? ` 错误: ${run.errorMsg}` : ''
    const msg = `[系统通知] runId=${runId} (${ctx.toolName}) ${verb}。${errSuffix}`
    pendingNotifications.value.push(msg)
    flushNotifications()
  })

  try {
    sessions.value = await listSessions()
    if (sessions.value.length > 0) {
      await selectSession(sessions.value[0].id)
    } else {
      await createNewSession()
    }
  } catch (e) {
    errorMsg.value = (e as Error).message
  }
})

// 离开页面时一定要 cancel,否则 fetch 还在监听,后端继续推流量
onBeforeUnmount(() => {
  cancelInFlight()
  runWatcher.clearAll()
})

async function refreshSessions() {
  sessions.value = await listSessions()
}

async function createNewSession() {
  cancelInFlight()
  try {
    const s = await createSession()
    await refreshSessions()
    await selectSession(s.id)
  } catch (e) {
    errorMsg.value = (e as Error).message
  }
}

async function selectSession(id: number) {
  if (id === activeId.value && !busy.value) return
  cancelInFlight()
  activeId.value = id
  errorMsg.value = null
  try {
    messages.value = await getSessionMessages(id)
    await scrollToBottom()
  } catch (e) {
    errorMsg.value = (e as Error).message
  }
}

async function onDeleteSession(id: number) {
  if (!confirm('删除该会话及其全部消息?')) return
  if (id === activeId.value) cancelInFlight()
  try {
    await deleteSession(id)
    if (activeId.value === id) {
      activeId.value = null
      messages.value = []
    }
    await refreshSessions()
    if (sessions.value.length > 0 && activeId.value == null) {
      await selectSession(sessions.value[0].id)
    } else if (sessions.value.length === 0) {
      await createNewSession()
    }
  } catch (e) {
    errorMsg.value = (e as Error).message
  }
}

async function onSend(text: string) {
  if (!activeId.value || busy.value) return
  errorMsg.value = null
  busy.value = true

  // 锚定本轮所属的 session id;onSseDone 时如果 activeId 已切换到别的会话,
  // 不要用这个流的结果覆盖新会话的 messages。
  const sentSessionId = activeId.value

  // 乐观插入 user 占位行
  const placeholderId = -Date.now()
  const placeholder: AgentMessage = {
    id: placeholderId,
    sessionId: activeId.value,
    seq: messages.value.length + 1,
    role: 'user',
    content: text,
    toolCallsJson: null,
    toolCallId: null,
    toolName: null,
    toolArgsJson: null,
    toolStatus: null,
    inputTokens: null,
    outputTokens: null,
    durationMs: null,
    createdAt: new Date().toISOString(),
  }
  messages.value.push(placeholder)
  await scrollToBottom()

  abortFn = sendChatStream(
    activeId.value,
    text,
    (ev) => onSseEvent(ev, placeholderId, sentSessionId),
    () => onSseDone(sentSessionId),
    (e) => {
      errorMsg.value = e.message
      abortFn = null
      busy.value = false
    },
  )
}

function onSseEvent(ev: AgentEventPayload, placeholderId: number, sentSessionId: number) {
  // 防御:用户中途切了会话,这次流的事件不要塞给当前 messages
  if (sentSessionId !== activeId.value) return
  const sid = sentSessionId
  switch (ev.type) {
    case 'user_saved': {
      // 把乐观占位行的 id/seq 更新成真实值
      const idx = messages.value.findIndex((m) => m.id === placeholderId)
      if (idx >= 0) {
        messages.value[idx] = {
          ...messages.value[idx],
          id: ev.data.messageId,
          seq: ev.data.seq,
        }
      }
      break
    }
    case 'assistant_done': {
      messages.value.push({
        id: ev.data.messageId,
        sessionId: sid,
        seq: ev.data.seq,
        role: 'assistant',
        content: ev.data.content || '',
        toolCallsJson: ev.data.hasToolCalls ? '[]' : null,
        toolCallId: null,
        toolName: null,
        toolArgsJson: null,
        toolStatus: null,
        inputTokens: null,
        outputTokens: null,
        durationMs: null,
        createdAt: new Date().toISOString(),
      })
      break
    }
    case 'tool_result': {
      // 工具执行完毕 → 清空待批卡(approval 决定后或 READ 工具直接执行后都走这);连带清提交态
      if (pendingApproval.value && pendingApproval.value.id === ev.data.id) {
        pendingApproval.value = null
        approvalSubmitting.value = false
        approvalError.value = null
      }
      // ACTION 工具的结果常带 runId,把它注册进 RunWatcher 自动监视
      if (ev.data.status === 'OK' && ev.data.resultJson) {
        try {
          const parsed = JSON.parse(ev.data.resultJson)
          if (parsed && typeof parsed.runId === 'number' && parsed.runId > 0) {
            runWatcher.watchRun(sid, parsed.runId, ev.data.name)
          }
        } catch {
          // resultJson 不是 JSON / 没 runId,跳过
        }
      }
      messages.value.push({
        id: ev.data.messageId,
        sessionId: sid,
        seq: ev.data.seq,
        role: 'tool',
        content: ev.data.resultJson,
        toolCallsJson: null,
        toolCallId: ev.data.id,
        toolName: ev.data.name,
        toolArgsJson: null, // 后端事件没带 args,刷新时从 GET 拉
        toolStatus: ev.data.status,
        inputTokens: null,
        outputTokens: null,
        durationMs: null,
        createdAt: new Date().toISOString(),
      })
      break
    }
    case 'tool_call':
      // 占位事件,目前不渲染独立行(等 tool_result 一并入)
      break
    case 'tool_approval_request':
      pendingApproval.value = ev.data as ApprovalRequest
      approvalSubmitting.value = false
      approvalError.value = null
      break
    case 'error':
      errorMsg.value = ev.data?.message || '未知错误'
      break
  }
  scrollToBottom()
}

async function onApprovalDecided(payload: { toolCallId: string; approved: boolean; reason?: string }) {
  if (!activeId.value) return
  // 关键:不要乐观清空 pendingApproval — 否则 API 失败时用户连重试入口都没了。
  // 只切提交态,卡片继续展示;ApprovalCard 看到 submitting=true 自己锁按钮 + 显示"等服务器确认"。
  // 真正清掉卡片的时机是后端发来对应的 tool_result(成功/REJECTED/CANCELLED 都会触发)。
  approvalSubmitting.value = true
  approvalError.value = null
  try {
    const resp = await approveTool(activeId.value, payload.toolCallId, payload.approved, payload.reason)
    if (!resp.ok) {
      // 服务端反馈"已超时/SESSION 不匹配/已被先一步完成"等 — 决定其实没生效,告诉用户。
      // 卡片不立刻消失;后端会发对应的 tool_result 事件,届时由 SSE 路径清掉。
      approvalError.value = resp.note || '审批未生效'
    }
  } catch (e) {
    approvalError.value = '审批响应失败: ' + (e as Error).message
  } finally {
    approvalSubmitting.value = false
  }
}

async function onSseDone(sentSessionId: number) {
  abortFn = null
  busy.value = false
  // 兜底全量同步,补齐 args/cost 等。但只有当前依然是同一会话时才覆盖,否则会冲掉用户切到的新会话
  if (sentSessionId !== activeId.value) {
    await refreshSessions().catch(() => {})
    flushNotifications() // 即使切了 session,如果新 session 空闲也可以走通知
    return
  }
  if (activeId.value != null) {
    try {
      messages.value = await getSessionMessages(activeId.value)
      await refreshSessions()
    } catch (e) {
      errorMsg.value = (e as Error).message
    }
  }
  // chat 流彻底结束 → 看看有没有积压的 RunWatcher 通知,有就发一条触发新一轮 chat
  flushNotifications()
}

/**
 * 把队列里的"任务完成通知"作为 user message 发出去触发 agent 新一轮 chat。
 *
 * 调用时机:
 *   1. 从 RunWatcher 回调里(busy=true 时入队等这里)
 *   2. onSseDone 里(上一轮 chat 结束,可以发下一条了)
 *
 * 一次只发一条 — 发出去后 busy=true,后续等下一个 onSseDone 再发。
 */
function flushNotifications() {
  if (busy.value) return
  if (activeId.value == null) return
  if (pendingNotifications.value.length === 0) return
  const msg = pendingNotifications.value.shift()!
  // 直接调 onSend — 跟用户手动发消息走同一条路径
  onSend(msg)
}

async function scrollToBottom() {
  await nextTick()
  const el = scrollEl.value
  if (el) el.scrollTop = el.scrollHeight
}

watch(messages, () => scrollToBottom(), { deep: true })
</script>

<template>
  <div class="flex h-full">
    <SessionList
      :sessions="sessions"
      :active-id="activeId"
      @select="selectSession"
      @create="createNewSession"
      @delete="onDeleteSession"
    />

    <div class="flex-1 flex flex-col min-w-0">
      <header class="px-5 py-4 border-b border-border-subtle">
        <h1 class="text-base font-semibold text-text-primary">Agent 控制台</h1>
        <p class="text-xs text-text-muted mt-0.5">
          通过对话管理预设、提示词和系统配置 — 第一阶段:只读 + 配置写入
        </p>
      </header>

      <div ref="scrollEl" class="flex-1 overflow-y-auto bg-surface-primary">
        <div class="max-w-4xl mx-auto">
          <ErrorBanner v-if="errorMsg" :msg="errorMsg" class="m-4" />
          <MessageList v-if="messages.length > 0 || busy" :messages="messages" :busy="busy && !pendingApproval" />
          <div v-if="pendingApproval" class="px-4 pb-4">
            <ApprovalCard
              :request="pendingApproval"
              :submitting="approvalSubmitting"
              :submit-error="approvalError"
              @decided="onApprovalDecided"
            />
          </div>
          <div v-if="messages.length === 0 && !busy && !pendingApproval" class="px-8 py-16 text-center text-text-muted">
            <div class="text-base font-medium text-text-secondary mb-2">开始一个新对话</div>
            <div class="text-sm">
              输入 <code class="px-1 bg-surface-tertiary rounded">ping</code> 验证工具链路,
              或从右侧"动作目录"挑一个常用模板。
            </div>
          </div>
        </div>
      </div>

      <ChatInput :busy="busy" @send="onSend" />
    </div>
  </div>
</template>
