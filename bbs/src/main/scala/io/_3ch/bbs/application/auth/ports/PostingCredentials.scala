package io._3ch.bbs.application.auth.ports

import io._3ch.common.domain.account.SessionClaims
import scala.concurrent.Future

/** 署名済みセッションの検証と発行、索引・専ブラ UA の HMAC */
trait PostingCredentials {
  /** Cookie ヘッダから署名と期限を確かめたセッションを読む */
  def session(cookieHeader: String, now: Double): Future[Option[SessionClaims]]
  /** Set-Cookie の値を作る。発行できない要求や鍵の障害では None */
  def issue(claims: SessionClaims, now: Double): Future[Option[String]]
  def index(kind: String, value: String): Future[Option[String]]
  def ua(userAgent: String): Future[Option[String]]
}
