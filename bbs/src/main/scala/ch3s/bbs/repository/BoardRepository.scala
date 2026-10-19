package ch3s.bbs.repository

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import ch3s.bbs.external.cloudflare.WorkerEnv
import ch3s.bbs.handler.PostingText

object BoardRepository {  

  /**
    * R2から該当板のSETTING.TXTを取得する
    * 無い場合は[[PostingText.settingTxtDefault]]を使う
    *
    * @param bbs 板ディレクトリ名 TODO: BoardId domain objectにする
    * @param e WorkerEnv
    * @return SETTING.TXT
    */
  def getBoardSettings(bbs: String, e: WorkerEnv): Future[String] = {
    e.ch3www.get(bbs + "/SETTING.TXT")
        .toFuture
        .flatMap(o => 
            if o == null then Future.successful(PostingText.settingTxtDefault)
            else o.text().toFuture
        )
  }
}
