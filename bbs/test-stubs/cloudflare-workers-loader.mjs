// Node の Module customization hooks（`--experimental-loader`）で `cloudflare:workers` の
// 解決だけをスタブ（cloudflare-workers-stub.mjs）へ差し替える。それ以外のモジュール指定子は
// Node 既定の解決へそのまま委譲する。参照実装 ch3s-migration の同名ファイルをそのまま踏襲。
export async function resolve(specifier, context, nextResolve) {
  if (specifier === 'cloudflare:workers') {
    return {
      url: new URL('./cloudflare-workers-stub.mjs', import.meta.url).href,
      shortCircuit: true,
    }
  }
  return nextResolve(specifier, context)
}
