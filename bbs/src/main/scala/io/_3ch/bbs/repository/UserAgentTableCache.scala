package io._3ch.bbs.repository

import io._3ch.bbs.domain.posting.UserAgentTable

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.util.control.NonFatal

/** 読み込んだ UA 解析表を ttlMillis だけ使い回す。読み込みに失敗したら最後に読めた表を返し、一度も読めていなければ空の表 */
final class UserAgentTableCache(ttlMillis: Double, now: () => Double) {
  private var table: Option[UserAgentTable] = None
  private var checkedAt: Double = 0

  def get(fetch: () => Future[UserAgentTable]): Future[UserAgentTable] = table match {
    case Some(cached) if now() - checkedAt < ttlMillis => Future.successful(cached)
    case _ =>
      Future.unit.flatMap(_ => fetch()).map { loaded =>
        table = Some(loaded)
        checkedAt = now()
        loaded
      }.recover { case NonFatal(_) =>
        js.Dynamic.global.console.warn("ua-profiles.json could not be read")
        table.foreach(_ => checkedAt = now())
        table.getOrElse(UserAgentTable.empty)
      }
  }
}
