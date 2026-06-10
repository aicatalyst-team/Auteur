# Auteur — AI 助手 cheat sheet

> 这是给 Claude Code / Cursor / Copilot 等 AI 编程助手的项目 onboarding 指南。
> 用 AI 改这个项目时让它先读这份文件，能少走 90% 弯路。

## 项目一句话

**16 个 AI 角色 + 1 个 Agent 对话循环 + 流水线编排，端到端生产中文短视频。**
所有产物落 DB，每段可单独重跑、人工介入、回滚。

## 技术栈

| 层 | 技术 | 端口 |
|---|---|---|
| Backend | Spring Boot 3.3 + JPA + Flyway + MySQL 8.0 + Java 21 | 8082 |
| Frontend | Vue 3 + Vite + TypeScript + Pinia | 5174 |
| Renderer (可选) | Remotion (TypeScript) | — |
| Extension | Chrome Manifest V3（4 平台后台数据回写） | — |

## 仓库布局

```
backend/      Spring Boot；16 AI 角色 + Agent 循环 + 流水线编排 + REST API
  src/main/java/com/auteur/
    Application.java                # 入口
    agent/                          # AI Agent 对话循环 + 工具/Skill 注册
      tools/                        #   按主题切分的工具集
    bgm/ brainstorm/ cover/ image/  # 各 AI 角色（按职责分包）
    insights/ llm/ pipeline/ preset/
    published/ script/ storage/
    storyboard/ topic/ video/ voice/
    config/                         # WebMvc / Cors / 静态文件挂载
    bootstrap/                      # PresetSeeder / StartupSweep 等启动钩
    common/ domain/ web/            # 通用工具
  src/main/resources/
    application.yml                 # 通用配置
    application-local.yml           # 本机开发（gitignored）
    application-local.yml.example   # 模板
    application-docker.yml          # Docker 部署 profile
    db/migration/V*__*.sql          # Flyway 迁移
    preset_seeds/<name>/            # 种子预设（PresetSeeder 启动时注入）
    agent/skills/*.md               # Agent 自动加载的 skill 剧本
    agent/system_prompt.md          # Agent 系统提示

frontend/     Vue 3 + Vite，创作工作台 + 预设库 + AI Agent 聊天 UI
  src/views/                        # 顶层路由页（Topic/Script/Storyboard/Image/Chat...）
  src/components/                   # 复用组件（chat/preset/...）
  src/api/                          # axios wrappers
  src/lib/                          # 工具（封面 Canvas 渲染等）
  vite.config.ts                    # /api 代理到 :8082

renderer/     Remotion compositions（StoryHorizontal / StoryVertical）
extension/    抖音 / B 站 / 视频号 / 快手 浏览器扩展
```

## 启动方式

| 方式 | 命令 | 适合 |
|---|---|---|
| **Docker Compose** | `cp .env.example .env && docker compose up -d --build` | 跑 demo / 部署 |
| **本地开发 - Backend** | `cd backend && mvn spring-boot:run` | 改 Java |
| **本地开发 - Frontend** | `cd frontend && npm run dev` | 改 Vue |
| **本地开发 - Renderer** | `cd renderer && npm run dev` | 调 Remotion composition |

数据流：`topic → script → storyboard_shot → image_asset → voice_asset → video_asset → published_video → weekly_review`

## 关键约定（违反会出问题）

### 1. 改 schema → 加 Flyway migration

**绝对不要直接改 entity 然后让 JPA 自动建表**（`spring.jpa.hibernate.ddl-auto=validate`，会启动失败）。

正确做法：
1. 在 `backend/src/main/resources/db/migration/` 加 `V<下一个数字>__<描述>.sql`
2. **数字递增不能跳号**，描述用下划线分隔：`V8__add_topic_status_index.sql`
3. 同时改 entity 类
4. Flyway 启动时自动执行

### 2. 改预设相关代码 → 同步更新 seed 文件

预设由 `preset_seeds/<name>/` 下的 yaml/json 文件定义。`PresetSeeder` 在 `preset` 表为空时注入。如果你改了 PresetEntity 字段或 prompt 格式，一定要同步更新 seed 文件，否则新部署的实例会拿到陈旧默认值。

仓库内置 seed：
- **freeform** — 默认种入；通用基础模板（theme + tone + duration_minutes）
- **LifeCopy** — 代码内置但**默认不 seed**；横屏 1920×1080 + 锁脸人设 + 漫画风 + PRECISE_BY_CUE

### 3. 角色之间不直接 RPC，全部通过 DB 解耦

每个 AI 角色（Service）是一个独立单元：读上游 DB 表，写下游 DB 表。**不要在 Service A 里直接 `@Autowired` 调 Service B 的方法**——这会把流水线段间耦合，违反"任何一段都能单独重跑 / 人工介入"的设计哲学。

例外：跨角色共享的中央笔记走 `DirectorNoteService`（专门为这件事设计的）。

### 4. 添加 Agent 工具（自然语言驱动流水线）

Agent 工具定义位置：`backend/src/main/java/com/auteur/agent/tools/<Domain>Tools.java`。

加新工具流程：
1. 在对应 domain 的 `*Tools.java` 加一个 `@Component` 方法，用 `@Tool(name="...", description="...")` 标注
2. 工具自动被 `ToolRegistry` 扫描注册（启动日志会打 `[Agent] 工具注册: <name>`）
3. **写操作 / 不可逆操作** 必须实现 `PreviewableHandler`，前端会弹审批卡，用户确认后才执行
4. 长任务（生图、合成）要返 `runId`，前端轮询 `GET /api/runs/{id}` 看进度
5. 如果新工具属于一类成体系的操作（例如"调整内容"），写一份 `agent/skills/<topic>.md`，Agent 自动按需加载

### 5. 第三方密钥不在 yml 里

P7 重构后：LLM key / 火山 TTS / TOS / Jamendo / 浏览器扩展 token 全部迁到 `app_config` 表，前端「系统设置」UI 编辑。**不要往 application-local.yml 加新 key**——它只剩 MySQL 地址 + 技术性参数（ffmpeg 路径、超时等）。

### 6. 本地优先 + 可降级

任何外部依赖缺失，后端不应该启动失败：
- TOS 没配 → 走本地 storage + `/api/files/...` 静态服务
- 火山 TTS 没配 → 配音环节 disabled，前端显示 notice
- Jamendo 没配 → BGM 推荐 off，制片照常合成
- Remotion 没装 → ffmpeg 路径

加新依赖时务必维护这个性质（写降级代码 + 启动日志说明状态）。

## PR 验证清单

提交前确保：

```bash
# Backend 编译
cd backend && mvn -B -DskipTests compile

# Frontend 类型检查 + 构建
cd frontend && npm run build       # 等价于 vue-tsc -b && vite build

# Docker 镜像可构建（可选但推荐）
docker compose build
```

CI 会跑这三个 + Maven package。**TS 严格模式启用了 `noUnusedLocals`/`noUnusedParameters`**，未使用的变量/参数要么删掉，要么加 `_` 前缀（`_env`, `_logoImg`）。

## 容易踩的坑

- **改 `.gitignore` 必须用前导 `/` 锚定到 git 根**。git 的目录模式会**递归匹配所有层级**——`storage/` 不光会忽略 `backend/storage/`（产物目录），还会一并忽略 `backend/src/main/java/com/auteur/storage/`（业务包）；同理 `test/` 会误伤 `backend/src/test/`（Maven 标准测试目录）。**正确写法是 `/storage/` `/test/`，只匹配 git 根下的同名目录**。注意 `.dockerignore` 语义不同（用 Go `filepath.Match` 不递归），所以同样的 `storage/` 在 dockerignore 里就没问题——这种语义差异曾让本地 docker build 通过、CI 编译挂掉。改完 `.gitignore` 用 `git check-ignore -v <file>` 抽查关键源码目录验证。
- **alpine wget 解析 localhost 优先 IPv6**，但 nginx 默认只监听 IPv4 → healthcheck 永远失败。在 alpine 容器里用 `127.0.0.1` 不要用 `localhost`。
- **Spring Boot `ddl-auto: validate` 严格校验 entity ↔ schema**——加字段忘 migration 会启动失败。
- **mvn 镜像在国内**，docker build 第一次需要让 mvn 走国内 mirror（可在 backend Dockerfile 加 `~/.m2/settings.xml`）或者已经在 maven 中央仓 + Docker Hub 国内 mirror（`~/.docker/daemon.json`）。
- **静态文件路径** 走 `/api/files/<voice|sfx>/**`，不要直接拼 `file://`——Remotion 不支持 `file://` 协议，需要走 `auteur.video.remotion.public-base-url` 拼成 HTTP URL。

## 常用调试命令

```bash
# 看后端启动日志（找 Flyway / PresetSeeder / Tomcat 信号）
docker compose logs -f backend

# 看 Agent 工具注册情况
docker compose logs backend | grep "工具注册"

# 直连后端 REST
curl http://localhost:8082/api/presets

# 走 nginx 反代验证
curl http://localhost:5174/api/presets

# 手动触发选题脑暴（要先填好 LLM key）
curl -X POST http://localhost:8082/api/topics/brainstorm \
     -H 'Content-Type: application/json' \
     -d '{"presetId":4,"input":{"theme":"...","tone":"..."}}'
```

## 不要做的事

- ❌ 不要直接改 application-local.yml 加密钥（用 UI「系统设置」页）
- ❌ 不要把 Agent 工具方法写成 Service 之间互相调用的入口
- ❌ 不要把生成产物（voice/video/cover）commit 到仓库（已 gitignore）
- ❌ 不要在 Service 里直接 RPC 别的 Service —— 走 DB 解耦
- ❌ 不要为了改一个字段跳过 Flyway，直接改 entity
- ❌ 不要在 PR 里只改 backend 不改对应 frontend 的 API 调用 —— 一起改
