package io._3ch.bbs.application.auth.ports

import io._3ch.common.domain.account.Roles
import scala.concurrent.Future

/** 役割の付け外しと role:admin 索引。拒否は AccountStoreException の Future の失敗で返す */
trait AccountRoleStore {
  def setRoles(aid: String, roles: Roles, actor: Roles, now: Double): Future[Unit]
  def grantFirstAdmin(aid: String, now: Double): Future[Unit]
  def claimFirstAdmin(hash: String, aid: String, now: Double, breakGlass: Boolean): Future[Unit]
  def reserve(kind: String, hash: String, aid: String, now: Double, tokenId: Option[String] = None): Future[Boolean]
  def confirm(kind: String, hash: String, aid: String, now: Double): Future[Unit]
  def remove(kind: String, hash: String, aid: String): Future[Unit]
}
