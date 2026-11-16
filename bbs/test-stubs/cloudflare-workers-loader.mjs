// Node の Module customization hooks で、テストに不要な解決だけを差し替える。
// それ以外のモジュール指定子は Node 既定の解決へそのまま委譲する。
//   cloudflare:workers -> スタブ（cloudflare-workers-stub.mjs）。参照実装 ch3s-migration の同名ファイルを踏襲
// scalajs:client.js と HonoX 内の hono/jsx は register.mjs が先に登録する hx4s-vite/node-loader が扱う
export async function resolve(specifier, context, nextResolve) {
  if (specifier === 'cloudflare:workers') {
    return {
      url: new URL('./cloudflare-workers-stub.mjs', import.meta.url).href,
      shortCircuit: true,
    }
  }
  return nextResolve(specifier, context)
}
