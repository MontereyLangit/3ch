package io._3ch.bbs.application.auth.ports

import io._3ch.common.domain.account.Roles
import scala.concurrent.Future

/** 役割の付け外しと role:admin 索引。拒否は AccountStoreException の Future の失敗で返す */
trait AccountRoleStore {
  /** 操作する人（actorAid）の今の役割で置き換える。Account DO が actorAid の Account から役割を読む */
  def setRoles(aid: String, roles: Roles, actorAid: String, now: Double): Future[Unit]
  /** role:admin 索引（hash）にこのアカウントの確保がある場合に限り admin を足す */
  def grantFirstAdmin(hash: String, aid: String, now: Double): Future[Unit]
  /** 利用できる admin か */
  def isAdmin(aid: String, now: Double): Future[Boolean]
  /** 他に利用できる admin が居る場合だけ、aid の確保を索引から外す。最後の admin は拒否する */
  def surrender(hash: String, aid: String, now: Double): Future[Unit]
  def claimFirstAdmin(hash: String, aid: String, now: Double, breakGlass: Boolean): Future[Unit]
  def reserve(kind: String, hash: String, aid: String, now: Double, tokenId: Option[String] = None): Future[Boolean]
  def confirm(kind: String, hash: String, aid: String, now: Double): Future[Unit]
  def remove(kind: String, hash: String, aid: String): Future[Unit]
}
