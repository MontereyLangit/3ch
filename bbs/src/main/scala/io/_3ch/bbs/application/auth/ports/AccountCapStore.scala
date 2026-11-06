package io._3ch.bbs.application.auth.ports

import io._3ch.common.domain.account.{AccountCap, AuthRateCounts}
import scala.concurrent.Future

/** 再発行で失効させたキャップの照合値と、新しいキャップの KV に書く表示名 */
final case class CapReissue(previousHash: String, storedName: String)

/** キャップの登録・失効と cap 索引。拒否は AccountStoreException の Future の失敗で返す */
trait AccountCapStore {
  def rate(key: String, kind: String, now: Double): Future[AuthRateCounts]
  def reserve(kind: String, hash: String, aid: String, now: Double, tokenId: Option[String] = None): Future[Boolean]
  def confirm(kind: String, hash: String, aid: String, now: Double): Future[Unit]
  def remove(kind: String, hash: String, aid: String): Future[Unit]
  def bindCap(aid: String, cap: AccountCap, issuerIsAdmin: Boolean, now: Double): Future[Unit]
  /** 失効させたキャップの照合値を返す */
  def revokeCap(aid: String, capId: String, now: Double): Future[String]
  def reissueCap(aid: String, capId: String, nextId: String, nextHash: String, issuedBy: String, issuerIsAdmin: Boolean, now: Double): Future[CapReissue]
}

/** KV の CAP（照合キー → 表示名） */
trait CapDirectory {
  def exists(key: String): Future[Boolean]
  def put(key: String, storedName: String): Future[Unit]
  def delete(key: String): Future[Unit]
}
