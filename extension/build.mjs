// Build script: bundle TS sources to dist/, then copy static files (manifest + html).
//
// 三类目标:
//  - background SW:ESM module(manifest 里 service_worker.type = "module" 才能 import)
//  - content scripts / page-hooks:IIFE 单文件,manifest 直接 js: [...] 加载,不能依赖外部 chunk
//  - popup / options:HTML 静态页面,各自带一个 IIFE TS 入口
import { build, context } from 'esbuild'
import { copyFileSync, cpSync, existsSync, mkdirSync, rmSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = dirname(fileURLToPath(import.meta.url))
const ROOT = __dirname
const DIST = resolve(ROOT, 'dist')

const watch = process.argv.includes('--watch')

const COMMON = {
  bundle: true,
  target: 'chrome110',
  logLevel: 'info',
  sourcemap: 'linked',
  legalComments: 'none',
}

/**
 * 入口列表。新增平台时按这里抄一份。
 * - format: 'iife' = 内联依赖,manifest 直接加载
 * - format: 'esm' = ES module(SW 用)
 */
const ENTRIES = [
  // background SW
  { in: 'src/background/index.ts', out: 'background/index.js', format: 'esm' },
  // 抖音
  { in: 'src/content/douyin.ts', out: 'content/douyin.js', format: 'iife' },
  { in: 'src/page-hooks/douyin-hook.ts', out: 'page-hooks/douyin-hook.js', format: 'iife' },
  // B站
  { in: 'src/content/bilibili.ts', out: 'content/bilibili.js', format: 'iife' },
  { in: 'src/page-hooks/bilibili-hook.ts', out: 'page-hooks/bilibili-hook.js', format: 'iife' },
  // 视频号
  { in: 'src/content/weixin.ts', out: 'content/weixin.js', format: 'iife' },
  { in: 'src/page-hooks/weixin-hook.ts', out: 'page-hooks/weixin-hook.js', format: 'iife' },
  // 快手
  { in: 'src/content/kuaishou.ts', out: 'content/kuaishou.js', format: 'iife' },
  { in: 'src/page-hooks/kuaishou-hook.ts', out: 'page-hooks/kuaishou-hook.js', format: 'iife' },
  // popup / options 自带 TS
  { in: 'src/popup/popup.ts', out: 'popup/popup.js', format: 'iife' },
  { in: 'src/options/options.ts', out: 'options/options.js', format: 'iife' },
]

function copyStatic() {
  copyFileSync(resolve(ROOT, 'manifest.json'), resolve(DIST, 'manifest.json'))
  // 把 popup/options 的 html 等静态资源拷过去。
  // 必须排除 .ts(esbuild 处理) 与 .js/.js.map(esbuild 已经写到 dist 了,
  // 如果 src 里还有遗留的旧 .js,会反向覆盖刚生成的产物 —— 历史上踩过这个坑)。
  const filter = (src) => !/\.(ts|js|js\.map)$/.test(src)
  cpSync(resolve(ROOT, 'src/popup'), resolve(DIST, 'popup'), {
    recursive: true,
    filter,
  })
  cpSync(resolve(ROOT, 'src/options'), resolve(DIST, 'options'), {
    recursive: true,
    filter,
  })
  // 图标:Chrome 加载用,manifest.json 里的 icons / action.default_icon 引用。
  if (existsSync(resolve(ROOT, 'icons'))) {
    cpSync(resolve(ROOT, 'icons'), resolve(DIST, 'icons'), { recursive: true })
  }
}

async function run() {
  if (existsSync(DIST)) rmSync(DIST, { recursive: true, force: true })
  mkdirSync(DIST, { recursive: true })

  const builds = ENTRIES.map((e) => ({
    ...COMMON,
    entryPoints: [resolve(ROOT, e.in)],
    outfile: resolve(DIST, e.out),
    format: e.format,
  }))

  if (watch) {
    const ctxs = await Promise.all(builds.map((opts) => context(opts)))
    await Promise.all(ctxs.map((c) => c.watch()))
    copyStatic()
    console.log('[build] watching for changes... (manifest/html copied once at startup; restart to re-copy)')
  } else {
    await Promise.all(builds.map((opts) => build(opts)))
    copyStatic()
    console.log('[build] done -> dist/')
  }
}

run().catch((e) => {
  console.error(e)
  process.exit(1)
})
