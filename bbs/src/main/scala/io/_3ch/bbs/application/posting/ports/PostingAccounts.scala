package io._3ch.bbs.application.posting.ports

import io._3ch.bbs.application.posting.PostingRequest
import io._3ch.bbs.domain.account.{AuthenticatedPoster, AuthenticationFailure}
import io._3ch.bbs.domain.posting.{PostingContext, PostingUser}
import io._3ch.common.domain.account.Roles
import io._3ch.common.domain.posting.MailCommands
import scala.concurrent.Future

/** 投稿者の認証結果。legacyCap は所有者のない旧キャップ、sessionCookie は応答に付ける Set-Cookie の値、roles はアカウントの役割 */
final case class PostingAccount(user: Option[PostingUser], lv: Int = 0, device: String = "", legacyCap: Boolean = false,
  sessionCookie: Option[String] = None, authentication: Option[AuthenticatedPoster] = None, roles: Roles = Roles()) {
  /** BBS_PHOEBE を免除し、スレの必要 Lv を下げられる投稿者 */
  def staff: Boolean = roles.staff
}

/** 認証を有効にした環境で、投稿者の認証と成功した投稿の記録を行う */
trait PostingAccounts {
  /** メール欄のコマンド・照合済みキャップ・Cookie から投稿者を決める。拒否は Left */
  def authenticate(commands: MailCommands, capKey: Option[String], request: PostingRequest, now: Double): Future[Either[AuthenticationFailure, PostingAccount]]

  /** 成功した投稿をアカウントに記録する。期限を延ばして Cookie を発行し直したらその値を返す */
  def posted(context: PostingContext, request: PostingRequest, account: PostingAccount): Future[Option[String]]

  /** 記録に失敗したことを残す。投稿の成否には影響させない */
  def recordFailed(error: Throwable): Unit
}
