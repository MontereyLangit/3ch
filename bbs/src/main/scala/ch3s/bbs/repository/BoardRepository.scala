package ch3s.bbs.repository

import ch3s.bbs.external.cloudflare.{BoardDoStub, WorkerEnv}
import ch3s.bbs.handler.PostingText
import ch3s.common.domain.{BbsSettings, BbsSubjects}

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

object BoardRepository {

  /**
    * 板のDurable Objectから該当板のスレッド一覧を取得する
    *
    * @param bbs 板ディレクトリ名 TODO: BoardId domain objectにする
    * @param e WorkerEnv
    * @return subject.txt
    */
  def getSubjects(bbs: String, e: WorkerEnv): Future[BbsSubjects] = {
     val stub = e.BOARD_DO
       .get(e.BOARD_DO.idFromName(bbs))
       .asInstanceOf[BoardDoStub]
 
     stub.getSubject()
       .toFuture
       .map(t => BbsSubjects.create(t))
   }

  /**
    * R2から該当板のSETTING.TXTを取得する
    * 無い場合は[[PostingText.settingTxtDefault]]を使う
    *
    * @param bbs 板ディレクトリ名 TODO: BoardId domain objectにする
    * @param e WorkerEnv
    * @return SETTING.TXT
    */
  def getBoardSettings(bbs: String, e: WorkerEnv): Future[BbsSettings] = {
    e.ch3www.get(bbs + "/SETTING.TXT")
      .toFuture
      .flatMap(o =>
        if o == null then Future.successful(PostingText.settingTxtDefault)
        else o.text().toFuture
      )
      .map(BbsSettings.create(_, bbs))
  }

  /**
   * R2から該当板のhead.txtを取得する
   * 無い場合は空文字列
   *
   * @param bbs 板ディレクトリ名 TODO: BoardId domain objectにする
   * @param e WorkerEnv
   * @return head.txt
   */
  def getHeadText(bbs: String, e: WorkerEnv): Future[String] = {
    e.ch3www.get(bbs + "/head.txt")
      .toFuture
      .flatMap(o =>
        if o == null then Future.successful("")
        else o.text().toFuture
      )
  }
}
