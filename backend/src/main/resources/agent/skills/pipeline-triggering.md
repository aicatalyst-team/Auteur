---
name: pipeline-triggering
summary: 触发流水线 ACTION 工具的注意事项、轮询策略、长任务管理
when: 用户要重生成脚本/分镜/出图/配音/视频/封面/事实核查;或问"跑完了没"
---

# 触发流水线(任务剧本)

## 触发本 skill 的信号

- 用户说"重生成 X"、"重跑 X"、"出图"、"合成视频"等动作类需求
- 调任何 ACTION 工具之前(`regenerate_script` / `generate_storyboard` / `generate_images` / `audit_images` / `regenerate_image_for_shot` / `generate_voice` / `render_video` / `generate_covers` / `run_factcheck` / `brainstorm_topics` / `generate_script_from_topic` / `recommend_bgm` / `audit_image_asset` / `generate_voice_demo` / `recompute_potential_scores` / `attribute_video` / `generate_weekly_review`)
- 已经发起后用户问"好了吗"

## 共同特征

ACTION 工具都是**异步**的,立即返 `runId`,真任务在后台跑 30s 到 15 分钟不等。审批卡批准后:

1. **告诉用户预计耗时**(每个工具的 description 里写了)
2. **不要在对话里循环 `get_run_status` 轮询**
3. 用户问"好了吗"再调 `get_run_status(runId)`,**间隔至少 3-5 秒**
4. 状态 `DONE` 才能看产物;`FAILED` 时 run.errorMessage 有原因

## 各工具耗时与成本一览

| 工具 | 耗时 | 成本 | 备注 |
|---|---|---|---|
| `brainstorm_topics` | 30-90s | 1 次旗舰 LLM 调用 | 必填 presetId |
| `generate_script_from_topic` | 30-60s | LLM 调用 | 第一次生成,跟 regenerate_script 区别 |
| `regenerate_script` | 30-60s | LLM 调用 | version+1,改版重生 |
| `generate_storyboard` | 30-50s | LLM 调用(opus 之类) | force=true 覆盖已有 |
| `generate_images` | **5-8 分钟 + 模型费用** | 每镜 1 次出图调用 × 20-28 镜 | **成本敏感** — 跟用户确认后再批准 |
| `audit_images` | 2-4 分钟 | 每张 5-10s | |
| `regenerate_image_for_shot` | 10-30s | 1 次出图 | 单镜重生 |
| `generate_voice` | 20-40s(按字数) | TTS 费用 | 必传 voiceModel |
| `render_video` | **3-10 分钟** | CPU/磁盘重消耗 | 视频合成,真重操作 |
| `generate_covers` | 30-60s | LLM + 渲染 | 多 ratio 一起出 |
| `run_factcheck` | **3-5 分钟** | N × LLM 调用 | 每个声明 verify 一次 |
| `generate_weekly_review` | 1-2 分钟 | LLM 调用 | 样本不足 < 3 条返 fallback 不烧 |
| `recommend_bgm` | 5-10s | 调 Jamendo API | 失败提示 client_id 配置 |
| `attribute_video` | 30-60s | LLM 调用 | 单视频归因 |
| `recompute_potential_scores` | 10-30s | 纯算 | 批量更新 |
| `audit_image_asset` | 5-10s | 1 次 LLM 调用 | 单图重审 |
| `generate_voice_demo` | 同 voice+speed 命中缓存零成本 | TTS 费用 | 试听 |

## 操作流程

1. **跟用户解释这是 ACTION,会触发实际成本**
   尤其是 `generate_images` / `render_video` / `run_factcheck` 这种贵的或慢的。直接说出预计耗时和"会触发 LLM 调用,大概 X 分钟"。

2. **审批卡批准后,把 runId 告诉用户,然后停下来**
   ✅ 正确:"已发起,runId=42,预计 X 分钟。**你过几分钟回来问'好了吗'**,我那时候帮你查;也可以去网页 UI 看进度。"
   ❌ **禁止**说"我会监控/通知你/盯着进度/完成后立即告诉你"——**你做不到**。Agent 只在用户发消息时才被唤醒一次,没有常驻进程,**没有主动 push 消息的能力**。承诺"通知"等同于撒谎。

3. **不要主动循环轮询**
   用户问"好了吗"再调 `get_run_status`。**禁止**:
   - 立即轮询 5 次堆满上下文
   - 1 秒间隔狂查
   - 后台死等(agent 只在用户对话时活跃)
   - 用"我会继续监控"骗自己有持续性

4. **用户问"好了吗" / "进度如何"时**
   调 1 次 `get_run_status`,如实回答状态:
   - 还在 RUNNING → "还在跑,你可以再等 X 分钟回来问我"(给一个相对预计时间,**不要**说"我会再查")
   - DONE → 干活:展示结果或下一步建议
   - FAILED → 看 errorMessage 给修复建议

5. **失败处理**
   `get_run_status` 返 `FAILED` 时,看 `errorMessage`。常见错:
   - `LLM 网关超时` → 让用户重试(再次审批)
   - `TOS 上传失败` → 检查 `auteur.tos.*` 配置
   - `没有 voice asset` → 提示先 `generate_voice`
   - `没有 final image` → 提示先 `select_image_as_final` 或重出图

## 依赖关系(链路)

提醒用户合理的链路顺序:

```
brainstorm_topics 或 create_topic
    ↓
generate_script_from_topic (第一次生成) 或 regenerate_script (改版)
    ↓
generate_storyboard (分镜)
    ↓
generate_images (出图,看 review_issues 决定要不要 audit_images / regen)
    ↓
generate_voice (配音,会有 SRT 字幕)
    ↓
align_script_timing (配音后对齐时间戳,可选)
    ↓
render_video (合成最终视频,要 voice + 分镜图都齐了)
    ↓
generate_covers (封面;可以跟 render_video 并行,不互相依赖)
```

如果用户跳着调(比如没 voice 就 render_video),工具会失败。提前提醒比让用户撞错好。

## 常见误区

- ❌ 触发后立即调 `get_run_status`(刚发起肯定 PENDING/RUNNING)
- ❌ 没跟用户说成本就批准 `generate_images` 或 `render_video`
- ❌ FAILED 后不看 errorMessage 直接重试
- ❌ 用户说"等会儿再查"你后续轮一次 get_run_status 然后又一次又一次
- ❌ 跳过依赖链路顺序,比如没 voice 就 render_video
- ❌ **承诺主动通知**:"我会监控""完成后立即告诉你""我盯着进度"——agent 没有常驻能力,这种承诺等同于撒谎,用户等了半天没回应会觉得被骗。正确说法:"你过 X 分钟回来问我'好了吗'"
