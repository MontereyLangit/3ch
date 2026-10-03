package ch3s.bbs.external.hono

import scala.scalajs.js

/** Hono の `c.req` / `c.env` 用の最小手書き facade（docs/v6s実装メモ.md 2.5）。 */
@js.native
trait HonoRequestFacade extends js.Object {
  def param(name: String): js.UndefOr[String] = js.native
  def header(name: String): js.UndefOr[String] = js.native
}

@js.native
trait HonoContextFacade extends js.Object {
  val req: HonoRequestFacade = js.native

  /** Workersバインディング（`env.V6_PLACEHOLDER_DO` 等）。呼び出し側で `WorkerEnv` へキャストする。 */
  val env: js.Any = js.native
}
