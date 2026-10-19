import { createApp } from "honox/server";
import { appendTrailingSlash } from "hono/trailing-slash";
// Scala.js が公開する DurableObject を Workers のエントリポイントとして再 export する。
import {
  V6PlaceholderDurableObject,
  Board,
  Thread,
  // @ts-expect-error scalajs:server.js is resolved by the Scala.js Vite plugin.
  publicHandler,
} from "scalajs:server.js";

const app = createApp({
  trailingSlash: true,
  init: (app) => {
    app.use(appendTrailingSlash({
      alwaysRedirect: true,
      skip: (path) => path.substring(path.lastIndexOf('/') + 1).includes('.'),
    }));
  },
});
// ファイルルートに一致しないリクエストは既存の公開ハンドラで処理する(LegacyDispatch)
app.all("*", (c) => publicHandler(c));

export default app;

// =========================================================
// !!! CRITICAL STEP: EXPORT THE DURABLE OBJECT CLASSES !!!
// wrangler.jsonc の durable_objects.bindings が参照する class_name はこのexport名と
// 一致する必要がある。@hono/vite-build/cloudflare-workers は本番ビルド用の仮想エントリを
// 合成し、各エントリファイルの **default export のみ** を取り込むため、この named export は
// 素朴には最終バンドルへ現れない（vite.config.ts の entryContentAfterHooks で再export している）。
// =========================================================
export { V6PlaceholderDurableObject, Board, Thread };
