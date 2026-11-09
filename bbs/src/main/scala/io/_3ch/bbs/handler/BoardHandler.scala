package io._3ch.bbs.handler

import io._3ch.bbs.components.{BoardIndexComponent, BoardIndexProps}
import io._3ch.bbs.external.hono.{HonoContextFacade, RenderProps}
import io._3ch.bbs.external.web.Response
import io._3ch.bbs.repository.BoardRepository
import io._3ch.common.domain.BoardId

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
    val raw = c.req.param("bbs").toString
    BoardId.create(raw).fold(_ => js.Promise.resolve[Response](notFound(s"/$raw/")), board => render(c, board))
  }

  /**
   * 無効な板名の場合404
   * 404画面に板名をそのまま出力するので、HTMLエスケープすること
   */
  private def notFound(path: String): Response = Responses.response(PublicText.respond404(path.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")), 404, js.Dictionary("content-type" -> "text/html;charset=UTF-8"))

  private def render(c: HonoContextFacade, board: BoardId): js.Promise[Response] = {
    val bbs = board.value
    val boardSettingsF = BoardRepository.getBoardSettings(board, c.env)
    val headTextF = BoardRepository.getHeadText(board, c.env)
    val subjectsF = BoardRepository.getMergedSubjects(board, c.env)

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
            title, headText, board, viewType, subjects, boardSettings.value,
            c.req.query("q").getOrElse(""), sort, math.floor(js.Date.now() / 1000).toLong.toString,
            c.env.IMGUR_CLIENT_ID.getOrElse("")
          )).rawElement, props).toFuture
      }
    } yield res).toJSPromise
  }
}
