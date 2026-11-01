package ch3s.bbs.handler

import ch3s.bbs.external.cloudflare.WorkerEnv
import ch3s.bbs.external.hono.HonoContextFacade
import ch3s.bbs.external.web.{Response, ResponseInit}

import scala.scalajs.js

/** `GET /_v6/health`。DO への RPC を含む骨格の疎通確認用 */
object HealthHandler {

  val Body: String = "ch3s v6s ok"

  def health(c: HonoContextFacade): js.Promise[Response] = {
    val env = c.env.asInstanceOf[WorkerEnv]
    val stub = env.V6_PLACEHOLDER_DO.get(env.V6_PLACEHOLDER_DO.idFromName("v6-health-check"))

    stub.ping().`then` { pong =>
      val init = new ResponseInit {}
      init.status = 200
      init.headers = js.Dynamic.literal("content-type" -> "text/plain; charset=utf-8").asInstanceOf[js.Object]
      new Response(s"$Body (do:$pong)", init)
    }
  }
}
