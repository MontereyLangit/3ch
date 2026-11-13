package io._3ch.bbs.application.auth.ports

import io._3ch.common.domain.account.{AccountDescription, AuthRateCounts, PasswordCredential, PendingKind}
import scala.concurrent.Future

/** 期限内の保留トークンの持ち主と、メール認証で確定させるアドレス */
final case class PendingCheck(uid: String, email: Option[String])

/** メール認証の結果と、置き換えた前のアドレス */
final case class EmailVerified(account: AccountDescription, previous: Option[String])

/** メール認証・パスワードの変更と再設定に使うアカウント操作。拒否は AccountStoreException の Future の失敗で返す */
trait AccountEmailStore extends IndexHoldings {
  def rate(key: String, kind: String, now: Double): Future[AuthRateCounts]
  def lookup(kind: String, hash: String): Future[Option[String]]
  def reserve(kind: String, hash: String, aid: String, now: Double, tokenId: Option[String] = None): Future[Boolean]
  def confirm(kind: String, hash: String, aid: String, now: Double): Future[Unit]
  /** 保留トークンを保存する。再設定なら送り先のアドレスを返す */
  def issuePending(aid: String, kind: PendingKind, hash: String, email: Option[String], now: Double): Future[Option[String]]
  def checkPending(aid: String, kind: PendingKind, hash: String, now: Double): Future[PendingCheck]
  def verifyEmail(aid: String, hash: String, now: Double): Future[EmailVerified]
  def resetPassword(aid: String, hash: String, credential: PasswordCredential, now: Double): Future[AccountDescription]
  def verifyPassword(aid: String, password: String, keyIpHash: String, now: Double): Future[SignInResult]
  def setPassword(aid: String, credential: PasswordCredential, now: Double): Future[AccountDescription]
}
