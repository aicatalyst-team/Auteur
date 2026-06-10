---
name: adjusting-content
summary: 用户提调整意见时该用 update_* 还是 regenerate_*,不要 delete + recreate
when: 用户对已生成的脚本/分镜/图/视频/封面/topic 提改进意见;说"调整/改/换/重生"等
---

# 调整已有内容(决策树)

## 触发本 skill 的信号

- 用户对**已经生成的**对象提改进意见:"B 段太弱"、"主角说错了"、"图风格不对"、"换个音色"
- 用户说"调一下"、"改一下"、"换一下"、"重新生成"
- 在你想调 `delete_*` + 接着调 `*_from_*` / `create_*` 之前

## 核心原则:不要 delete + recreate

LLM 看到"改"经常反射成 **删了重建** — **错误**。代价:
- 多个工具调用,**审批负担翻倍**(用户每次都要点)
- token 浪费(重新生成本来不需要变的部分)
- 丢失副产物:`reviewScore`、`critic_log`、`pipeline_run` 历史、已挑好的 `final` image、已对齐的时间戳

**正确流程**:先想能不能 `update_*` 局部改;不行再用对应的 `regenerate_*` 重生(版本号+1,但 topicId 等关联保留)。

## 决策树

### 调整脚本(`script`)

| 用户场景 | 推荐工具 | 为什么 |
|---|---|---|
| "B 段第二句改成 X" / "把这句换成 Y" | `update_script_section(scriptId, sectionId, newTextContent)` | 局部改,带 diff preview,只重建 fullText |
| "钩子段太弱,换个角度" / "整段重写" | `regenerate_script(scriptId, anchor="钩子要更悬疑")` | 整段重生,version+1,**保留 topicId 不动** |
| "整个脚本不行,重生" | `regenerate_script(scriptId, anchor=...)` | 同上 |
| **❌ 错的**: `delete_script` 再 `generate_script_from_topic` | | 丢 critic_log/reviewScore 历史;跟 regenerate_script 等价但代价高 |

### 调整分镜(`storyboard`)

| 用户场景 | 推荐工具 |
|---|---|
| "第 3 镜的 promptZh 加上 X" | `update_shot_prompt(shotId, promptZh=...)` |
| "第 3 镜的图换一张" | `regenerate_image_for_shot(shotId)` |
| "整个分镜风格不对,重出" | `generate_storyboard(scriptId, force=true)` |
| **❌ 错的**: 删 shot 再重建 | shot 没有 delete API |

### 调整图(`image_asset`)

| 用户场景 | 推荐工具 |
|---|---|
| "选另一张做 final" | `select_image_as_final(assetId)` |
| "某镜图重出一张" | `regenerate_image_for_shot(shotId)` |
| "整批图重出" | `generate_images(scriptId, force=true)` |
| "先改某镜 prompt,再用新 prompt 出图" | `update_shot_prompt` → `regenerate_image_for_shot` |
| "某张图风格不对要重审" | `audit_image_asset(assetId)` |

### 调整 voice / video / cover

| 用户场景 | 推荐工具 |
|---|---|
| "换个音色重配" | `generate_voice(scriptId, voiceModel=新音色)` 直接重生 |
| "重新合成视频" | `render_video(scriptId)` 直接重生(会用最新 voice + final 图) |
| "封面换风格" | `generate_covers(scriptId, templateId=新模板)` 直接重生 |
| **改完文案后想刷时间戳** | `align_script_timing(scriptId)` 纯算,不重生 |

### 调整 topic 字段(导演笔记/主角/预设)

| 用户场景 | 推荐工具 |
|---|---|
| "导演笔记加一段 X" | `update_topic(topicId, directorNote=完整 JSON)` |
| "主角设定改成 Y" | `update_topic(topicId, protagonist=...)` |
| "换个预设跑" | `update_topic(topicId, presetId=...)` |
| **改完 topic 字段后想看新效果** | `regenerate_script(scriptId, anchor="基于刚更新的 X")` 让下游重跑 |
| **❌ 错的**: 删 topic 再 create_topic | 级联删 script/shot/image,所有产物归零 |

### 调整事实核查 issue

| 用户场景 | 推荐工具 |
|---|---|
| "应用某条 fix 建议" | `apply_factcheck_fix(issueId)` |
| "这条不需要改" | `dismiss_factcheck_issue(issueId)` |
| **❌ 错的**: 改 section 文本绕开 issue | 留下 unresolved issue,下次审计还会跳出来 |

## 重生时:把用户的改动方向提炼到 `anchor`

`regenerate_script` 等工具的 `anchor` 字段:塞进 user prompt 末尾的额外指令,引导 LLM 关注本次改动方向。**用户提的修改意见应该浓缩到 anchor 里**:

✅ 好的 anchor:"钩子段要更悬疑,主角不要笑场;B 段加一个反转铺垫"
✅ 好的 anchor:"语气更克制,避开'好笑'之类的轻浮词"
❌ 没用的 anchor:"按用户要求改"、"重新生成"、""(空)

`anchor` 是 LLM 唯一能让重生**有方向**的杠杆。空 anchor 重生 = 跟原来一样的随机走,纯烧 token。

## 常见误区

- ❌ "调整"反射成"删了重建":多个工具调用 → 审批多次 → 用户烦
- ❌ 改 topic 字段后没让下游重跑(脚本/分镜还停在旧 topic 上)
- ❌ regenerate_* 时 anchor 留空或写"重新生成"
- ❌ 用户改一段就 regenerate_script 整个重生(应该用 update_script_section)
- ❌ 用户说"换张图",你 generate_images 整批重出(应该 regenerate_image_for_shot)
- ❌ 改 voice 用 delete + generate(voice 没 delete API,直接 generate_voice 重跑就行)

## 工作流模板:用户提改进意见时

1. **听清楚用户改的是哪一层**(字段 / 段 / 整个对象)
2. **查上面决策树挑工具**:能用 update_* 就 update_*
3. **如果用 regenerate_***:把用户的具体改进意见浓缩成一句 anchor(20-50 字)
4. **跟用户确认 anchor 内容再批准**:"我准备 regenerate_script(anchor='...'),你看这个方向对不对?"
5. **改 topic/preset 后想看下游变化** → 主动提议"要不要 regenerate_script 让脚本用新设定重生?"
