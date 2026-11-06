package io._3ch.bbs.application.auth.ports

import io._3ch.common.domain.account.SessionClaims
import scala.concurrent.Future

/** 署名済みセッションの検証と索引・専ブラ UA の HMAC */
trait PostingCredentials {
  def session(cookieHeader: String, now: Double): Future[Option[SessionClaims]]
  def index(kind: String, value: String): Future[Option[String]]
  def ua(userAgent: String): Future[Option[String]]
}
