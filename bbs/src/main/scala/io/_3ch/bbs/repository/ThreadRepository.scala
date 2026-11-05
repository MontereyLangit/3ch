package io._3ch.bbs.repository

import io._3ch.bbs.domain.posting.ThreadPosting
import io._3ch.bbs.durable.codec.PostingCodec
import io._3ch.bbs.external.cloudflare.{ThreadDoStub, WorkerEnv}
import io._3ch.common.domain.{BbsSettings, BoardId}

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

object ThreadRepository {
  /** 板とスレキーに対応する Thread DO のスタブ */
  def stub(board: BoardId, key: String, e: WorkerEnv): ThreadDoStub = e.THREAD_DO.get(e.THREAD_DO.idFromName(s"${board.value}/$key"))

  /** スレの DO からスレ設定（キー=値<> の列）を読む。無ければ空 */
  def getSettings(board: BoardId, key: String, e: WorkerEnv): Future[BbsSettings] =
    stub(board, key, e).getSettings().toFuture.map(BbsSettings.parse(_, "<>"))

  /** dat 行と log 行を追記し、確定したレス番号を返す。DO の拒否は Future の失敗になる */
  def addRes(board: BoardId, key: String, datLine: String, logLine: String, posting: ThreadPosting, e: WorkerEnv): Future[Int] =
    stub(board, key, e).addRes(datLine, logLine, PostingCodec.encode(posting)).toFuture
}
