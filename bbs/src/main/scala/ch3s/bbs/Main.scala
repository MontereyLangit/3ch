package ch3s.bbs

import ch3s.bbs.external.hono.HonoContextFacade
import ch3s.bbs.external.web.Response
import ch3s.bbs.handler.HealthHandler

import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel

/**
 * HonoXのルート（TypeScript、`app/routes` 配下、codegen生成）から `scalajs:server.js` 経由で
 * importされるエントリポイント関数。TS側はここでexportした関数を1行呼ぶだけの薄い配線にする
 * （参照実装 ch3s-migration の `ch3s.bbs.Main` と同じ方針）。
 *
 * `V6PlaceholderDurableObject`（`durable/`パッケージ）は `extends DurableObject` により
 * クラス自体がJS向けクラスになるためメンバは自動的に素の名前でexportされるが、
 * この `Main` のような独立関数のexportにはその仕組みが働かないため `@JSExportTopLevel` が必要。
 */
object Main:

  @JSExportTopLevel("healthHandler", "server")
  def healthHandler(c: js.Any): js.Promise[Response] =
    HealthHandler.health(c.asInstanceOf[HonoContextFacade])
