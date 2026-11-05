import { createApp } from "honox/server";
import { appendTrailingSlash } from "hono/trailing-slash";
// Scala.js が公開する DurableObject を Workers のエントリポイントとして再 export する。
import {
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
// =========================================================
export { Board, Thread };
