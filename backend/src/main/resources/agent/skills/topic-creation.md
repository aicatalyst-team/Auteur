---
name: topic-creation
summary: 创建/补全选题时的必填字段、流程、常见误区
when: 用户要新建选题；或选题刚创建但字段不全要补；或要从用户的零散描述里提取选题
---

# 创建选题(任务剧本)

## 触发本 skill 的信号

- 用户说"我想做 X 这个选题"、"帮我想个 X 主题"
- 准备调 `create_topic` / `update_topic` 之前
- 看到选题字段缺失(`get_topic` 返回 protagonist/directorNote 为空等)

## 字段要求(总览)

`create_topic` 工具的 schema 上 `title` 是唯一必填项,但**仅靠 title 跑下游会出垃圾**——脚本会瞎编主角、分镜没视觉锚点、出图凌乱。所以你的最低标准比 schema 严:

| 字段 | 你的最低标准 | 说明 |
|---|---|---|
| `title` | 必填 | 一句话主题 |
| `presetId` | 必填 | 决定内容形态(横屏纪录片/竖屏故事/锁脸短剧)。空了 `generate_script_from_topic` 直接失败 |
| `projectName` | **必让 LLM 起好的** | 4-10 字短名,有辨识度。**不允许靠兜底从 title 截前 10 字**——那样得到的常常是奇怪的半句话 |
| `protagonist` | 必填 | 主角设定 1-2 句。"30 岁外卖员小陈,城中村单亲爸爸,孩子重病急用钱"。**不允许填 `-` 或留空**,否则编剧 LLM 会自己瞎编 |
| `emotion` | 必填 | 情绪基调,1-3 个词。"嘲弄/紧张/悲悯"。会传给 BGM mood tagger 选曲 |
| `directorNote` | **必填,JSON 对象** | 跨角色共享的视觉/叙事方向。**字段是 MySQL JSON 列**,完整 schema 见下文"directorNote 的 JSON 结构"。**写"按预设跑"等于没写**;只传纯字符串工具会兜底包成 `{directorNotes:"..."}`,但 narrativeArc 等关键段会空着,下游编剧/摄影会失参考 |

填了更好(可选,但有就给):
- `dynasty` — 时代背景(古代/民国/当代/未来)
- `genre` — 题材类型(社会观察/现实主义/悬疑/...)
- `hookType` — 钩子类型(反转/悬念/共鸣/...)
- `durationMinutes` — 目标时长(分钟)
- `historicalReference` — 灵感来源/史料引用
- `presetInputJson` — 按 preset.input_schema_json 结构填的"身份标签卡"

## directorNote 的 JSON 结构

`Topic.directorNote` 不是纯字符串,是 **MySQL JSON 列**,前端 `DirectorNoteDrawer` 按结构化对象存储,共 8 个字段。这是给整个剧组(编剧/摄影/录音/美术)的共享方向。**调 create_topic / update_topic 时必须按这个结构传**:

```json
{
  "tone": "嘲弄中带悲悯,黑色幽默",
  "pacing": "前快后慢:钩子段 0-15s 节奏紧凑、揭秘段 30-60s 慢镜头放大情绪",
  "narrativeArc": [
    {"section": "A", "guidance": "钩子段:用'随手偷'的挑衅画面打开,让观众反感主角"},
    {"section": "B", "guidance": "累积段:外卖员追上来,主角狡辩、对方崩溃"},
    {"section": "C", "guidance": "中段转折:外卖员说出'孩子等饭',主角微露惊讶"},
    {"section": "D", "guidance": "揭秘段:旁观者掏手机录下主角嘲弄表情"},
    {"section": "E", "guidance": "留白段:视频在网上爆传,主角现实中被指认"}
  ],
  "visualStyle": {
    "palette": "冷灰主调,关键时刻局部暖色高光",
    "depthOfField": "手持纪实感,焦点跟随情绪",
    "lighting": "城市夜景,主光来自路灯/手机屏幕",
    "avoidWords": ["温馨", "明亮"]
  },
  "protagonistVibe": {
    "appearance": "20 岁左右大学生,白 T 恤帆布鞋,形象普通",
    "voiceVibe": "起初轻佻,后期颤抖",
    "speakingPace": "快语速,被识破后停顿"
  },
  "keyMoments": [
    {"time": "0:08", "what": "顺手抓走外卖,镜头特写手部"}
  ],
  "highlightThemes": ["社会性死亡", "网络曝光"],
  "directorNotes": "用户的额外补充指令,如'惩罚不要过度恶趣味,最后被录下传播即可'"
}
```

**字段说明**:
- `tone` — 整体语气基调
- `pacing` — 节奏指令
- `narrativeArc[A-E]` — 5 段叙事弧线,每段一句话指引(对应脚本的 A 钩子/B 累积/C 中段/D 揭秘/E 留白)
- `visualStyle` — 摄影/美术参考(palette / depthOfField / lighting / avoidWords)
- `protagonistVibe` — 主角印象(appearance / voiceVibe / speakingPace)
- `keyMoments` — 关键时间戳事件(可空数组)
- `highlightThemes` — 核心主题词(可空数组)
- `directorNotes` — 任何额外补充指令,如用户的特殊要求(本字段就是字符串,自由填)

**简化兜底**:LLM 真没把握把 8 字段都填准时,**至少**填 `tone` / `pacing` / `narrativeArc` 三个核心 — 脚本生成最依赖这些。其他字段空对象/空数组/空字符串都能接受。

## 操作流程

1. **追问缺失字段——一次问完**
   用户给的需求通常只够 1-2 个字段。**别一字段一回合**,把缺的字段一次性列出来让用户补。比如:

   > "好,做'偷外卖的人'这个选题。我需要再确认几个字段:
   > - 主角设定?(年龄/身份/处境,1-2 句)
   > - 情绪基调?(嘲弄/悲悯/紧张/...)
   > - 想用哪个预设?(你可以先 list_presets 看)
   > - 视觉方向?(冷写实/漫画风/手持纪实/...)"

2. **用户说"你看着办"才能你帮起**
   起完**贴出来让用户过目**再调工具。不要先调工具再让用户来纠错——审批卡看到不对的字段,用户拒绝、token 白烧。
   `directorNote` 这种结构化 JSON,用户看你贴的草稿(尤其 narrativeArc 5 段)是不是符合预期,再批准。

3. **调 `create_topic`,看返回 hint**
   工具返回的 `hint` 会暴露问题(`missingKeyFields`、`projectName 是兜底来的`、`directorNote 被自动包成纯文本` 等)。看到这种 hint **不要忽略**,主动跟用户说"刚才 X 字段我没填,要不要现在补?"然后调 `update_topic`。

4. **跟用户对完字段再进下一步**
   `directorNote` 是给后续编剧/摄影看的——你和用户在 chat 里说的细节都该浓缩进去。空了下游各角色会各自漂移。

## 常见误区

- ❌ 用户说"我想做 X 主题"就立刻调 `create_topic`,只填 title
- ❌ 用兜底逻辑(projectName 截 title 前 10 字、protagonist 留空)
- ❌ `directorNote` 写成"按预设跑"、"按用户的描述生成"——LLM 角色看到这种话等于没看到
- ❌ 一字段一回合追问,用户烦
- ❌ 创建完不检查 hint 就告诉用户"完成了"

## 一个完整范例

用户:"帮我做'偷外卖的人'这个选题"

✅ 你:
> 好,这个选题角度挺有意思——是想拍**外卖员视角的同情向**(比如生活所迫的瞬间)、还是**消费者视角的批判向**(随手偷的人不知道背后压力)?
> 
> 另外几个字段我需要你定一下:
> - 想用哪个预设?(我可以先 `list_presets` 给你看选)
> - 时长想做多长?(2 分钟短视频 / 4-5 分钟中视频)
> - 想要冷写实还是漫画风?
> - 情绪基调?

用户填完后:

✅ 你:
> 我帮你拼了一份草稿,过目一下:
> - **projectName**: "外卖员之夜"
> - **protagonist**: 32 岁男性外卖员,单亲爸爸,孩子白血病,日跑 14 小时凑不齐医药费
> - **emotion**: 悲悯,带点嘲弄
> - **directorNote**: "用 4 分钟讲一个'被偷的外卖'背后的双向沉重——表面是消费者愤怒、深层是骑手挣扎。镜头基调用冷灰色调写实风,雨夜手持感,主角脸大特写少用、多用动作侧写。情感转折点在 [B 钩子] 揭示孩子病情后,观众应该从'凭什么偷我'转到'我也会偷'。"
> 
> 没问题我就 `create_topic` 了。
