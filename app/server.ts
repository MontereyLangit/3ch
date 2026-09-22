import { createApp } from "honox/server";
// @ts-expect-error scalajs:server.js は @scala-js/vite-plugin-scalajs が解決する仮想モジュール
// （.d.ts無し）。bbs（Scala.js）が @JSExportTopLevel した DurableObject サブクラスをここで受け取り、
// Workers のエントリポイントとして再exportする。
import { V6PlaceholderDurableObject } from "scalajs:server.js";

const app = createApp();

export default app;

// =========================================================
// !!! CRITICAL STEP: EXPORT THE DURABLE OBJECT CLASSES !!!
// wrangler.v6.jsonc の durable_objects.bindings が参照する class_name はこのexport名と
// 一致する必要がある。@hono/vite-build/cloudflare-workers は本番ビルド用の仮想エントリを
// 合成し、各エントリファイルの **default export のみ** を取り込むため、この named export は
// 素朴には最終バンドルへ現れない（vite.config.ts の entryContentAfterHooks で再export している）。
// =========================================================
export { V6PlaceholderDurableObject };
