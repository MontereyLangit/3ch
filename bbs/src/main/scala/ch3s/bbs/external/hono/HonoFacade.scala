package ch3s.bbs.external.hono

import scala.scalajs.js

/**
 * `c.req.param`/`c.env` へ型付きで触るための薄い手書き `@js.native` facade。
 * 参照実装 ch3s-migration の `HonoFacade.scala` と同じ方針（ScalablyTypedは使わず、
 * 実際に使うメンバだけを宣言する）。v6s骨格では `healthHandler` が `env` も `param` も
 * 使わないため、次タスクで必要になるメンバから最小限だけ先取りしてある。
 */
@js.native
trait HonoRequestFacade extends js.Object:
  def param(name: String): js.UndefOr[String] = js.native
  def header(name: String): js.UndefOr[String] = js.native

@js.native
trait HonoContextFacade extends js.Object:
  val req: HonoRequestFacade = js.native

  /** Workersバインディング（`env.V6_PLACEHOLDER_DO` 等）。呼び出し側で `WorkerEnv` へキャストする。 */
  val env: js.Any = js.native
