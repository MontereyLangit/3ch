package io._3ch.bbs.handler.auth

import io._3ch.bbs.external.cloudflare.WorkerEnv
import io._3ch.bbs.handler.HandlerTestKit
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

class AuthHandlerSuite extends munit.FunSuite {
  private val origin = "https://www.3ch.io"
  test("return は同一オリジンの安全なパスだけを受け付ける") {
    assertEquals(AuthHandler.safeReturn("/sample/?q=test&sort=new", origin + "/auth/"), "/sample/?q=test&sort=new")
    Seq("https://evil.test/", "//evil.test/", "/\\evil.test/", "/%2f%2fevil.test", "/%5cevil.test", "/%0aevil", "/%", "/<script>", "/sample/#x").foreach { value =>
      assertEquals(AuthHandler.safeReturn(value, origin + "/auth/"), "/", value)
    }
  }
  test("設定が揃っていない環境の認証エンドポイントは認証が無いときと同じ404にする") {
    val request = HandlerTestKit.request(origin + "/auth/session")
    val partial = Seq(
      js.Dynamic.literal(),
      js.Dynamic.literal(AUTH_SECRET = AuthTestEnv.secret),
      js.Dynamic.literal(AUTH_SECRET = "short-secret", AGREEMENT_VERSION = "v1", TURNSTILE_SITE_KEY = "site", TURNSTILE_SECRET_KEY = "server"))
    scala.concurrent.Future.traverse(partial)(env => AuthHandler.handle(request, env.asInstanceOf[WorkerEnv]).toFuture).map { responses =>
      responses.foreach { response =>
        assertEquals(response.status, 404)
        assert(response.headers.get("content-type").startsWith("text/html"))
      }
    }
  }
  test("ログアウトはセッションが無効でも Cookie を消す") {
    val request = HandlerTestKit.request(origin + "/auth/logout", headers = Seq("Content-Type" -> "application/x-www-form-urlencoded", "Origin" -> origin,
      "Cookie" -> "__Host-ch3s_sid=1.forged"), method = "POST", body = "")
    AuthHandler.handle(request, AuthTestEnv.env()).toFuture.map { response =>
      assertEquals(response.status, 200)
      assertEquals(response.headers.get("Set-Cookie"), "__Host-ch3s_sid=; Max-Age=0; Path=/; Secure; HttpOnly; SameSite=Lax")
    }
  }
  test("本文の不正な % は要求の誤りにする") {
    val request = HandlerTestKit.request(origin + "/auth/verify", headers = Seq("Content-Type" -> "application/x-www-form-urlencoded", "Origin" -> origin),
      method = "POST", body = "agreement=%E3%81")
    AuthHandler.handle(request, AuthTestEnv.env()).toFuture.flatMap { response =>
      assertEquals(response.status, 400)
      response.json().toFuture.map(value => assertEquals(value.asInstanceOf[js.Dynamic].code.asInstanceOf[String], "auth:invalid"))
    }
  }
  test("Origin が無い変更要求は DO と Turnstile より先に拒否する") {
    val request = HandlerTestKit.request(origin + "/auth/verify", headers = Seq("Content-Type" -> "application/x-www-form-urlencoded"), method = "POST", body = "agreement=v1")
    val env = AuthTestEnv.env()
    AuthHandler.handle(request, env).toFuture.flatMap { response =>
      assertEquals(response.status, 403)
      response.json().toFuture.map(value => assertEquals(value.asInstanceOf[js.Dynamic].code.asInstanceOf[String], "auth:origin"))
    }
  }
  test("一致しない Origin は拒否する") {
    val request = HandlerTestKit.request(origin + "/auth/logout", headers = Seq("Content-Type" -> "application/x-www-form-urlencoded", "Origin" -> "https://evil.test"), method = "POST", body = "")
    val env = AuthTestEnv.env()
    AuthHandler.handle(request, env).toFuture.map(response => assertEquals(response.status, 403))
  }
}
