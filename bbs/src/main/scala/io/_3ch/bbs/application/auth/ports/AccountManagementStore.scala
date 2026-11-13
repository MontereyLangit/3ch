package io._3ch.bbs.application.auth.ports

import io._3ch.common.domain.account.{AccountDescription, AccountProfile, AuthRateCounts}
import scala.concurrent.Future

/** 本人認証・登録と専ブラキー発行に必要なアカウント操作 */
trait AccountManagementStore extends IndexHoldings {
  /** key ごとの認証要求を一件数え、直近一時間・一日の件数を返す */
  def rate(key: String, kind: String, now: Double): Future[AuthRateCounts]
  def reserve(kind: String, hash: String, aid: String, now: Double, tokenId: Option[String] = None): Future[Boolean]
  def confirm(kind: String, hash: String, aid: String, now: Double): Future[Unit]
  def create(profile: AccountProfile, now: Double): Future[AccountDescription]
  def bindDevice(aid: String, device: String, keyIp: String, now: Double): Future[Unit]
  def agree(aid: String, version: String, now: Double): Future[Unit]
  def issueToken(aid: String, tokenId: String, hash: String, now: Double): Future[AccountDescription]
  def revokeToken(aid: String, tokenId: String, now: Double): Future[Unit]
}
