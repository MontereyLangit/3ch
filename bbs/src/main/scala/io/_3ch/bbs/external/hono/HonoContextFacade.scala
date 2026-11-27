package io._3ch.bbs.external.hono

import hx4s.hono.Context
import io._3ch.bbs.external.cloudflare.WorkerEnv
import io._3ch.bbs.external.web.{Request, Response}
import japgolly.scalajs.react.facade.React

import scala.scalajs.js

/** 3ch のレンダラ（app/routes/_renderer.tsx）が受け取る props */
trait RenderProps extends js.Object {
  var title: js.UndefOr[String] = js.undefined
  var authModal: js.UndefOr[Boolean] = js.undefined
}

/** 3ch の Hono Context */
type HonoContextFacade = Context[WorkerEnv, RenderProps]

/** hx4s の Context が使う scalajs-dom の Request / Response を、3ch の Fetch facade（external.web）として読み替える境界 */
object HonoContextFacade {
  extension (c: HonoContextFacade) {
    /** c.req.raw */
    def request: Request = c.req.raw.asInstanceOf[Request]

    /** c.render の結果を Promise にそろえて返す */
    def renderPage(children: React.Element, props: js.UndefOr[RenderProps] = js.undefined): js.Promise[Response] =
      c.renderPromise(children, props).asInstanceOf[js.Promise[Response]]
  }
}
