import { createApp } from "honox/server";
import { appendTrailingSlash } from "hono/trailing-slash";
// Scala.js が公開する DurableObject を Workers のエントリポイントとして再 export する。
import {
  Board,
  Thread,
  Account,
  AccountIndex,
  AuthRate,
  authApi,
  acceptClientHints,
  // @ts-expect-error scalajs:server.js is resolved by the Scala.js Vite plugin.
  publicHandler,
} from "scalajs:server.js";

const app = createApp({
  trailingSlash: true,
  init: (app) => {
    app.use("*", async (c, next) => {
      await next();
      c.res = acceptClientHints(c.res);
    });
    app.use(appendTrailingSlash({
      alwaysRedirect: true,
      skip: (path) => path.substring(path.lastIndexOf('/') + 1).includes('.'),
    }));
  },
});
app.all("/auth/*", (c) => authApi(c.req.raw, c.env));

// ファイルルートに一致しないリクエストは既存の公開ハンドラで処理する(LegacyDispatch)
app.all("*", (c) => publicHandler(c));

export default app;

// Workers に Durable Object を公開する。
export { Board, Thread, Account, AccountIndex, AuthRate };
