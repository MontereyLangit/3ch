// sbt test（Node上のmunit実行）では Cloudflare Workers ランタイム組み込みモジュール
// `cloudflare:workers` を解決できない（Node標準のESMローダーは cloudflare: スキームを知らない）。
// `bbs` の Main には V6PlaceholderDurableObject が `@JSExportTopLevel` で常にexportされ、
// それがTestのリンク成果物にも巻き込まれるため、テスト実行時だけこのスタブへ import解決先を
// 差し替える（cloudflare-workers-loader.mjs参照）。
//
// 実行時（wrangler/workerd）では本物の cloudflare:workers が使われるため、このスタブが
// テスト経路以外で読み込まれることはない。現状のテスト（HealthHandlerSuite）は
// V6PlaceholderDurableObject の実RPC呼び出し（blockConcurrencyWhile経由のもの）を行わないため、
// ここでは import 解決が成立することだけを保証する最小のクラスで足りる
// （参照実装 ch3s-migration の同名ファイルと同じ割り切り）。
export class DurableObject {
  constructor(ctx, env) {
    this.ctx = ctx
    this.env = env
  }
}
