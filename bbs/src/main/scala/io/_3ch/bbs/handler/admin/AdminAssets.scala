package io._3ch.bbs.handler.admin

import io._3ch.bbs.external.cloudflare.WorkerEnv
import io._3ch.bbs.external.web.{Request, Response, URL}
import io._3ch.bbs.handler.{AdminText, Responses}

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

/** 管理画面の起動ページと資産（dist/admin/）を ASSETS から返す。認証は呼び出し側で済ませる */
object AdminAssets {
  val IndexPath = "/admin/index.html"
  val AssetPrefix = "/admin/assets/"
  val ContentSecurityPolicy =
    "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'self'; form-action 'self' https://accounts.google.com; frame-ancestors 'none'; base-uri 'none'"

  /** 応答に付けるヘッダ。資産はハッシュ付きのファイル名なので、見つかったものだけ長く持たせる */
  def headers(asset: Boolean, status: Int): Seq[(String, String)] = {
    val common = Seq("x-content-type-options" -> "nosniff", "referrer-policy" -> "no-referrer")
    if (asset) common :+ ("cache-control" -> (if (status == 200) "private, max-age=31536000, immutable" else "no-store"))
    else common ++ Seq("cache-control" -> "no-store", "content-security-policy" -> ContentSecurityPolicy)
  }

  /** /admin/assets/ 以下はそのファイルを、それ以外の画面の URL は起動ページを返す（画面の切り替えはブラウザーで行う） */
  def handle(r: Request, e: WorkerEnv, path: String): Future[Response] = {
    val asset = path.startsWith(AssetPrefix)
    if (r.method != "GET" && r.method != "HEAD") Future.successful(text(AdminText.apiMethod, 405, asset))
    else {
      val target = if (asset) r else js.Dynamic.newInstance(js.Dynamic.global.Request)(new URL(IndexPath, r.url).toString, r).asInstanceOf[Request]
      e.ASSETS.fetch(target).toFuture.map { found =>
        if (!asset && found.status != 200) text(AdminText.pageMissing, 503, asset)
        else {
          val copy = js.Dynamic.newInstance(js.Dynamic.global.Response)(found.body, found).asInstanceOf[Response]
          headers(asset, found.status).foreach((name, value) => copy.headers.set(name, value))
          copy
        }
      }
    }
  }

  private def text(message: String, status: Int, asset: Boolean): Response = {
    val values = js.Dictionary("content-type" -> "text/plain; charset=UTF-8")
    headers(asset, status).foreach((name, value) => values(name) = value)
    Responses.response(message, status, values)
  }
}
