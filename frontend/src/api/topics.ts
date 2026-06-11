import { http } from './client'
import type { DirectorNote, SpringPage, Topic, TopicStatus, Script } from '../types'

export async function listTopics(
  status: TopicStatus = 'DRAFT',
  page = 0,
  size = 30,
): Promise<SpringPage<Topic>> {
  const { data } = await http.get<SpringPage<Topic>>('/topics', {
    params: { status, page, size },
  })
  return data
}

export async function getTopic(id: number): Promise<Topic> {
  const { data } = await http.get<Topic>(`/topics/${id}`)
  return data
}

/** 多级链路追溯节点 —— 后端 GET /topics/{id}/lineage 返回。顺序:[起点, ..., 当前]。 */
export interface LineageNode {
  topicId: number
  title: string
  projectName: string | null
  latestScriptId: number | null
  /** 该节点是从哪条钩子兑现来的;起点为 null */
  hookSummary: string | null
  /** 该节点钩子来自哪条上集 script 的 E 段;起点为 null */
  fromScriptId: number | null
}

export async function getTopicLineage(id: number): Promise<LineageNode[]> {
  const { data } = await http.get<LineageNode[]>(`/topics/${id}/lineage`)
  return data
}

// PATCH:全字段 optional;后端只 apply 非 null 字段
export interface TopicUpdate {
  title?: string
  projectName?: string | null
  dynasty?: string | null
  genre?: string | null
  protagonist?: string | null
  hookType?: string | null
  emotion?: string | null
  durationMinutes?: number | null
  potentialScore?: number | null
  historicalReference?: string | null
  seriesId?: number | null
  status?: TopicStatus
  notes?: string | null
  /** 总导演笔记 JSON 字符串;前端 DirectorNoteDrawer 保存时 JSON.stringify 后传 */
  directorNote?: string | null
  /** 绑定 preset。 */
  presetId?: number | null
  /** 用户填的字段(per preset.input_schema);前端 PresetInputDrawer 保存时 JSON.stringify 后传 */
  presetInputJson?: string | null
}

export async function updateTopic(id: number, patch: TopicUpdate): Promise<Topic> {
  const { data } = await http.patch<Topic>(`/topics/${id}`, patch)
  return data
}

// 真删:有脚本会被后端 409 拒绝,提示走归档(PATCH status=ARCHIVED)
export async function deleteTopic(id: number): Promise<void> {
  await http.delete(`/topics/${id}`)
}

// 跟后端 BrainstormRequest 字段一一对应
export interface BrainstormRequest {
  /** 必填:走 preset.brainstorm_prompt_yaml 渲染。 */
  presetId: number
  n: number
  archiveHint?: string
  doneTopics?: string
  model?: string
  /** 数据驱动开关：true 时由后端用本号已发布数据替代 yaml 写死的权重表 */
  useDataDriven?: boolean
  /** 数据驱动口径：抖音 / B站 / 视频号 / 小红书 / 西瓜；空 = 全平台混合 */
  platform?: string
  /** 数据驱动统计窗口（天），默认 30 */
  windowDays?: number
}

export async function brainstormTopics(req: BrainstormRequest): Promise<Topic[]> {
  // brainstorm 是同步等 LLM,出 20 条通常 10-30s,拉到 120s 留余量
  const { data } = await http.post<Topic[]>('/topics/brainstorm', req, {
    timeout: 120_000,
  })
  return data
}

export async function generateScript(topicId: number): Promise<Script> {
  // script 走 claude-opus,实测 25-40s,偶尔会到 60s+ —— 显式拉长到 180s
  const { data } = await http.post<Script>(`/topics/${topicId}/scripts/generate`, null, {
    timeout: 180_000,
  })
  return data
}

// 异步路径:立即返回 runId,前端轮询 GET /api/runs/{runId},DONE 后用 run.scriptId 跳详情
export async function generateScriptAsync(topicId: number): Promise<{ runId: number }> {
  const { data } = await http.post<{ runId: number }>(`/topics/${topicId}/scripts/generate-async`)
  return data
}

/** 导演笔记 AI 智能填充:后端综合 currentValues + userFeedback 重写整份 DirectorNote。不落库。 */
export interface DirectorNoteOptimizeRequest {
  /** 用户自然语言诉求 */
  userFeedback: string
  /** 抽屉里的当前 form(可能含未保存草稿)。空 = 后端回落 DB 里的 directorNote */
  currentValues: DirectorNote | null
}

export interface DirectorNoteOptimizeResponse {
  note: DirectorNote
  explanation: string | null
}

export async function optimizeDirectorNote(
  topicId: number,
  req: DirectorNoteOptimizeRequest,
): Promise<DirectorNoteOptimizeResponse> {
  // 整份导演笔记 token 量大,LLM 20-40s 常见,留 120s 余量
  const { data } = await http.post<DirectorNoteOptimizeResponse>(
    `/topics/${topicId}/director-note/optimize`,
    req,
    { timeout: 120_000 },
  )
  return data
}
