---
name: preset-modification
summary: 修改/创建预设的标准流程、字段语义、版本管理
when: 用户要改某预设的字段、新建/复制预设、回滚版本;调任何 update_preset_field/save_preset_as_new_version/create_preset 之前
---

# 修改/创建预设(任务剧本)

## 触发本 skill 的信号

- 用户说"把 X 预设的 Y 字段改成 Z"
- 用户说"新建一个 X 预设"、"基于 lifecopy 复制一个我的版本"
- 用户说"回到上一版"、"昨天那个版本"

## 修改预设:三件套该用哪个

| 工具 | 何时用 | 是否写 snapshot |
|---|---|---|
| `update_preset_field` | 改单字段、改动小、不影响生成质量(阈值/水印/数字) | **不写**,直接覆盖当前版 |
| `save_preset_as_new_version` | 改 prompt yaml / image_config_json 等大块改动,影响下游产出 | **写一份快照** + currentVersion+1,可回滚 |
| `rollback_preset_version` | 用户说"回到上版"、"昨天那个" | 回滚到指定版本 |

判断标准很简单:**如果改完想保留可回滚的能力,就用 `save_preset_as_new_version`**。

## 操作流程

1. **先 `get_preset`(或 `list_presets`)** — 拿到当前值,把 oldValuePreview 引用给用户看
2. **跟用户确认改动** — 至少口述"我要把 X 字段从 'A' 改成 'B'",别直接调工具
3. **调写工具,带 comment**(用 save_preset_as_new_version 时) — comment 写"改了什么、为什么",方便日后翻 version 历史定位
4. **改完告诉用户**:新版本号是几、怎么回滚、改动是否会影响**正在跑的脚本/已生成的产物**(预设修改不影响历史 script,只影响新发起的 generate_*)

## 字段白名单(允许改的)

`displayName`、`description`、`brainstormPromptYaml`、`scriptPromptYaml`、`scriptCriticPromptYaml`、`scriptCriticThreshold`、`storyboardPromptYaml`、`storyboardMode`、`assistantDirectorPromptYaml`、`bgmMoodPromptYaml`、`imageConfigJson`、`voiceConfigJson`、`compositionId`、`formatWidth`、`formatHeight`、`watermarkText`、`hookSegmentEnabled`、`bgmEnabled`、`bgmLocked`、`minExtremeCloseup`、`hookPageFlipSoundUrl`

## 不允许改的字段(用户要求时明确告知)

- `name` — 内部 key,改了会破坏跨 service 的引用
- `visibility` — 公开/私有访问控制,涉及"可见性"威胁模型
- `ownerName` — 同上
- `inputSchemaJson` — 输入字段定义,改了影响所有引用此预设的 topic 校验

## 创建预设

调 `create_preset` 前,**先 `get_preset_by_name(freeform)` 拿一份基础模板**——所有必填字段都有合理默认。然后只覆盖你要改的几项。这比从零拼整段 prompt yaml 靠谱得多。

复制行为用 `duplicate_preset` 更方便——一次性把所有字段拷过来。

## prompt yaml 处理规则

修改 `*_prompt_yaml` 字段时:

- **保留原结构、键名、注释**——别因为改一个值顺手"重构"
- **整段替换是允许的**——但要确保新值是完整有效的 YAML
- **超过 4000 字会被 `get_preset` 截断**(你看到的是 `_truncated: true` 标记)。看到截断**严禁**基于 preview 调写工具,会把后段切掉。让用户去网页 UI 改,或拆段问"具体改哪一段"

## 常见误区

- ❌ 改 prompt yaml 用 `update_preset_field` — 没快照,搞砸不能回滚
- ❌ 不先 `get_preset` 看现状,凭用户描述写新值
- ❌ 改预设时附带改不相关字段(用户只说改阈值,你顺手改了水印)
- ❌ 看到 `{_truncated: true}` 还基于 preview 写回去 — 后段全切掉
- ❌ 改密钥类配置(`set_app_config`)时回写 mask 占位 `abcd****wxyz` — 工具层会拒,但你应该先就反应过来要求用户提供原值
