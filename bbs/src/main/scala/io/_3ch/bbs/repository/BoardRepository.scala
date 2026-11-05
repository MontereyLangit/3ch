package io._3ch.bbs.repository

import io._3ch.bbs.external.cloudflare.{BoardDoStub, WorkerEnv}
import io._3ch.bbs.handler.PostingText
import io._3ch.common.domain.{BbsSettings, BbsSubjects, BoardId}

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

object BoardRepository {

  /**
    * 板のDurable Objectから該当板のスレッド一覧を取得する
    *
    * @param board 板ID
    * @param e WorkerEnv
    * @return subject.txt
    */
  def getSubjects(board: BoardId, e: WorkerEnv): Future[BbsSubjects] = {
     val stub = e.BOARD_DO
       .get(e.BOARD_DO.idFromName(board.value))
       .asInstanceOf[BoardDoStub]
 
     stub.getSubject()
       .toFuture
       .map(t => BbsSubjects.create(t))
   }

  /**
    * R2から該当板のSETTING.TXTを取得する
    * 無い場合は[[PostingText.settingTxtDefault]]を使う
    *
    * @param board 板ID
    * @param e WorkerEnv
    * @return SETTING.TXT
    */
  def getBoardSettings(board: BoardId, e: WorkerEnv): Future[BbsSettings] = {
    e.ch3www.get(board.value + "/SETTING.TXT")
      .toFuture
      .flatMap(o =>
        if o == null then Future.successful(PostingText.settingTxtDefault)
        else o.text().toFuture
      )
      .map(BbsSettings.create(_, board))
  }

  /**
   * R2から該当板のhead.txtを取得する
   * 無い場合は空文字列
   *
   * @param board 板ID
   * @param e WorkerEnv
   * @return head.txt
   */
  def getHeadText(board: BoardId, e: WorkerEnv): Future[String] = {
    e.ch3www.get(board.value + "/head.txt")
      .toFuture
      .flatMap(o =>
        if o == null then Future.successful("")
        else o.text().toFuture
      )
  }
}
