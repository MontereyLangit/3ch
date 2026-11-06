package io._3ch.bbs.handler.auth

import io._3ch.bbs.durable.DurableTestKit
import io._3ch.bbs.domain.posting.KeyIp
import io._3ch.bbs.external.cloudflare.WorkerEnv
import io._3ch.bbs.external.web.{Request, Response}
import io._3ch.bbs.handler.HandlerTestKit
import io._3ch.bbs.hash.AuthHmac
import scala.collection.mutable
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

/** 実 DO を記憶域だけ差し替えて動かし、外部の Turnstile と Google の応答を固定する */
final class AuthSignInTestKit(google: Boolean = false, mail: Boolean = false) {
  /** 送ったメール（宛先・件名・本文）。mail が真のときだけ送信先を設定する */
  val outbox = mutable.ArrayBuffer.empty[(String, String, String)]
  /** 真なら送信先が失敗を返す */
  var mailFails: Boolean = false
  val origin = "https://www.3ch.io"
  val kit: DurableTestKit.Env = DurableTestKit.newEnv()
  val env: WorkerEnv = {
    val raw = kit.worker.asInstanceOf[js.Dynamic]
    raw.AUTH_SECRET = AuthTestEnv.secret
    raw.AGREEMENT_VERSION = "v1"
    raw.TURNSTILE_SITE_KEY = "test-site-key"
    raw.TURNSTILE_SECRET_KEY = "test-server-key"
    if (mail) {
      raw.MAIL_SENDER = js.Dynamic.literal(send = ((message: js.Dynamic) => {
        if (mailFails) js.Promise.reject(new js.Error("mail down"))
        else {
          outbox += ((message.to.asInstanceOf[String], message.subject.asInstanceOf[String], message.text.asInstanceOf[String]))
          js.Promise.resolve[js.Any](js.undefined)
        }
      }): js.Function1[js.Dynamic, js.Promise[js.Any]])
    }
    if (google) {
      raw.GOOGLE_CLIENT_ID = "test-client.apps.googleusercontent.com"
      raw.GOOGLE_CLIENT_SECRET = "test-client-secret"
    }
    kit.worker
  }
  /** Google のトークンエンドポイントが返す ID トークン */
  var idToken: String = ""
  var jwks: js.Any = js.Dynamic.literal(keys = js.Array())
  val external = mutable.ArrayBuffer.empty[String]
  /** 応答の遅延として要求された秒数 */
  val delays = mutable.ArrayBuffer.empty[Double]

  def keyIp(ip: String): Future[String] = AuthHmac.indexKey(AuthTestEnv.secret, "key-ip", KeyIp.of(ip)).map(_.get)
  def index(kind: String, value: String): Future[String] = AuthHmac.indexKey(AuthTestEnv.secret, kind, value).map(_.get)

  def post(path: String, fields: Seq[(String, String)], ip: String = "192.0.2.10", cookie: String = "", requestOrigin: String = origin): Request = {
    val params = js.Dynamic.newInstance(js.Dynamic.global.URLSearchParams)()
    fields.foreach { case (k, v) => params.append(k, v) }
    val headers = Seq("Content-Type" -> "application/x-www-form-urlencoded", "CF-Connecting-IP" -> ip) ++
      (if (requestOrigin.nonEmpty) Seq("Origin" -> requestOrigin) else Nil) ++ (if (cookie.nonEmpty) Seq("Cookie" -> cookie) else Nil)
    HandlerTestKit.request(origin + path, headers = headers, method = "POST", body = params.toString())
  }

  def get(path: String, ip: String = "192.0.2.10", cookie: String = ""): Request =
    HandlerTestKit.request(origin + path, headers = Seq("CF-Connecting-IP" -> ip) ++ (if (cookie.nonEmpty) Seq("Cookie" -> cookie) else Nil))

  def send(request: Request): Future[Response] = AuthHandler.handle(request, env).toFuture

  /** 送ったメールの本文にあるリンクのトークン */
  def mailedToken(index: Int = -1): String = {
    val text = if (index < 0) outbox.last._3 else outbox(index)._3
    "token=([a-z0-9]{16}\\.[A-Za-z0-9_-]{43})".r.findFirstMatchIn(text).map(_.group(1)).getOrElse("")
  }

  def body(response: Response): Future[js.Dynamic] = response.json().toFuture.map(_.asInstanceOf[js.Dynamic])

  /** Set-Cookie の値のうち名前に一致するものの「名前=値」 */
  def cookie(response: Response, name: String): Option[String] =
    Option(response.headers.asInstanceOf[js.Dynamic].getSetCookie().asInstanceOf[js.Array[String]]).toSeq.flatten
      .find(_.startsWith(name + "=")).map(_.takeWhile(_ != ';'))

  private def respond(status: Int, value: js.Any, headers: js.Dictionary[String] = js.Dictionary()): js.Promise[js.Any] =
    js.Promise.resolve[js.Any](js.Dynamic.newInstance(js.Dynamic.global.Response)(js.JSON.stringify(value), js.Dynamic.literal(status = status, headers = headers)))

  /** 外部サービスの応答と遅延を差し替えて body を実行する */
  def run[A](body: => Future[A]): Future[A] = {
    val global = HandlerTestKit.global
    val originalFetch = global.fetch
    val originalTimeout = global.setTimeout
    global.fetch = ((url: String, init: js.Dynamic) => {
      external += url
      url match {
        case "https://challenges.cloudflare.com/turnstile/v0/siteverify" =>
          val token = js.Dynamic.newInstance(js.Dynamic.global.URLSearchParams)(init.body).get("response").asInstanceOf[String]
          respond(200, js.Dynamic.literal(success = token == "accepted", hostname = "www.3ch.io"))
        case GoogleIdTokens.TokenUrl => respond(200, js.Dynamic.literal(id_token = idToken))
        case GoogleIdTokens.CertsUrl => respond(200, jwks, js.Dictionary("Cache-Control" -> "public, max-age=60"))
        case _ => js.Promise.reject(new js.Error("unexpected fetch " + url))
      }
    }): js.Function2[String, js.Dynamic, js.Promise[js.Any]]
    global.setTimeout = ((callback: js.Function0[Any], ms: js.UndefOr[Double]) => {
      val wait = ms.getOrElse(0d)
      if (wait >= 1000 && wait <= 8000) {
        delays += wait / 1000
        originalTimeout(callback, 0)
      } else originalTimeout(callback, wait)
    }): js.Function2[js.Function0[Any], js.UndefOr[Double], js.Any]
    val result = try body catch { case e: Throwable => Future.failed(e) }
    result.transform { r =>
      global.fetch = originalFetch
      global.setTimeout = originalTimeout
      r
    }
  }
}
