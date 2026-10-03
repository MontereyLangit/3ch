package ch3s.bbs.handler

import munit.FunSuite
import ch3s.bbs.external.cloudflare.WorkerEnv
import scala.collection.mutable
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

/** 管理画面の認証と、変更系リクエストの Origin / Fetch Metadata 検証（admin.md、AGENTS.md 3.6）。 */
class AdminSuite extends FunSuite {
  import HandlerTestKit.*

  private def admin(req: ch3s.bbs.external.web.Request, env: js.Dynamic): Future[js.Dynamic] =
    fut[js.Dynamic](AdminHandler.handle(req, env.asInstanceOf[WorkerEnv]))

  private def basic(credentials: String): String =
    "Basic " + js.Dynamic.global.Buffer.from(credentials).applyDynamic("toString")("base64").asInstanceOf[String]

  test("管理認証は未設定・誤認証を拒否し、UTF-8資格情報を受け付ける") {
    val env = js.Dynamic.literal(ADMIN_USER = "管理者", ADMIN_PASSWORD = "テスト用パスワード")
    val authorization = basic("管理者:テスト用パスワード")
    for {
      unset <- admin(request("https://example.test/admin/"), js.Dynamic.literal())
      _ = assertEquals(status(unset), 503)
      unauthorized <- admin(request("https://example.test/admin/"), env)
      _ = assertEquals(status(unauthorized), 401)
      ok <- admin(request("https://example.test/admin/", Seq("authorization" -> authorization)), env)
      _ = assertEquals(status(ok), 200)
      _ = assertEquals(header(ok, "cache-control"), Some("no-store"))
      _ = assert(header(ok, "content-security-policy").exists(_.contains("frame-ancestors 'none'")))
      deleted <- admin(request("https://example.test/admin/", Seq("authorization" -> authorization), method = "DELETE"), env)
      _ = assertEquals(status(deleted), 405)
    } yield ()
  }

  test("管理変更は同一オリジンを確認し、Origin省略時はFetch Metadataで確認する") {
    val writes = mutable.ListBuffer.empty[String]
    val env = js.Dynamic.literal(
      ADMIN_PASSWORD = "test",
      ch3www = js.Dynamic.literal(
        put = ((key: String) => { writes += key; js.Promise.resolve[js.Any](js.undefined) }): js.Function1[String, js.Promise[js.Any]]
      )
    )
    val authorization = basic("admin:test")
    def post(headers: (String, String)*): Future[Int] =
      admin(request("https://example.test/admin/menu", Seq("authorization" -> authorization) ++ headers, method = "POST", body = "html=menu"), env).map(status)
    for {
      noOrigin <- post()
      _ = assertEquals(noOrigin, 403)
      evil <- post("origin" -> "https://evil.test")
      _ = assertEquals(evil, 403)
      nullOrigin <- post("origin" -> "null", "sec-fetch-site" -> "same-origin")
      _ = assertEquals(nullOrigin, 403)
      sameSite <- post("sec-fetch-site" -> "same-site")
      _ = assertEquals(sameSite, 403)
      _ = assertEquals(writes.length, 0)
      fetchMetadata <- post("sec-fetch-site" -> "same-origin")
      _ = assertEquals(fetchMetadata, 303)
      origin <- post("origin" -> "https://example.test")
      _ = assertEquals(origin, 303)
      _ = assertEquals(writes.length, 2)
    } yield ()
  }
}
