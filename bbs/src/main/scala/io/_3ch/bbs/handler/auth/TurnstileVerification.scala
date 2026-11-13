package io._3ch.bbs.handler.auth

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobal
import scala.scalajs.js.JSConverters.*
import scala.util.control.NonFatal

@js.native
private trait VerificationResponse extends js.Object {
  val ok: Boolean = js.native
  def json(): js.Promise[VerificationResult] = js.native
}
@js.native
private trait VerificationResult extends js.Object {
  val success: Boolean = js.native
  val hostname: js.UndefOr[String] = js.native
}
@js.native
@JSGlobal("fetch")
private object VerificationFetch extends js.Object {
  def apply(url: String, init: js.Object): js.Promise[VerificationResponse] = js.native
}

/** Cloudflare の検証結果だけを採用し、通信失敗では認証を通さない */
object TurnstileVerification {
  def verify(secret: String, token: String, ip: String, hostname: String): Future[Boolean] = {
    if (secret.isEmpty || token.isEmpty || token.length > 2048) Future.successful(false)
    else {
      val body = Seq("secret" -> secret, "response" -> token, "remoteip" -> ip).map { case (key, value) =>
        key + "=" + js.URIUtils.encodeURIComponent(value)
      }.mkString("&")
      VerificationFetch("https://challenges.cloudflare.com/turnstile/v0/siteverify", js.Dynamic.literal(
        method = "POST", headers = js.Dictionary("Content-Type" -> "application/x-www-form-urlencoded"), body = body)).toFuture.flatMap { response =>
        if (!response.ok) Future.successful(false)
        else response.json().toFuture.map(result => result.success && result.hostname.contains(hostname))
      }.recover { case NonFatal(_) => false }
    }
  }
}
