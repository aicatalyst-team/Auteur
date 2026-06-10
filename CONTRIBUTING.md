# 给 Auteur 贡献代码

感谢愿意为 Auteur 出力。这份指南帮你在 30 分钟内提出第一个 PR。

## 🚀 第一次贡献

```bash
# 1. Fork 这个仓库到你自己的 GitHub
# 2. clone 你的 fork
git clone git@github.com:<your-username>/Auteur.git
cd Auteur

# 3. 起服务（推荐 Docker，3 分钟跑通）
cp .env.example .env
docker compose up -d --build

# 4. 验证：浏览器打开 http://localhost:5174，能看到首页就 OK
```

## 📝 PR 流程

1. 从 `main` 切一条 feature branch：`git checkout -b feat/your-thing`
2. 在你的 branch 上提交修改（commit message 风格见下文）
3. 提交前本地跑通 [PR 验证清单](#pr-验证清单)
4. push 到你的 fork → 在 GitHub 上发 PR 到本仓库 `main` 分支
5. 等 CI 跑过 + maintainer review → 合并

## 🍳 Cookbook

### 加一个新 AI 角色

假设要加一个「⚖️ 风险审查官」：脚本生成后扫描合规风险并打分。

1. **建包**：`backend/src/main/java/com/auteur/riskreview/`
2. **写 Service**：`RiskReviewService.java`
   ```java
   @Service
   @RequiredArgsConstructor
   public class RiskReviewService {
       private final ScriptRepository scriptRepo;          // 读上游
       private final RiskReviewRepository reviewRepo;      // 写自己产物
       private final LlmClient llm;
       private final PromptLoader prompts;

       public RiskReviewResult review(Long scriptId) {
           Script script = scriptRepo.findById(scriptId).orElseThrow();
           String prompt = prompts.load("risk_review.yaml")
               .render(Map.of("script", script.getFullText()));
           // ... 调 LLM，落 review_log 表
       }
   }
   ```
3. **加 Flyway migration**：`V<下一个数字>__risk_review_log.sql` 建表
4. **加 entity / repository**：`RiskReviewLog.java` + `RiskReviewRepository.java`
5. **REST 端点**：`backend/src/main/java/com/auteur/web/RiskReviewController.java`
6. **流水线钩子**：在 `PipelineRunService` 里在脚本生成后调 `riskReviewService.review(...)`，写 `pipeline_run` 状态
7. **前端**：在 `frontend/src/views/ScriptDetail.vue` 加一个"风险审查结果"卡片
8. **文档**：在主 README 的角色表里加一行

### 加一个新预设（不用改代码）

预设由 `preset_seeds/<name>/` 下的纯文件定义。如果你只是想做一种新的内容形态：

1. UI 里「预设库」→「新建预设」（admin 模式）
2. 调通后用「导出 JSON」拿到完整定义
3. 把 JSON 拆成 `preset_seeds/<your-preset>/preset.json` + `prompts/*.yaml`
4. 在 `PresetSeeder` 的 seedable 列表加上你的 preset name
5. PR

> 想代码内置但不默认 seed？参考 `LifeCopy`：seed 文件在但不在默认注入名单里，靠 admin 手动启用。

### 加一个新 Agent 工具

让 AI 能通过 `/chat` 自然语言驱动一个新动作。

1. **找对应的 domain Tools 类**：`backend/src/main/java/com/auteur/agent/tools/`
   - 选题相关 → `TopicTools.java`
   - 流水线触发（异步生成）→ `PipelineTriggerTools.java`
   - 内容编辑（同步小改动）→ `ContentWriteTools.java`
   - 预设修改 → `PresetWriteTools.java`
   - …
2. **加 method**：
   ```java
   @Tool(
       name = "regenerate_image_for_shot",
       description = "Regenerate one shot's image with optional style override. " +
                     "Use when user asks to redo a single shot (e.g. \"shot 5 darker\")."
   )
   public ToolResult regenerateImageForShot(
       @Param("shotId") Long shotId,
       @Param(value = "styleHint", required = false) String styleHint
   ) {
       // ...
   }
   ```
3. **写操作要审批**：实现 `PreviewableHandler`，提供 preview 给前端审批卡
4. **长任务返 runId**：异步触发 + 立即返 `{runId: ..., estimatedSeconds: 60}`
5. **如果是新一类操作**：写一份 `backend/src/main/resources/agent/skills/<topic>.md` 当剧本，Agent 会按需自动加载

参考现有：`PipelineTriggerTools.regenerateScript()` 是异步触发的范例，`ContentWriteTools.updateScriptSection()` 是同步小改动的范例。

## 🧪 PR 验证清单

提交前请本地跑通：

```bash
# Backend
cd backend && mvn -B -DskipTests compile        # ① 编译过
cd backend && mvn -B -DskipTests package        # ② jar 能打

# Frontend
cd frontend && npm run build                    # ③ vue-tsc + vite build 过

# Docker（推荐但非必须）
docker compose build                            # ④ 镜像能构建
docker compose up -d                            # ⑤ 三个服务全部 healthy
```

CI 会跑 ①②③④。所有跑过才会进 review 阶段。

### TypeScript 严格模式

`tsconfig` 启用了 `noUnusedLocals` / `noUnusedParameters`。**未使用的变量 / 参数会让构建失败**。处理方式：

- 真没用 → 删掉
- 接口要求保留参数 → 加 `_` 前缀（`_env`, `_logoImg`）
- 没办法删（类型推导链上的）→ 加 `// eslint-disable-next-line @typescript-eslint/no-unused-vars`

## 🎨 commit message 规范

参考已有 commits（`git log --oneline | head -10`），主流是：

```
<type>(<scope>): <subject>

<可选 body>
```

`type`：`feat` / `fix` / `refactor` / `docs` / `chore` / `test`
`scope`：`backend` / `frontend` / `agent` / `docker` / `preset` / ...

例子：
```
feat(agent): 添加内容调整技能文档和资产发布工具集
fix(frontend): healthcheck 用 127.0.0.1 而非 localhost
docs(readme): 重写中英文 README 突出多角色协作和自动化剪辑
```

中英文都可以，但同一个 PR 内保持一致。

## 🐛 报 Issue

报 bug 之前先：

1. 跑通 [PR 验证清单](#pr-验证清单)，确认本地构建是 OK 的
2. 看 `docker compose logs -f backend`，捞 stack trace
3. 在 issue 里贴：**期望行为 / 实际行为 / 复现步骤 / 日志片段（脱敏）/ 环境（OS、JDK、Node 版本）**

## 🤝 Maintainer 期待

- 24-72 小时内响应 PR / Issue（个人项目，请耐心）
- 简单 PR（typo / 文档 / 小 bug）当天合
- 复杂 PR（新角色 / schema 改动）会有 review 来回，可能改 1-2 轮

不接受的 PR 类型：

- ❌ 大量纯样式 / 格式化 commit（除非真有显著可读性提升）
- ❌ "把 X 框架换成 Y" 的大重构（先开 issue 讨论）
- ❌ 引入新依赖但没说明必要性
- ❌ 删了别人的功能没说明替代方案

## 📜 License

提交即视为同意按 [MIT License](./LICENSE) 授权你的代码贡献。
