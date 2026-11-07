package io._3ch.bbs.application.auth.ports

import scala.concurrent.Future

/** 退会した Account から外す値。caps は失効させたキャップの KV の照合値 */
final case class WithdrawnAccount(loginId: Option[String], email: Option[String], googleSubHash: Option[String], caps: Vector[String])

/** 退会に使うアカウント操作。拒否は AccountStoreException の Future の失敗で返す */
trait AccountWithdrawalStore {
  def withdraw(aid: String, generation: Int, now: Double): Future[WithdrawnAccount]
  def remove(kind: String, hash: String, aid: String): Future[Unit]
}
