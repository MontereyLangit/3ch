// トップページは既存の公開ハンドラで処理する。
import { createHono } from "honox/factory";
// @ts-expect-error scalajs:server.js is resolved by the Scala.js Vite plugin.
import { publicHandler } from "scalajs:server.js";
const app = createHono();
app.all('/', (c) => publicHandler(c));
export default app;
