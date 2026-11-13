package io._3ch.bbs.repository

import io._3ch.bbs.application.posting.ports.{ThreadSnapshot, ThreadStore}
import io._3ch.bbs.domain.posting.{ThreadPosting, ThreadWriteResult}
import io._3ch.bbs.domain.posting.moderation.ThreadCreationRejection
import io._3ch.bbs.durable.codec.{PostingCodec, ThreadOwnerCodec}
import io._3ch.bbs.external.cloudflare.{ThreadDoStub, WorkerEnv}
import io._3ch.common.domain.{BbsSettings, BoardId}
import io._3ch.common.domain.posting.ThreadWriteRejection

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

object ThreadRepository {
  /** 板とスレキーに対応する Thread DO のスタブ */
  def stub(board: BoardId, key: String, e: WorkerEnv): ThreadDoStub = e.THREAD_DO.get(e.THREAD_DO.idFromName(s"${board.value}/$key"))

  /** スレの DO からスレ設定（キー=値<> の列）を読む。無ければ空 */
  def getSettings(board: BoardId, key: String, e: WorkerEnv): Future[BbsSettings] =
    stub(board, key, e).getSettings().toFuture.map(BbsSettings.parse(_, "<>"))

  /** dat 行と log 行を追記し、確定したレス番号を返す。スレの状態とスレ立て規制による拒否は結果で返し、それ以外の障害は Future の失敗 */
  def addRes(board: BoardId, key: String, datLine: String, logLine: String, posting: ThreadPosting, e: WorkerEnv): Future[ThreadWriteResult] =
    stub(board, key, e).addRes(datLine, logLine, PostingCodec.encode(posting)).toFuture
      .map(ThreadWriteResult.Written(_))
      .recover { case Refusal(result) => result }

  /** 投稿処理のポート */
  def store(e: WorkerEnv): ThreadStore = new ThreadStore {
    def datBeforeCreation(board: BoardId, key: String): Future[String] = stub(board, key, e).getDat().toFuture

    def readForReply(board: BoardId, key: String): Future[ThreadSnapshot] = {
      val thread = stub(board, key, e)
      val dat = thread.getDat(board.value, key).toFuture
      val settings = getSettings(board, key, e)
      val owner = thread.getOwner().toFuture
      dat.zip(settings).zip(owner).map { case ((d, s), o) =>
        ThreadSnapshot(d, s, () => if (js.DynamicImplicits.truthValue(o.asInstanceOf[js.Dynamic])) Some(ThreadOwnerCodec.decode(o.asInstanceOf[js.Dynamic])) else None)
      }
    }

    def addRes(board: BoardId, key: String, datLine: String, logLine: String, posting: ThreadPosting): Future[ThreadWriteResult] =
      ThreadRepository.addRes(board, key, datLine, logLine, posting, e)
  }

  /** DO が投げたエラーの文字列（Error: の後）が拒否のコードなら結果にする */
  private object Refusal {
    def unapply(error: Throwable): Option[ThreadWriteResult] = {
      val text = error match {
        case x: js.JavaScriptException => String.valueOf(x.exception)
        case other => other.toString
      }
      val code = text.stripPrefix("Error: ")
      ThreadWriteRejection.fromCode(code).map(ThreadWriteResult.Refused(_))
        .orElse(ThreadCreationRejection.fromCode(code).map(ThreadWriteResult.CreationLimited(_)))
    }
  }
}
