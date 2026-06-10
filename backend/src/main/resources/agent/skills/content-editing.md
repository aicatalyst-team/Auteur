---
name: content-editing
summary: 编辑业务内容(脚本段/分镜 prompt/品牌包/事实核查)的流程,使用 diff preview
when: 用户要改脚本某段、分镜 prompt、品牌包字段、应用事实核查修复
---

# 业务内容编辑(任务剧本)

## 触发本 skill 的信号

- 用户说"改脚本第 X 段"、"把 B 段改成..."
- 用户说"分镜第 N 镜的 prompt 加上 / 删掉..."
- 用户说"品牌色改成 X"、"修改 logo / 字体"
- 用户说"应用事实核查的修复建议"

## 工具一览

| 工具 | diff preview | 说明 |
|---|---|---|
| `update_script_section` | ✅ | 改 section.textContent + title;自动重建 fullText |
| `update_shot_prompt` | ✅ | 改 promptZh / promptEn / negativePrompt;子集修改 |
| `update_brand_identity` | ✅ | 改品牌包字段(brandName / colors / titleFont 等) |
| `apply_factcheck_fix` | ❌(内部跑 LLM) | 应用 issue 修复;preview 跟 execute 重复成本太高,跳过 |

带 ✅ diff preview 的工具:审批卡上**自动展示 before/after diff**——用户能直接看到你改的新内容跟旧内容的差异。**所以你不要在回复里再贴一遍新文本**,审批卡已经显示了。

## 必须遵守的规则

### 1. 先读后写 — 强制约束

调任何写工具前,**必须**先读到当前内容:

- 改 script section → 先 `get_script_summary(scriptId)` 拿到 sectionId 列表(注意 fullText 在 summary 里是 preview)
- 改 shot prompt → 先 `list_recent_scripts` 找到 scriptId,**当前缺一个 list_shots 工具**, 用户得在网页上找 shotId 给你
- 改 brand identity → **当前缺一个 get_brand_identity 工具**, 用户提供具体要改的字段值
- apply_factcheck_fix → 先 `dismiss_factcheck_issue` 之外可走 `get_topic` 看 issue,issueId 由用户给

不读直接写的后果:sectionId/shotId 不存在 → preview 失败 → tool 落 ERROR;或者新内容跟用户期望偏离。

### 2. 不要"顺手重构"

用户说"B 段第二句改成 X" → **只改那一句**,不要把整段 B 都重写一遍。最小变更原则:diff 越小,用户审核越快。

### 3. 截断标记不可幻觉

`get_preset` / `get_script_summary` / `list_critic_logs` 等工具的返回里出现下面三种标记之一时,**不能凭印象补全**:

- `{_truncated: true, originalLength, preview}` — 字段被工具截断
- `[...TRUNCATED: 原文共 N 字符,已保留前 M 字符...]` — 存储层截断尾部
- `[已折叠 N 条更早历史以节省 token]` — 老对话被折叠成 summary

遇到时:重新调对应读工具拉最新原文,或问用户;**绝不**基于截断片段调写工具。

## 操作流程

1. **读取当前内容** — 跟用户确认 ID(sectionId/shotId 等)正确
2. **跟用户口述改动方向**(不必贴具体文本) — 比如"把 B 段从'X 这件事让所有人愤怒'改成'X 这件事让消费者愤怒,但骑手却沉默'"
3. **调写工具** — 审批卡会显示 diff,用户批准就执行
4. **批准后告诉用户**:"已应用,fullText 已重建" / "shot #N 的 prompt 已改"。**不要再贴 diff**(审批卡已显示)

## apply_factcheck_fix 的特殊性

- 不带 diff preview(因为内部跑 LLM 决策替换串,跟 execute 重复一次成本翻倍)
- 用户在审批卡看到的是 issueId,不是 diff
- **建议在调用前**:先帮用户列一下这个 issue 的具体内容(`get_script_summary` 找 scriptId → 提示用户在网页 UI 看 issue 详情;当前没工具能直接读 issue),让用户知道**会改成什么**

## 常见误区

- ❌ 不读直接写 — sectionId 错了 preview 失败
- ❌ 在 LLM 回复里再贴一遍 diff(审批卡已显示)
- ❌ 用户说改 X 你顺手改了 X+Y(scope creep)
- ❌ 看到截断标记还基于片段写
- ❌ apply_factcheck_fix 不告诉用户具体 issue 是啥就直接申请审批
