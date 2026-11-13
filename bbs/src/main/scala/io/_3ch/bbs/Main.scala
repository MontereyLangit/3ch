package io._3ch.bbs

import io._3ch.bbs.external.hono.HonoContextFacade
import io._3ch.bbs.external.web.{Response, ResponseInit}
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
  def renderShell(children: js.Any, title: js.UndefOr[String], clientScriptSrc: String, authModal: js.UndefOr[Boolean] = js.undefined): React.Element =
    RendererShell.render(children, title, clientScriptSrc, authModal.getOrElse(false))

  @JSExportTopLevel("admin", "server")
  def admin(request: Request, env: WorkerEnv): js.Promise[Response] = handler.AdminHandler.handle(request, env)

  //noinspection ScalaWeakerAccess
  @JSExportTopLevel("publicFetch", "server")
  def publicFetch(request: Request, env: WorkerEnv, ctx: js.Dynamic): js.Promise[Response] = handler.PublicRoutes.fetch(request, env, ctx)

  @JSExportTopLevel("publicHandler", "server")
  def publicHandler(c: js.Dynamic): js.Promise[Response] = publicFetch(c.req.raw.asInstanceOf[Request], c.env.asInstanceOf[WorkerEnv], c.executionCtx)

  @JSExportTopLevel("bbs_entry", "server")
  def bbsEntry(request: Request, env: WorkerEnv): js.Promise[Response] = handler.Posting.entry(request, env)

  @JSExportTopLevel("settingTxtDefault", "server")
  val settingTxtDefault: String = handler.PostingText.settingTxtDefault

  @route("[bbs]/index.ts")
  @JSExportTopLevel("boardIndexHandler", "server")
  def boardIndexHandler(c: js.Any): js.Promise[Response] =
  BoardHandler.index(c.asInstanceOf[HonoContextFacade])

  /** 認証・登録画面を描画する */
  @route("auth/index.ts")
  @JSExportTopLevel("authPageHandler", "server")
  def authPageHandler(c: js.Any): js.Promise[Response] = handler.auth.AuthHandler.page(c.asInstanceOf[HonoContextFacade])

  /** 本人のアカウント設定画面を描画する */
  @route("auth/account/index.ts")
  @JSExportTopLevel("authAccountHandler", "server")
  def authAccountHandler(c: js.Any): js.Promise[Response] = handler.auth.AuthHandler.page(c.asInstanceOf[HonoContextFacade], accountPage = true)

  /** 本文と状態を保って応答を複製し、変更可能なヘッダへClient Hintsを付ける */
  @JSExportTopLevel("acceptClientHints", "server")
  def acceptClientHints(response: Response): Response = {
    val init = new ResponseInit {}
    init.status = response.status
    init.statusText = response.statusText
    init.headers = response.headers
    val copy = new Response(response.body, init)
    copy.headers.set("Accept-CH", "Sec-CH-UA-Platform, Sec-CH-UA-Platform-Version, Sec-CH-UA-Model, Sec-CH-UA-Arch, Sec-CH-UA-Mobile, Sec-CH-UA-Full-Version-List")
    copy
  }

  /** 認証と本人向け操作の API を受け付ける */
  @JSExportTopLevel("authApi", "server")
  def authApi(request: Request, env: WorkerEnv): js.Promise[Response] = handler.auth.AuthHandler.handle(request, env)

  // SjisEncoding（R-01案A）の実装そのものを exhaustive に検証するための診断用export。
  // HTTPルーティングからは呼ばれない（@route を付けていない）。
  @JSExportTopLevel("sjisEncode", "server")
  def sjisEncode(text: String): Uint8Array = SjisEncoding.encode(text)

  @JSExportTopLevel("sjisDecode", "server")
  def sjisDecode(bytes: Uint8Array): String = SjisEncoding.decode(bytes)
}
