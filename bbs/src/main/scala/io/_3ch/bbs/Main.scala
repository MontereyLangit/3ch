package io._3ch.bbs

import io._3ch.bbs.external.hono.HonoContextFacade
import io._3ch.bbs.external.web.Response
import io._3ch.bbs.handler.RendererShell
import io._3ch.bbs.util.SjisEncoding
import io._3ch.common.routes.route
import japgolly.scalajs.react.facade.React

import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel
import scala.scalajs.js.typedarray.Uint8Array

import external.web.Request
import external.cloudflare.WorkerEnv
import io._3ch.bbs.handler.BoardHandler

object Main {

  /** HonoX の renderer から HTML ドキュメント全体を組み立てる。 */
  @JSExportTopLevel("renderShell", "server")
  def renderShell(children: js.Any, title: js.UndefOr[String], clientScriptSrc: String): React.Element =
    RendererShell.render(children, title, clientScriptSrc)

  @JSExportTopLevel("admin", "server")
  def admin(request: Request, env: WorkerEnv): js.Promise[Response] = handler.AdminHandler.handle(request, env)

  //noinspection ScalaWeakerAccess
  @JSExportTopLevel("publicFetch", "server")
  def publicFetch(request: Request, env: WorkerEnv, ctx: js.Dynamic): js.Promise[Response] = handler.PublicRoutes.fetch(request, env, ctx)

  @JSExportTopLevel("publicHandler", "server")
  def publicHandler(c: js.Dynamic): js.Promise[Response] = publicFetch(c.req.raw.asInstanceOf[Request], c.env.asInstanceOf[WorkerEnv], c.executionCtx)

  @JSExportTopLevel("bbs_entry", "server")
  def bbsEntry(request: Request, env: WorkerEnv): js.Promise[Response] = handler.PostingHandler.entry(request, env)

  @JSExportTopLevel("settingTxtDefault", "server")
  val settingTxtDefault: String = handler.PostingText.settingTxtDefault

  @JSExportTopLevel("parseRules", "server")
  def parseRules(text: String): js.Array[js.Dynamic] = handler.Moderation.parseRules(text)

  @JSExportTopLevel("checkModeration", "server")
  def checkModeration(gb: js.Dynamic, env: WorkerEnv): js.Promise[Int] = handler.Moderation.check(gb, env)

  @route("[bbs]/index.ts")
  @JSExportTopLevel("boardIndexHandler", "server")
  def boardIndexHandler(c: js.Any): js.Promise[Response] =
  BoardHandler.index(c.asInstanceOf[HonoContextFacade])

  // SjisEncoding（R-01案A）の実装そのものを exhaustive に検証するための診断用export。
  // HTTPルーティングからは呼ばれない（@route を付けていない）。
  @JSExportTopLevel("sjisEncode", "server")
  def sjisEncode(text: String): Uint8Array = SjisEncoding.encode(text)

  @JSExportTopLevel("sjisDecode", "server")
  def sjisDecode(bytes: Uint8Array): String = SjisEncoding.decode(bytes)
}
