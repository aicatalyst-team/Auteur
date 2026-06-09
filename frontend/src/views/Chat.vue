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
} from '../api/agent'
import SessionList from '../components/chat/SessionList.vue'
import MessageList from '../components/chat/MessageList.vue'
import ChatInput from '../components/chat/ChatInput.vue'
import ActionPalette from '../components/chat/ActionPalette.vue'
import ErrorBanner from '../components/ErrorBanner.vue'

const sessions = ref<AgentSession[]>([])
const activeId = ref<number | null>(null)
const messages = ref<AgentMessage[]>([])
const busy = ref(false)
const errorMsg = ref<string | null>(null)
const scrollEl = ref<HTMLDivElement | null>(null)
const chatInputRef = ref<InstanceType<typeof ChatInput> | null>(null)
let abortFn: (() => void) | null = null

/**
 * 取消正在进行的 SSE 流。在切会话/删会话/卸载组件/出错时调用,避免后端 LLM 调用孤立运行。
 * 注意:fetch AbortController.abort() 只能切断前端的读流;后端的 LlmClient.chatWithTools 仍会跑完当前那一次 HTTP,
 * 但下一次 emitter.send() 会 IOException 而被 onError catch,turn() 整体退出。完美干净要等 AgentLoopService 接收 cancel 信号
 * (见 AgentController review 里 onTimeout/onError 的同类问题)。
 */
function cancelInFlight() {
  if (abortFn) {
    abortFn()
    abortFn = null
  }
  busy.value = false
}

onMounted(async () => {
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
    case 'error':
      errorMsg.value = ev.data?.message || '未知错误'
      break
  }
  scrollToBottom()
}

async function onSseDone(sentSessionId: number) {
  abortFn = null
  busy.value = false
  // 兜底全量同步,补齐 args/cost 等。但只有当前依然是同一会话时才覆盖,否则会冲掉用户切到的新会话
  if (sentSessionId !== activeId.value) {
    await refreshSessions().catch(() => {})
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
}

async function scrollToBottom() {
  await nextTick()
  const el = scrollEl.value
  if (el) el.scrollTop = el.scrollHeight
}

watch(messages, () => scrollToBottom(), { deep: true })

function onFillPrompt(text: string) {
  chatInputRef.value?.fillText(text)
}
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
          <MessageList v-if="messages.length > 0 || busy" :messages="messages" :busy="busy" />
          <div v-else class="px-8 py-16 text-center text-text-muted">
            <div class="text-base font-medium text-text-secondary mb-2">开始一个新对话</div>
            <div class="text-sm">
              输入 <code class="px-1 bg-surface-tertiary rounded">ping</code> 验证工具链路,
              或从右侧"动作目录"挑一个常用模板。
            </div>
          </div>
        </div>
      </div>

      <ChatInput ref="chatInputRef" :busy="busy" @send="onSend" />
    </div>

    <ActionPalette @fill-prompt="onFillPrompt" />
  </div>
</template>
