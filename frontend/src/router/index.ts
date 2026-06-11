import { createRouter, createWebHistory } from 'vue-router'

// ⚠️ 路由组件统一用 eager static import,不用动态 import:
// GitHub Pages 国内访问时,动态 import 触发的 chunk 经常被 ISP 中断
// (ERR_CONNECTION_CLOSED),导致点按钮切路由"卡住"。Eager import 让所有
// 页面打到 main bundle,首屏多几百 KB,换路由切换 0 网络请求。
import Home from '../views/Home.vue'
import Chat from '../views/Chat.vue'
import TopicList from '../views/TopicList.vue'
import TopicDetail from '../views/TopicDetail.vue'
import ScriptList from '../views/ScriptList.vue'
import ScriptDetail from '../views/ScriptDetail.vue'
import CoverList from '../views/CoverList.vue'
import CoverDesigner from '../views/CoverDesigner.vue'
import FactCheckList from '../views/FactCheckList.vue'
import FactCheckDetail from '../views/FactCheckDetail.vue'
import StoryboardList from '../views/StoryboardList.vue'
import StoryboardDetail from '../views/StoryboardDetail.vue'
import ImageList from '../views/ImageList.vue'
import ImageDetail from '../views/ImageDetail.vue'
import VoiceStudio from '../views/VoiceStudio.vue'
import Dashboard from '../views/Dashboard.vue'
import ReviewWeekly from '../views/ReviewWeekly.vue'
import ReviewCompare from '../views/ReviewCompare.vue'
import ReviewVideo from '../views/ReviewVideo.vue'
import Insights from '../views/Insights.vue'
import PublishedVideoAdmin from '../views/PublishedVideoAdmin.vue'
import GenreStatsAdmin from '../views/GenreStatsAdmin.vue'
import VideoAssemblyList from '../views/VideoAssemblyList.vue'
import VideoAssemblyPreview from '../views/VideoAssemblyPreview.vue'
import SeriesView from '../views/SeriesView.vue'
import SeriesDetail from '../views/SeriesDetail.vue'
import PresetLibrary from '../views/PresetLibrary.vue'
import SystemConfig from '../views/SystemConfig.vue'
import PresetEditor from '../views/PresetEditor.vue'
import AdminToggle from '../views/AdminToggle.vue'

const router = createRouter({
  // base 路径与 vite.config.ts 的 base 配置同步:
  // - 本地 dev / Vercel / Docker 都是 '/'
  // - GitHub Pages 是 '/Auteur/'(由 build 时的 VITE_BASE_PATH 传递,
  //   Vite 会自动塞进 import.meta.env.BASE_URL)
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/', redirect: '/home' },

    // 工作台首页
    { path: '/home', name: 'home', component: Home, meta: { title: '工作台' } },

    // Agent 控制台:对话式管理预设/系统配置(并存,Home 不变)
    { path: '/chat', name: 'chat', component: Chat, meta: { title: 'Agent 控制台' } },

    // 选题
    { path: '/topics', name: 'topics', component: TopicList, meta: { title: '选题池' } },
    {
      path: '/topics/:id',
      name: 'topic-detail',
      component: TopicDetail,
      meta: { title: '选题详情' },
      props: (route) => ({ id: Number(route.params.id) }),
    },

    // 脚本
    { path: '/scripts', name: 'scripts', component: ScriptList, meta: { title: '脚本工作台' } },
    {
      path: '/scripts/:id',
      name: 'script-detail',
      component: ScriptDetail,
      meta: { title: '脚本详情' },
      props: (route) => ({ id: Number(route.params.id) }),
    },
    { path: '/cover', name: 'cover', component: CoverList, meta: { title: '封面生成' } },
    {
      path: '/scripts/:scriptId/cover',
      name: 'cover-designer',
      component: CoverDesigner,
      meta: { title: '封面设计器' },
      props: (route) => ({ scriptId: Number(route.params.scriptId) }),
    },

    // 事实核查（按 scriptId 查；列表先靠最近脚本聚合）
    { path: '/factcheck', name: 'factcheck', component: FactCheckList, meta: { title: '事实核查' } },
    {
      path: '/factcheck/:scriptId',
      name: 'factcheck-detail',
      component: FactCheckDetail,
      meta: { title: '事实核查详情' },
      props: (route) => ({ scriptId: Number(route.params.scriptId) }),
    },

    // 分镜
    { path: '/storyboard', name: 'storyboard', component: StoryboardList, meta: { title: '分镜工作台' } },
    {
      path: '/storyboard/:scriptId',
      name: 'storyboard-detail',
      component: StoryboardDetail,
      meta: { title: '分镜详情' },
      props: (route) => ({ scriptId: Number(route.params.scriptId) }),
    },

    // 生图
    { path: '/images', name: 'images', component: ImageList, meta: { title: '生图工作台' } },
    {
      path: '/images/:scriptId',
      name: 'image-detail',
      component: ImageDetail,
      meta: { title: '生图详情' },
      props: (route) => ({ scriptId: Number(route.params.scriptId) }),
    },

    // 配音字幕
    { path: '/voice', name: 'voice', component: VoiceStudio, meta: { title: '配音字幕工作台' } },

    // 复盘组
    { path: '/dashboard', name: 'dashboard', component: Dashboard, meta: { title: '数据看板' } },
    { path: '/review/weekly', name: 'review-weekly', component: ReviewWeekly, meta: { title: '周复盘' } },
    { path: '/review/compare', name: 'review-compare', component: ReviewCompare, meta: { title: '跨视频对比' } },
    {
      path: '/review/:videoId',
      name: 'review-video',
      component: ReviewVideo,
      meta: { title: '单条视频复盘' },
      props: (route) => ({ videoId: Number(route.params.videoId) }),
    },
    { path: '/insights', name: 'insights', component: Insights, meta: { title: '数据洞察 · 反向选题' } },
    { path: '/published-videos', name: 'published-videos', component: PublishedVideoAdmin, meta: { title: '已发布视频' } },
    { path: '/genre-stats', name: 'genre-stats', component: GenreStatsAdmin, meta: { title: '体裁基准 · 投稿作品聚合' } },

    // 视频组装
    { path: '/assembly', name: 'assembly', component: VideoAssemblyList, meta: { title: '视频组装' } },
    {
      path: '/assembly/:scriptId',
      name: 'assembly-detail',
      component: VideoAssemblyPreview,
      meta: { title: '视频组装预览' },
      props: (route) => ({ scriptId: Number(route.params.scriptId) }),
    },
    { path: '/series', name: 'series', component: SeriesView, meta: { title: '系列管理' } },
    {
      path: '/series/:id',
      name: 'series-detail',
      component: SeriesDetail,
      meta: { title: '系列详情' },
      props: (route) => ({ id: Number(route.params.id) }),
    },

    // Preset 管理(admin 模式 + 公开预设浏览)
    { path: '/presets', name: 'preset-library', component: PresetLibrary, meta: { title: '预设库' } },
    // 系统设置(LLM 中转 / 对象存储 / 语音 / BGM 等密钥)
    { path: '/config', name: 'system-config', component: SystemConfig, meta: { title: '系统设置' } },
    {
      path: '/presets/:id/edit',
      name: 'preset-editor',
      component: PresetEditor,
      meta: { title: '编辑预设' },
      props: (route) => ({ id: Number(route.params.id) }),
    },
    {
      path: '/presets/new',
      name: 'preset-new',
      component: PresetEditor,
      meta: { title: '新建预设' },
      props: () => ({ id: null }),
    },
    // admin 模式开关入口 — 访问 /admin?token=xxx 写 localStorage,/admin?logout=1 清除
    { path: '/admin', name: 'admin-toggle', component: AdminToggle, meta: { title: 'Admin 模式' } },

    // 兜底 404 → 回首页
    { path: '/:pathMatch(.*)*', redirect: '/home' },
  ],
})

export default router
