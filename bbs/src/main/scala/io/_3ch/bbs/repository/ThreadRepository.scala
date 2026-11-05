package io._3ch.bbs.repository

import io._3ch.bbs.external.cloudflare.{ThreadDoStub, WorkerEnv}
import io._3ch.common.domain.{BbsSettings, BoardId}

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

object ThreadRepository {
  def stub(board: BoardId, key: String, e: WorkerEnv): ThreadDoStub = e.THREAD_DO.get(e.THREAD_DO.idFromName(s"${board.value}/$key"))

  /** スレの Durable Object からスレ設定（`キー=値<>` 列）を読む。無ければ空。 */
  def getSettings(board: BoardId, key: String, e: WorkerEnv): Future[BbsSettings] =
    stub(board, key, e).getSettings().toFuture.map(BbsSettings.parse(_, "<>"))
}
