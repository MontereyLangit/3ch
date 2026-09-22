package ch3s.bbs.handler

import ch3s.bbs.external.cloudflare.{V6PlaceholderDoStub, WorkerEnv}
import ch3s.bbs.external.hono.HonoContextFacade
import ch3s.bbs.external.web.{Response, ResponseInit}

import scala.scalajs.js

/**
 * `GET /_v6/health`。v6s ビルド骨格が実際にリクエストを処理できることを示すためだけの
 * 固定文字列レスポンス。BBSロジックは一切含まない（docs/移植方針_step3_ScalaJS.md のスコープ外）。
 *
 * `V6PlaceholderDurableObject.ping()` をRPC経由で呼び、その結果をレスポンスへ含める。
 * これにより「named export ワークアラウンド」と「DOバインディング」の両方が実機（`wrangler dev`）で
 * 実際に機能していることを、DOの一覧登録だけでなく実インスタンス化・実RPC呼び出しで証明する。
 */
object HealthHandler {

  val Body: String = "ch3s v6s ok"

  def health(c: HonoContextFacade): js.Promise[Response] = {
    val env = c.env.asInstanceOf[WorkerEnv]
    val stub = env.V6_PLACEHOLDER_DO
      .get(env.V6_PLACEHOLDER_DO.idFromName("v6-health-check"))
      .asInstanceOf[V6PlaceholderDoStub]

    stub.ping().`then` { pong =>
      val init = new ResponseInit {}
      init.status = 200
      init.headers = js.Dynamic.literal("content-type" -> "text/plain; charset=utf-8").asInstanceOf[js.Object]
      new Response(s"$Body (do:$pong)", init)
    }
  }
}
