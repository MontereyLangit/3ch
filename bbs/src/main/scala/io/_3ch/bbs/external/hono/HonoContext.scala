package io._3ch.bbs.external.hono

import io._3ch.bbs.external.web.{Request, Response}
import japgolly.scalajs.react.facade.React

import scala.scalajs.js
import scala.scalajs.js.|

/** Hono の `c.req` の最小手書き facade */
@js.native
trait HonoRequestFacade extends js.Object {
  val raw: Request = js.native
  def param(name: String): js.UndefOr[String] = js.native

  def query(name: String): js.UndefOr[String] = js.native
  def header(name: String): js.UndefOr[String] = js.native
}

/** Hono の Context。Env は `c.env`、R は `c.render` に渡すレンダラの props */
@js.native
trait HonoContext[Env, R <: js.Object] extends js.Object {
  val req: HonoRequestFacade = js.native
  val env: Env = js.native
  val res: Response = js.native
  def header(name: String, value: String): Unit = js.native

  def render(children: React.Element, props: js.UndefOr[R] = js.native): Response | js.Promise[Response] = js.native
}

object HonoContext {
  extension [Env, R <: js.Object](context: HonoContext[Env, R]) {
    def renderPromise(children: React.Element, props: js.UndefOr[R] = js.undefined): js.Promise[Response] = {
      js.Promise.resolve[Response](context.render(children, props))
    }
  }
}
