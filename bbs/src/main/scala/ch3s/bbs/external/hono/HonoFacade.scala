package ch3s.bbs.external.hono

import scala.scalajs.js
import scala.scalajs.js.|
import ch3s.bbs.external.web.Response
import japgolly.scalajs.react.facade.React

trait RenderProps extends js.Object {
  var title: js.UndefOr[String] = js.undefined
}

/** Hono の `c.req` / `c.env` 用の最小手書き facade */
@js.native
trait HonoRequestFacade extends js.Object {
  def param(name: String): js.UndefOr[String] = js.native
  def header(name: String): js.UndefOr[String] = js.native
}

@js.native
trait HonoContextFacade extends js.Object {
  val req: HonoRequestFacade = js.native

  /** Workersバインディング（`env.V6_PLACEHOLDER_DO` 等）。呼び出し側で `WorkerEnv` へキャストする。 */
  val env: js.Any = js.native

  def render(children: React.Element, props: js.UndefOr[RenderProps] = js.native): Response | js.Promise[Response] = js.native
}

object HonoContextFacade {
  extension (context: HonoContextFacade) {
    /** 同期・非同期の renderer を Promise に統一する。 */
    def renderPromise(children: React.Element, props: js.UndefOr[RenderProps] = js.undefined): js.Promise[Response] = {
      js.Promise.resolve[Response](context.render(children, props))
    }
  }
}
