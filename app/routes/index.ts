// 既存 fetch の全メソッド振り分けは手書きで維持する。
import { createHono } from "honox/factory";
// @ts-expect-error scalajs:server.js is resolved by the Scala.js Vite plugin.
import { publicHandler, healthHandler } from "scalajs:server.js";
const app = createHono();
app.all('*', (c) => c.req.path === '/_v6/health' ? healthHandler(c) : publicHandler(c));
export default app;
