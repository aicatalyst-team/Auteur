import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

// 后端 Spring Boot 默认 8082；同源代理避免 CORS
//
// base 路径:本地 dev / Docker / Vercel 都走根路径 / ;GitHub Pages 部署到
// nxin-github.github.io/Auteur/ 子路径,build 时通过 VITE_BASE_PATH 环境变量传 /Auteur/。
// 主路径以 import.meta.env.BASE_URL 形式被 vue-router 读到,保持单一来源。
//
// chunk 策略:GitHub Pages 国内访问时,动态 import 的 chunk 会被 ISP 间歇性
// 断流(ERR_CONNECTION_CLOSED),导致点按钮切路由失败。把所有路由页面合并到
// 一个 bundle(只把 xlsx 这种大依赖单独抽),换"首屏稍大但运行 0 风险"。
export default defineConfig({
  base: process.env.VITE_BASE_PATH || '/',
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  build: {
    rollupOptions: {
      output: {
        manualChunks: (id) => {
          // xlsx 单独抽出来(424KB,只 PublishedVideoAdmin 用),
          // 不让它拖累首屏;但把它打成 vendor chunk 而不是动态 import,
          // 加载时仍然走静态 link(浏览器 prefetch 友好,失败可重试)。
          if (id.includes('node_modules/xlsx')) return 'xlsx'
          // 其他所有(vue 全家桶 + 业务代码 + fixtures)合并到 index
          return undefined
        },
      },
    },
  },
  server: {
    port: 5174,
    proxy: {
      '/api': {
        target: 'http://localhost:8082',
        changeOrigin: true,
      },
    },
  },
})
