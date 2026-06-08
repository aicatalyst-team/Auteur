import { http } from './client'

/**
 * Preset 管理 REST 客户端。后端见 PresetController。
 *
 * 字段命名沿用后端 camelCase(JPA → Jackson 默认),前端不做二次转换 —— 保持简单。
 */

export interface Preset {
  id: number
  name: string
  displayName?: string | null
  description?: string | null
  visibility: 'private' | 'public'
  ownerName?: string | null
  inputSchemaJson?: any
  brainstormPromptYaml?: string | null
  scriptPromptYaml: string
  scriptCriticPromptYaml?: string | null
  scriptCriticThreshold?: number | null
  storyboardPromptYaml: string
  storyboardMode: 'PRECISE_BY_CUE' | 'FREE'
  assistantDirectorPromptYaml?: string | null
  bgmMoodPromptYaml?: string | null
  imageConfigJson?: any
  voiceConfigJson?: any
  bgmEnabled: boolean
  /** true = BGM 锁定模式,用户不让选,后端按 preset 兜底出曲。仅 bgmEnabled=true 时有意义。 */
  bgmLocked: boolean
  compositionId: string
  formatWidth: number
  formatHeight: number
  watermarkText?: string | null
  hookSegmentEnabled: boolean
  currentVersion: number
  createdAt?: string
  updatedAt?: string
}

export interface PresetVersion {
  id: number
  presetId: number
  version: number
  snapshotJson: string
  comment?: string | null
  createdAt: string
}

export interface PresetAsset {
  id: number
  presetId: number
  kind: string
  localPath: string
  uploadedAt: string
}

export async function listPresets(): Promise<Preset[]> {
  const { data } = await http.get<Preset[]>('/presets')
  return data
}

export async function getPreset(id: number): Promise<Preset> {
  const { data } = await http.get<Preset>(`/presets/${id}`)
  return data
}

export async function getPresetByName(name: string): Promise<Preset> {
  const { data } = await http.get<Preset>(`/presets/by-name/${encodeURIComponent(name)}`)
  return data
}

export async function createPreset(draft: Partial<Preset>): Promise<Preset> {
  const { data } = await http.post<Preset>('/presets', draft)
  return data
}

export async function updatePreset(id: number, patch: Partial<Preset>): Promise<Preset> {
  const { data } = await http.put<Preset>(`/presets/${id}`, patch)
  return data
}

export async function saveAsNewVersion(
  id: number,
  patch: Partial<Preset>,
  comment?: string,
): Promise<Preset> {
  const { data } = await http.post<Preset>(`/presets/${id}/save-version`, patch, {
    params: comment ? { comment } : {},
  })
  return data
}

export async function rollbackPreset(id: number, version: number): Promise<Preset> {
  const { data } = await http.post<Preset>(`/presets/${id}/rollback`, null, {
    params: { version },
  })
  return data
}

export async function deletePreset(id: number): Promise<void> {
  await http.delete(`/presets/${id}`)
}

export async function listPresetVersions(id: number): Promise<PresetVersion[]> {
  const { data } = await http.get<PresetVersion[]>(`/presets/${id}/versions`)
  return data
}

export async function listPresetAssets(id: number): Promise<PresetAsset[]> {
  const { data } = await http.get<PresetAsset[]>(`/presets/${id}/assets`)
  return data
}

/**
 * 沟通优化:用户在某 tab 描述对当前配置的不满,LLM 重新生成该 section 涉及的字段。
 * 不落库,前端拿到结果再决定是否写回 draft。LLM 调用慢,timeout 拉到 120s。
 */
export interface PresetOptimizeRequest {
  section: string
  userFeedback: string
  /** 用户在编辑器中尚未保存的草稿字段值,缺则后端读 DB 当前值。 */
  currentValues?: Record<string, any>
}

export interface PresetOptimizeResponse {
  section: string
  fields: Record<string, any>
  explanation?: string | null
}

export async function optimizePreset(
  id: number,
  req: PresetOptimizeRequest,
): Promise<PresetOptimizeResponse> {
  const { data } = await http.post<PresetOptimizeResponse>(`/presets/${id}/optimize`, req, {
    timeout: 120_000,
  })
  return data
}
