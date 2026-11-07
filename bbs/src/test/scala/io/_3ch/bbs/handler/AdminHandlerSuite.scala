package io._3ch.bbs.handler

import munit.FunSuite
import io._3ch.bbs.external.cloudflare.WorkerEnv
import io._3ch.bbs.handler.admin.AdminAssets
import scala.collection.mutable
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

/** 管理画面の認証、SPA の起動ページと資産の配信、他オリジンからの変更の拒否を検証する。 */
class AdminHandlerSuite extends FunSuite {
  import HandlerTestKit.*

  private def admin(req: io._3ch.bbs.external.web.Request, env: js.Dynamic): Future[js.Dynamic] =
    fut[js.Dynamic](AdminHandler.handle(req, env.asInstanceOf[WorkerEnv]))

  private def basic(credentials: String): String =
    "Basic " + js.Dynamic.global.Buffer.from(credentials).applyDynamic("toString")("base64").asInstanceOf[String]

  /** dist/admin/ の代わり。files に無いパスは 404 で、要求されたパスを fetched に残す */
  private def assets(fetched: mutable.ListBuffer[String], files: Map[String, String]): js.Dynamic = js.Dynamic.literal(
    fetch = ((req: js.Dynamic) => {
      val path = js.Dynamic.newInstance(js.Dynamic.global.URL)(req.url).pathname.asInstanceOf[String]
      fetched += path
      val response = files.get(path).fold(js.Dynamic.newInstance(js.Dynamic.global.Response)("Not Found", js.Dynamic.literal(status = 404))) { body =>
        js.Dynamic.newInstance(js.Dynamic.global.Response)(body, js.Dynamic.literal(headers = js.Dynamic.literal("content-type" -> "text/html", "etag" -> "\"a1\"")))
      }
      js.Promise.resolve[js.Any](response)
    }): js.Function1[js.Dynamic, js.Promise[js.Any]])

  private val index = Map(AdminAssets.IndexPath -> "<div id=\"admin-root\"></div>", "/admin/assets/index-a1.js" -> "export {}")

  test("管理認証は未設定・誤認証を拒否し、UTF-8資格情報を受け付ける") {
    val fetched = mutable.ListBuffer.empty[String]
    val env = js.Dynamic.literal(ADMIN_USER = "管理者", ADMIN_PASSWORD = "テスト用パスワード", ASSETS = assets(fetched, index))
    val authorization = basic("管理者:テスト用パスワード")
    for {
      unset <- admin(request("https://example.test/admin/"), js.Dynamic.literal())
      _ = assertEquals(status(unset), 503)
      unauthorized <- admin(request("https://example.test/admin/"), env)
      _ = assertEquals(status(unauthorized), 401)
      _ = assert(header(unauthorized, "www-authenticate").exists(_.startsWith("Basic")))
      asset <- admin(request("https://example.test/admin/assets/index-a1.js"), env)
      _ = assertEquals(status(asset), 401)
      _ = assertEquals(fetched.toList, Nil, "認証の前に資産を読まない")
      ok <- admin(request("https://example.test/admin/", Seq("authorization" -> authorization)), env)
      _ = assertEquals(status(ok), 200)
      _ = assertEquals(header(ok, "cache-control"), Some("no-store"))
      _ = assertEquals(header(ok, "x-content-type-options"), Some("nosniff"))
      _ = assertEquals(header(ok, "referrer-policy"), Some("no-referrer"))
      _ = assertEquals(header(ok, "content-security-policy"), Some(AdminAssets.ContentSecurityPolicy))
      _ = assert(AdminAssets.ContentSecurityPolicy.contains("script-src 'self';"))
      _ = assert(AdminAssets.ContentSecurityPolicy.contains("frame-ancestors 'none'"))
      deleted <- admin(request("https://example.test/admin/", Seq("authorization" -> authorization), method = "DELETE"), env)
      _ = assertEquals(status(deleted), 405)
    } yield ()
  }

  test("画面の URL は起動ページを返し、資産はそのファイルを長く持たせて返す") {
    val fetched = mutable.ListBuffer.empty[String]
    val env = js.Dynamic.literal(ADMIN_PASSWORD = "test", ASSETS = assets(fetched, index))
    val auth = Seq("authorization" -> basic("admin:test"))
    def get(path: String): Future[(Int, String, js.Dynamic)] = admin(request("https://example.test" + path, auth), env).flatMap(res => text(res).map((status(res), _, res)))
    for {
      pages <- Future.sequence(List("/admin/", "/admin/boards/sample/", "/admin/boards/sample/threads/1800000000/", "/admin/rules/access/", "/admin/unknown/").map(get))
      _ = pages.foreach { case (code, body, _) => assertEquals((code, body), (200, index(AdminAssets.IndexPath))) }
      _ = assertEquals(fetched.toList, List.fill(5)(AdminAssets.IndexPath))
      script <- get("/admin/assets/index-a1.js")
      _ = assertEquals(script._1, 200)
      _ = assertEquals(script._2, "export {}")
      _ = assertEquals(header(script._3, "cache-control"), Some("private, max-age=31536000, immutable"))
      _ = assertEquals(header(script._3, "x-content-type-options"), Some("nosniff"))
      missing <- get("/admin/assets/missing.js")
      _ = assertEquals(missing._1, 404)
      _ = assertEquals(header(missing._3, "cache-control"), Some("no-store"))
      unbuilt <- admin(request("https://example.test/admin/", auth), js.Dynamic.literal(ADMIN_PASSWORD = "test", ASSETS = assets(fetched, Map.empty)))
    } yield {
      assertEquals(status(unbuilt), 503)
      assertEquals(header(unbuilt, "cache-control"), Some("no-store"))
    }
  }

  test("管理 API の変更は同じオリジンの管理画面からだけ受け付ける") {
    val kit = io._3ch.bbs.durable.DurableTestKit.newEnv()
    val env = kit.worker.asInstanceOf[js.Dynamic]
    env.ADMIN_PASSWORD = "test"
    val authorization = basic("admin:test")
    def put(headers: (String, String)*): Future[(Int, String)] =
      admin(request("https://example.test/admin/api/menu", Seq("authorization" -> authorization, "content-type" -> "application/json") ++ headers, method = "PUT",
        body = """{"text":"menu","etag":null}"""), env).flatMap(res => text(res).map(status(res) -> _))
    for {
      noOrigin <- put("x-ch3s-admin" -> "1")
      _ = assertEquals(noOrigin._1, 403)
      _ = assert(noOrigin._2.contains("\"admin:origin\""), noOrigin._2)
      crossOrigin <- put("origin" -> "https://other.test", "x-ch3s-admin" -> "1")
      _ = assertEquals(crossOrigin._1, 403)
      otherScheme <- put("origin" -> "http://example.test", "x-ch3s-admin" -> "1")
      _ = assertEquals(otherScheme._1, 403)
      nullOrigin <- put("origin" -> "null", "sec-fetch-site" -> "same-origin", "x-ch3s-admin" -> "1")
      _ = assertEquals(nullOrigin._1, 403)
      fetchMetadata <- put("sec-fetch-site" -> "same-origin", "x-ch3s-admin" -> "1")
      _ = assertEquals(fetchMetadata._1, 403)
      noMarker <- put("origin" -> "https://example.test")
      _ = assertEquals(noMarker._1, 403)
      wrongMarker <- put("origin" -> "https://example.test", "x-ch3s-admin" -> "0")
      _ = assertEquals(wrongMarker._1, 403)
      _ = assertEquals(kit.r2Text("bbsmenu.html"), None)
      read <- admin(request("https://example.test/admin/api/menu", Seq("authorization" -> authorization)), env)
      _ = assertEquals(status(read), 200, "読み取りは Origin を問わない")
      saved <- put("origin" -> "https://example.test", "x-ch3s-admin" -> "1")
    } yield {
      assertEquals(saved._1, 200)
      assertEquals(kit.r2Text("bbsmenu.html"), Some("menu"))
    }
  }

  test("Origin の検査は GET・HEAD 以外のすべてのメソッドにかかる") {
    import io._3ch.bbs.handler.admin.AdminOrigin.allows
    val url = "https://example.test/admin/api/menu"
    assert(allows("GET", url, None, None))
    assert(allows("HEAD", url, Some("https://other.test"), None))
    for (method <- List("POST", "PUT", "DELETE", "PATCH")) {
      assert(!allows(method, url, None, Some("1")), method)
      assert(!allows(method, url, Some("https://example.test:8443"), Some("1")), method)
      assert(allows(method, url, Some("https://example.test"), Some("1")), method)
    }
  }
}
