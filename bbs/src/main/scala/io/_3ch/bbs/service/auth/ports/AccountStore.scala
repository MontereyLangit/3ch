package io._3ch.bbs.service.auth.ports

import io._3ch.bbs.domain.account.{TokenReference, TokenProof, TokenAuthentication, CapAuthentication}
import io._3ch.common.domain.account.{AccountDescription, AccountObservation, AccountPost, AccountRejection}
import scala.concurrent.Future

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
