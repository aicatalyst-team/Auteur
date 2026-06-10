<!-- 感谢贡献!请按下面的项填一下,maintainer review 起来更快。 -->

## 这个 PR 干了什么

<!-- 一句话概括 -->

## 为什么要这么做 / 解决了什么问题

<!-- 可以引用相关 issue:closes #123 -->

## 改动的范围

<!-- 勾选所有适用项 -->

- [ ] Backend(Java / Spring Boot)
- [ ] Frontend(Vue / TypeScript)
- [ ] Renderer(Remotion / 视频合成)
- [ ] Extension(Chrome 浏览器扩展)
- [ ] Docker / 部署 / CI
- [ ] 文档(README / CLAUDE.md / CONTRIBUTING.md / SECURITY.md)
- [ ] 预设(`preset_seeds/<name>/`)
- [ ] 数据库迁移(新增 Flyway `V*__*.sql`)

## 验证清单

<!-- 提交前本地跑通 -->

- [ ] `cd backend && mvn -B -DskipTests compile` 通过
- [ ] `cd frontend && npm run build` 通过(含 vue-tsc 严格类型检查)
- [ ] `docker compose build` 通过(改了 Dockerfile / docker-compose.yml 时必须)
- [ ] 改了 schema:加了 Flyway migration 用递增 `V*` 编号
- [ ] 改了预设代码:同步更新 `preset_seeds/<name>/` seed 文件
- [ ] 改了 Agent 工具:写在对应 domain 的 `*Tools.java`,工具名 / 描述清晰

## 截图 / 录屏(UI 改动必须)

<!-- 拖图片到这里 -->

## 备注

<!-- 想让 reviewer 关注的点 / 已知的局限 / 后续要做的事 -->
