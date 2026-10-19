package ch3s.bbs.handler

import ch3s.bbs.external.cloudflare.{V6PlaceholderDoStub, WorkerEnv}
import ch3s.bbs.external.hono.{HonoContextFacade, RenderProps}
import ch3s.bbs.external.hono.HonoContextFacade
import ch3s.bbs.external.web.{Response, ResponseInit}
import japgolly.scalajs.react.vdom.html_<^.*
import scala.scalajs.js

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
    // TODO: 板名取得
    // head.txt取得
    // TODO: スレ一覧取得 -> 組立
    val props = new RenderProps {}
    props.title = "板top"
    val page = <.main(<.h1("板top"))
    c.renderPromise(page.rawElement, props)
  }
}
