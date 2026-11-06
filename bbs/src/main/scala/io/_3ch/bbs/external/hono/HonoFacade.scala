package io._3ch.bbs.external.hono

import io._3ch.bbs.external.cloudflare.WorkerEnv
import io._3ch.bbs.external.web.Response
import japgolly.scalajs.react.facade.React

import scala.scalajs.js
import scala.scalajs.js.|

trait RenderProps extends js.Object {
  var title: js.UndefOr[String] = js.undefined
}

/** Hono の `c.req` / `c.env` 用の最小手書き facade */
@js.native
trait HonoRequestFacade extends js.Object {
  val raw: io._3ch.bbs.external.web.Request = js.native
  def param(name: String): js.UndefOr[String] = js.native

  def query(name: String): js.UndefOr[String] = js.native
  def header(name: String): js.UndefOr[String] = js.native
}

@js.native
trait HonoContextFacade extends js.Object {
  val req: HonoRequestFacade = js.native
  val env: WorkerEnv = js.native
  val res: Response = js.native
  def header(name: String, value: String): Unit = js.native

  def render(children: React.Element, props: js.UndefOr[RenderProps] = js.native): Response | js.Promise[Response] = js.native
}

object HonoContextFacade {
  extension (context: HonoContextFacade) {
    def renderPromise(children: React.Element, props: js.UndefOr[RenderProps] = js.undefined): js.Promise[Response] = {
      js.Promise.resolve[Response](context.render(children, props))
    }
  }
}
