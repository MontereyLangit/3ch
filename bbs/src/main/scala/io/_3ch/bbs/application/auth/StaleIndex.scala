package io._3ch.bbs.application.auth

import io._3ch.bbs.application.auth.ports.IndexHoldings
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.control.NonFatal

/** 所有者が値を持たなくなった索引（退会・変更の後始末が途中で止まったもの）を解放する */
final class StaleIndex(accounts: IndexHoldings) {
  /** owner が value を今も持っていなければ索引を外して真を返す。持っているか、確かめられなければ偽 */
  def reclaim(kind: String, value: String, hash: String, owner: String): Future[Boolean] =
    accounts.holds(owner, kind, value).flatMap { held =>
      if (held) Future.successful(false) else accounts.remove(kind, hash, owner).map(_ => true)
    }.recover { case NonFatal(_) => false }
}
