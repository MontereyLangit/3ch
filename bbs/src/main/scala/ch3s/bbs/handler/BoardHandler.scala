package ch3s.bbs.handler

import ch3s.bbs.external.hono.{HonoContextFacade, RenderProps}
import ch3s.bbs.external.web.Response
import ch3s.bbs.repository.BoardRepository
import ch3s.common.domain.BbsSettings
import japgolly.scalajs.react.vdom.html_<^.*

import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js
import scala.scalajs.js.JSConverters.JSRichFutureNonThenable

/**
  * 板topハンドラ
  */
object BoardHandler {

  /**
    * /${bbs}/ 板top
    *
    * @param c Honoコンテキスト
    * @return レンダリング結果(Promise)
    */
  def index(c: HonoContextFacade): js.Promise[Response] = {
    // TODO: bbsが[a-zA-Z0-9]{1,16}でなければ404にする ここもBoardIdにする
    val bbs = c.req.param("bbs").toString
    val settingF = BoardRepository.getBoardSettings(bbs, c.env)
    val headF = BoardRepository.getHeadText(bbs, c.env)

    (for {
      settingText <- settingF
      headText <- headF
      res <- {
        val title = BbsSettings.create(settingText, bbs).value.getOrElse("BBS_TITLE", "???＠3ch掲示板")
        val props = new RenderProps {}
        props.title = title
        val page = <.main(
          <.h1(title),
          <.hr,
          <.div(^.dangerouslySetInnerHtml := headText)
        )
        c.renderPromise(page.rawElement, props).toFuture
      }
    } yield res).toJSPromise
  }
}
