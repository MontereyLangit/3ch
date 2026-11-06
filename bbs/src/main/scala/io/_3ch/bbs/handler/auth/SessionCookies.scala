package io._3ch.bbs.handler.auth

import io._3ch.bbs.external.web.URL
import io._3ch.bbs.hash.AuthHmac
import io._3ch.common.domain.account.{CookieCodec, SessionClaims}
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.control.NonFatal

/** セッション Cookie の検証と Set-Cookie の生成 */
object SessionCookies {
  private val Attributes = "; Path=/; Secure; HttpOnly; SameSite=Lax"

  /** 署名と期限を確認する。Cookie の重複や設定不足は未認証 */
  def verify(cookieHeader: String, secret: String, nowSeconds: Double): Future[Option[SessionClaims]] =
    CookieCodec.fromHeader(cookieHeader).fold(Future.successful(Option.empty[SessionClaims])) { session =>
      AuthHmac.verifySession(secret, session.payload, session.signature).map { verified =>
        Option.when(verified && session.claims.isCurrent(nowSeconds))(session.claims)
      }
    }.recover { case NonFatal(_) => None }

  /** HTTPS または開発用 localhost にだけ発行する。期限切れや鍵の欠落は発行しない */
  def issue(claims: SessionClaims, secret: String, requestUrl: String, nowSeconds: Double): Future[Option[String]] = Future.unit.flatMap { _ =>
    if (!canIssue(requestUrl) || !claims.isCurrent(nowSeconds)) Future.successful(None)
    else CookieCodec.payload(claims).fold(Future.successful(Option.empty[String])) { payload =>
      AuthHmac.sessionSignature(secret, payload).map(_.flatMap(CookieCodec.encode(claims, _)).map { value =>
        val maxAge = math.floor(claims.expiresAt.toDouble - nowSeconds).toLong
        s"${CookieCodec.Name}=$value; Max-Age=$maxAge$Attributes"
      })
    }
  }.recover { case NonFatal(_) => None }

  /** 発行時と同じ属性で Cookie を削除する */
  def clear(requestUrl: String): Option[String] =
    Option.when(canIssue(requestUrl))(s"${CookieCodec.Name}=; Max-Age=0$Attributes")

  private def canIssue(requestUrl: String): Boolean = {
    try {
      val url = new URL(requestUrl)
      url.protocol == "https:" || (url.protocol == "http:" && url.hostname == "localhost")
    } catch {
      case NonFatal(_) => false
    }
  }
}
