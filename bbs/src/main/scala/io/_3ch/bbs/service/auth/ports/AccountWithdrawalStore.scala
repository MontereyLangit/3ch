package io._3ch.bbs.service.auth.ports

import io._3ch.bbs.domain.account.{AccountStoreException, WithdrawnAccount}
import scala.concurrent.Future

/** 退会に使うアカウント操作。拒否は AccountStoreException の Future の失敗で返す */
trait AccountWithdrawalStore extends IndexHoldings {
  def withdraw(aid: String, generation: Int, now: Double): Future[WithdrawnAccount]
}
