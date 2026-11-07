package io._3ch.bbs.handler

import io._3ch.bbs.external.cloudflare.*
import io._3ch.bbs.external.web.{Request, Response, TextEncoder, URL}
import io._3ch.bbs.hash.WebHash
import io._3ch.bbs.handler.admin.{AdminApi, AdminAssets}
import io._3ch.common.admin.{AdminError, AdminErrorCode}
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.concurrent.Future

/**
  * 管理画面用ハンドラ。Basic 認証の後、/admin/api 以下は JSON API、それ以外は SPA の起動ページと資産を返す
  */
object AdminHandler {
  private def str(v: js.Any): String = js.Dynamic.global.String(v).asInstanceOf[String]
  private def truth(v: js.Any): Boolean = js.Dynamic.global.Boolean(v).asInstanceOf[Boolean]

  def handle(r: Request, e: WorkerEnv): js.Promise[Response] = {
    val env = e.asInstanceOf[js.Dynamic]
    val requestPath = new URL(r.url).pathname
    val api = requestPath == "/admin/api" || requestPath.startsWith("/admin/api/")
    def page(message: String, status: Int, extra: Seq[(String, String)] = Nil): Response = {
      val headers = js.Dictionary("content-type" -> "text/plain; charset=UTF-8")
      (AdminAssets.headers(asset = false, status) ++ extra).foreach((name, value) => headers(name) = value)
      Responses.response(message, status, headers)
    }
    if !truth(env.ADMIN_PASSWORD) then return js.Promise.resolve(
      if api then AdminApi.error(AdminError(AdminErrorCode.Unavailable, AdminText.apiUnavailable)) else page(AdminText.apiUnavailable, 503))
    val username = if truth(env.ADMIN_USER) then str(env.ADMIN_USER) else "admin"
    val credentialBytes = new TextEncoder().encode(s"$username:${str(env.ADMIN_PASSWORD)}")
    val credentials = (0 until credentialBytes.length).map(i => credentialBytes(i).toChar).mkString
    val expected = "Basic " + js.Dynamic.global.btoa(credentials).asInstanceOf[String]
    val a = WebHash.hash("SHA-256", expected).toFuture
    val b = WebHash.hash("SHA-256", Option(r.headers.get("authorization")).getOrElse("")).toFuture
    a.zip(b).flatMap { (left, right) =>
      if left != right then {
        val challenge = Seq("www-authenticate" -> "Basic realm=\"3ch admin\", charset=\"UTF-8\"")
        Future.successful(
          if api then AdminApi.error(AdminError(AdminErrorCode.Unauthorized, AdminText.apiUnauthorized), challenge)
          else page(AdminText.apiUnauthorized, 401, challenge))
      } else if api then AdminApi.handle(r, e, requestPath)
      else AdminAssets.handle(r, e, requestPath)
    }.toJSPromise
  }
}
