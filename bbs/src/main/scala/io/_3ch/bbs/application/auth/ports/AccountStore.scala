package io._3ch.bbs.application.auth.ports

import io._3ch.common.domain.account.{AccountDescription, AccountObservation, AccountPost, AccountRejection}
import scala.concurrent.Future

/** 専ブラキーの索引から得た参照 */
final case class TokenReference(aid: String, tokenId: String)

/** Account DO が照合する専ブラキーと完全な UA ハッシュ */
final case class TokenProof(tokenId: String, uaHash: String)

/** 認証したキーと現在のアカウント */
final case class TokenAuthentication(account: AccountDescription, tokenId: String)
final case class CapAuthentication(account: AccountDescription, capId: String)

/** アカウント DO の応答が拒否または不正だったことを Future の失敗として運ぶ */
final case class AccountStoreException(reason: AccountRejection) extends RuntimeException(reason.code)

/** 投稿時に使うアカウントと索引の読み書き */
trait AccountStore {
  def describe(aid: String, generation: Int, now: Double, token: Option[TokenProof]): Future[Either[AccountRejection, AccountDescription]]
  def token(hash: String): Future[Either[AccountRejection, Option[TokenReference]]]
  def useToken(reference: TokenReference, uaHash: String, uaLabel: String, now: Double): Future[Either[AccountRejection, TokenAuthentication]]
  def capOwner(hash: String): Future[Either[AccountRejection, Option[String]]]
  def describeCap(aid: String, capKey: String, now: Double): Future[Either[AccountRejection, CapAuthentication]]
  /** 成功した投稿で期限・投稿数・端末を更新し、更新後のアカウントを返す */
  def recordPost(aid: String, post: AccountPost, observation: AccountObservation): Future[AccountDescription]
}
