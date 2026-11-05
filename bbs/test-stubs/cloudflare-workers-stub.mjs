// sbt test（Node上のmunit実行）では Cloudflare Workers ランタイム組み込みモジュール
// `cloudflare:workers` を解決できない
// `bbs` の Board / Thread DO は `@JSExportTopLevel` で常にexportされ、
// それがTestのリンク成果物にも巻き込まれるため、テスト実行時だけこのスタブへ import解決先を
// 差し替える（cloudflare-workers-loader.mjs参照）。
//
// DO の単体テストは DurableTestKit のフェイク state で行うため、
// ここでは import 解決が成立することだけを保証する最小のクラスで足りる
export class DurableObject {
  constructor(ctx, env) {
    this.ctx = ctx
    this.env = env
  }
}
