package io._3ch.bbs.application.auth.ports

import io._3ch.common.domain.account.{AccountConversion, AccountDescription}
import scala.concurrent.Future

/** クライアント ID からの移行に使うアカウント操作。拒否は AccountStoreException の Future の失敗で返す */
trait AccountConversionStore {
  def lookup(kind: String, hash: String): Future[Option[String]]
  def reserve(kind: String, hash: String, aid: String, now: Double, tokenId: Option[String] = None): Future[Boolean]
  def confirm(kind: String, hash: String, aid: String, now: Double): Future[Unit]
  def remove(kind: String, hash: String, aid: String): Future[Unit]
  def convert(aid: String, target: AccountConversion, uid: String, generation: Int, now: Double): Future[AccountDescription]
  def bindDevice(aid: String, device: String, keyIp: String, now: Double): Future[Unit]
}
