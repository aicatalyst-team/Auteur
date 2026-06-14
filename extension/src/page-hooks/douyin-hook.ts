// 抖音创作者中心:抓两类列表网关
//   /web/api/creator/...                  老路径(2024 及以前)
//   /janus/douyin/creator/pc/work_list    2026-06 改版后的新路径
//                                         response 里 aweme_list[] + items[] 并列,
//                                         items[].metrics 含完整 KPI(view/like/comment/share/
//                                         completion/bounce/avg_view/cover_click/subscribe/...)
import { installHook } from './_install'

installHook({
  platform: '抖音',
  flag: '__auteurDouyinHooked',
  patterns: [
    /\/janus\/douyin\/creator\/pc\/work_list/i,
  ],
})
