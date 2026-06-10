# Changelog

All notable changes to Auteur are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

### Added

### Changed

### Fixed

---

## [0.1.0] — 2026-06-10

🎬 **Auteur 的第一个公开版本**。一个由 16 位 AI 角色组成的剧组,端到端自动化生产中文短视频。

### Added

#### 16 位 AI 角色协作的虚拟剧组

每个角色独立 Spring Service,有自己的 prompt 模板、LLM 调用与重试策略、DB 产物表。

- **创意层** — 选题策划 / 编剧 / 编剧自审 / 史实核查 / 钩子分析师
- **视觉层** — 摄影指导 / 摄影自审 / 美术指导 / 审片官 / 封面设计
- **声音层** — 配音演员(火山豆包 TTS)/ 作曲选曲(Jamendo CC 协议曲库)
- **统筹层** — 总导演 / 制片(ffmpeg / Remotion 双 renderer)
- **复盘层** — 数据分析师 / 系列规划

#### 自动化剪辑

- **PRECISE_BY_CUE 模式** — 摄影指导给每个 shot 一段精确字面 anchor,后端把它在 SRT 时间轴上反查,镜头时长 = anchor 在 SRT 里实际占的秒数,**画面与字幕一帧不差**
- **一键合成** — `VideoAssemblyService` + `FfmpegVideoRenderer` / `RemotionVideoRenderer` 自动:解析 SRT → 算每镜真实时长 → 拼接 ImageClip → 烧字幕 → sidechaincompress BGM ducking → 直出 MP4
- 内置横屏 1920×1080 + 竖屏 1080×1920 双 composition

#### AI Agent 对话式控制

- 内置 `/chat` 工作台,40+ 工具覆盖选题 CRUD / 流水线触发 / 预设修改 / 内容编辑 / 资产发布
- 5 份 Skills(adjusting-content / pipeline-triggering / topic-creation / preset-modification / content-editing)按需自动加载
- 危险操作前端审批门槛
- 消息持久化,session 可中断 / 可继续 / 可回滚

#### 预设驱动

- 一行预设决定所有内容形态相关配置(prompt / 画幅 / 模型 / 音色 / composition / 锁脸 / BGM)
- 每次保存写一份 `preset_version` 快照,UI 一键回滚
- 仓库内置 `freeform`(默认 seed) + `LifeCopy`(代码内置但默认不 seed)
- 导出 = `GET /api/presets/{id}` JSON,导入 = `POST /api/presets`,可放进 Git 做版本控制

#### 数据回写驱动复盘(元学习层)

- `extension/` Chrome 扩展,插到抖音 / B 站 / 视频号 / 快手 创作者后台,自动抓播放数 / 完播率 / 互动数据
- `WeeklyReviewService` 每周做特征贡献度归因,反哺下一轮选题脑暴

#### 本地优先 + 可降级

- TOS / 火山 TTS / Jamendo / Remotion 都可选,缺哪个对应功能 graceful 关闭,后端不挂
- LLM 走 OpenAI 兼容协议,vLLM / DeepSeek / 智谱 / Anthropic 都能接

#### Docker Compose 一键启动

- `cp .env.example .env && docker compose up -d --build` 三分钟跑起来
- 三服务:auteur-mysql / auteur-backend(JRE 21 + ffmpeg + Noto CJK 字体)/ auteur-frontend(nginx serve + 反代 /api)
- Spring Boot Actuator `/actuator/health` 标准探活
- 持久化卷:`auteur-mysql`(DB)+ `auteur-storage`(生成产物)

#### 仓库基础设施

- MIT License
- GitHub Actions CI(backend mvn / frontend vue-tsc + vite build / docker compose build)
- `CLAUDE.md`(给 AI 助手的项目 onboarding cheat sheet)
- `CONTRIBUTING.md`(三本 cookbook:加新角色 / 新预设 / 新 Agent 工具)
- 中英文双语 README

### Architecture Highlights

- 数据流以 DB 表为骨架:`topic → script → storyboard_shot → image_asset → voice_asset → video_asset → published_video → weekly_review`
- 角色之间不直接 RPC,全部通过 DB 解耦 — 任意一段都能单独重跑、人工介入、回滚
- `pipeline_run` 状态机跟踪每段 PENDING / RUNNING / DONE / FAILED,前端轮询进度,断点续跑
- `DirectorNoteService` 维护跨角色的视觉/叙事中央笔记,所有下游角色都读,避免漂移

[Unreleased]: https://github.com/nxin-github/Auteur/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/nxin-github/Auteur/releases/tag/v0.1.0
