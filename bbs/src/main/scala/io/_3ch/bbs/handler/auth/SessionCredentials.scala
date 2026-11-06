package io._3ch.bbs.handler.auth

import io._3ch.bbs.application.auth.ports.PostingCredentials
import io._3ch.bbs.hash.AuthHmac
import io._3ch.common.domain.account.SessionClaims
import scala.concurrent.Future

/** セッション Cookie と HMAC を投稿の認証に渡す。requestUrl で Cookie を発行してよい要求かを決める */
final class SessionCredentials(secret: String, requestUrl: String) extends PostingCredentials {
  def session(cookieHeader: String, now: Double): Future[Option[SessionClaims]] = SessionCookies.verify(cookieHeader, secret, now)
  def issue(claims: SessionClaims, now: Double): Future[Option[String]] = SessionCookies.issue(claims, secret, requestUrl, now)
  def index(kind: String, value: String): Future[Option[String]] = AuthHmac.indexKey(secret, kind, value)
  def ua(userAgent: String): Future[Option[String]] = AuthHmac.uaHash(secret, userAgent)
}
