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
  test("設定していない認証エンドポイントは503を返す") {
    val request = HandlerTestKit.request(origin + "/auth/session")
    AuthHandler.handle(request, js.Dynamic.literal().asInstanceOf[WorkerEnv]).toFuture.map { response =>
      assertEquals(response.status, 503)
      assertEquals(response.headers.get("Cache-Control"), "private, no-store")
    }
  }
  test("Origin が無い変更要求は DO と Turnstile より先に拒否する") {
    val request = HandlerTestKit.request(origin + "/auth/verify", headers = Seq("Content-Type" -> "application/x-www-form-urlencoded"), method = "POST", body = "agreement=v1")
    val env = js.Dynamic.literal(AUTH_SECRET = "test-secret", AGREEMENT_VERSION = "v1").asInstanceOf[WorkerEnv]
    AuthHandler.handle(request, env).toFuture.flatMap { response =>
      assertEquals(response.status, 403)
      response.json().toFuture.map(value => assertEquals(value.asInstanceOf[js.Dynamic].code.asInstanceOf[String], "auth:origin"))
    }
  }
  test("一致しない Origin は拒否する") {
    val request = HandlerTestKit.request(origin + "/auth/logout", headers = Seq("Content-Type" -> "application/x-www-form-urlencoded", "Origin" -> "https://evil.test"), method = "POST", body = "")
    val env = js.Dynamic.literal(AUTH_SECRET = "test-secret", AGREEMENT_VERSION = "v1").asInstanceOf[WorkerEnv]
    AuthHandler.handle(request, env).toFuture.map(response => assertEquals(response.status, 403))
  }
}
