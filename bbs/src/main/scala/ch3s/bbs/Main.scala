package ch3s.bbs

import ch3s.bbs.external.hono.HonoContextFacade
import ch3s.bbs.external.web.Response
import ch3s.bbs.handler.HealthHandler
import ch3s.bbs.util.SjisEncoding

import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel
import scala.scalajs.js.typedarray.Uint8Array

/**
 * HonoXのルート（TypeScript、`app/routes` 配下、codegen生成）から `scalajs:server.js` 経由で
 * importされるエントリポイント関数。TS側はここでexportした関数を1行呼ぶだけの薄い配線にする
 * （参照実装 ch3s-migration の `ch3s.bbs.Main` と同じ方針）。
 *
 * `V6PlaceholderDurableObject`（`durable/`パッケージ）は `extends DurableObject` により
 * クラス自体がJS向けクラスになるためメンバは自動的に素の名前でexportされるが、
 * この `Main` のような独立関数のexportにはその仕組みが働かないため `@JSExportTopLevel` が必要。
 */
object Main {

  @JSExportTopLevel("admin", "server")
  def admin(request: external.web.Request, env: external.cloudflare.WorkerEnv): js.Promise[Response] = handler.Admin.handle(request, env)

  @JSExportTopLevel("publicFetch", "server")
  def publicFetch(request: external.web.Request, env: external.cloudflare.WorkerEnv, ctx: js.Dynamic): js.Promise[Response] = handler.PublicRoutes.fetch(request, env, ctx)

  @JSExportTopLevel("publicHandler", "server")
  def publicHandler(c: js.Dynamic): js.Promise[Response] = publicFetch(c.req.raw.asInstanceOf[external.web.Request], c.env.asInstanceOf[external.cloudflare.WorkerEnv], c.executionCtx)

  @JSExportTopLevel("bbs_entry", "server")
  def bbsEntry(request: external.web.Request, env: external.cloudflare.WorkerEnv): js.Promise[Response] = handler.Posting.entry(request, env)

  @JSExportTopLevel("settingTxtDefault", "server")
  val settingTxtDefault: String = handler.PostingText.settingTxtDefault

  @JSExportTopLevel("parseRules", "server")
  def parseRules(text: String): js.Array[js.Dynamic] = handler.Moderation.parseRules(text)

  @JSExportTopLevel("checkModeration", "server")
  def checkModeration(gb: js.Dynamic, env: external.cloudflare.WorkerEnv): js.Promise[Int] = handler.Moderation.check(gb, env)

  @JSExportTopLevel("healthHandler", "server")
  def healthHandler(c: js.Any): js.Promise[Response] =
    HealthHandler.health(c.asInstanceOf[HonoContextFacade])

  // 差分テスト（tests/golden/http-differential.test.mjs）が実機 workerd 上で
  // SjisEncoding（R-01案A）の実装そのものを exhaustive に検証するための診断用export。
  // HTTPルーティングからは呼ばれない（RouteTable.scala に対応ルートを追加していない）。
  @JSExportTopLevel("sjisEncode", "server")
  def sjisEncode(text: String): Uint8Array = SjisEncoding.encode(text)

  @JSExportTopLevel("sjisDecode", "server")
  def sjisDecode(bytes: Uint8Array): String = SjisEncoding.decode(bytes)
}
