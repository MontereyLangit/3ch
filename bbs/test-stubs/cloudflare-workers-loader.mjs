// Node の Module customization hooks で、テストに不要な解決だけを差し替える。
// それ以外のモジュール指定子は Node 既定の解決へそのまま委譲する。
//   cloudflare:workers -> スタブ（cloudflare-workers-stub.mjs）。参照実装 ch3s-migration の同名ファイルを踏襲
//   hono/jsx(-runtime) -> react（honox の island 部品。本番は Vite の reactApiImportSource が同じ置換をする）
//   scalajs:client.js  -> bbsClient のリンク成果物（island の SSR が実体を読む。build.sbt が CH3S_CLIENT_JS_DIR を渡す）
import { pathToFileURL } from 'node:url'
import path from 'node:path'

export async function resolve(specifier, context, nextResolve) {
  if (specifier === 'cloudflare:workers') {
    return {
      url: new URL('./cloudflare-workers-stub.mjs', import.meta.url).href,
      shortCircuit: true,
    }
  }
  if (specifier === 'scalajs:client.js') {
    const dir = process.env.CH3S_CLIENT_JS_DIR
    if (!dir) throw new Error('CH3S_CLIENT_JS_DIR is not set (bbsClient fastLinkJS output directory)')
    return {
      url: pathToFileURL(path.join(dir, 'client.js')).href,
      shortCircuit: true,
    }
  }
  if (context.parentURL?.includes('/node_modules/honox/')) {
    if (specifier === 'hono/jsx') return nextResolve('react', context)
    if (specifier === 'hono/jsx/jsx-runtime') return nextResolve('react/jsx-runtime', context)
  }
  return nextResolve(specifier, context)
}
