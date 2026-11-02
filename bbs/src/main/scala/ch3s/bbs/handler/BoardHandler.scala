package ch3s.bbs.handler

import ch3s.bbs.components.{BoardIndexComponent, BoardIndexProps}
import ch3s.bbs.external.hono.{HonoContextFacade, RenderProps}
import ch3s.bbs.external.web.Response
import ch3s.bbs.repository.BoardRepository
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
    val boardSettingsF = BoardRepository.getBoardSettings(bbs, c.env)
    val headTextF = BoardRepository.getHeadText(bbs, c.env)
    val subjectsF = BoardRepository.getSubjects(bbs, c.env)

    (for {
      boardSettings <- boardSettingsF
      headText <- headTextF
      subjects <- subjectsF
      res <- {
        val title = boardSettings.value.getOrElse("BBS_TITLE", "???＠3ch掲示板")

        val props = new RenderProps {}
        props.title = title

        val viewType = if c.req.query("type").contains("feed") then "feed" else "subback"
        val sort = c.req.query("sort").filter(Set("new", "speed", "res")).getOrElse("new")
        c.renderPromise(
          BoardIndexComponent.Component(BoardIndexProps(
            title, headText, bbs, viewType, subjects, boardSettings.value,
            c.req.query("q").getOrElse(""), sort, math.floor(js.Date.now() / 1000).toLong.toString,
            c.env.IMGUR_CLIENT_ID.getOrElse("")
          )).rawElement, props).toFuture
      }
    } yield res).toJSPromise
  }
}
