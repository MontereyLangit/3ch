package io._3ch.bbs.handler.auth

import io._3ch.bbs.handler.HandlerTestKit
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

class TurnstileVerificationSuite extends munit.FunSuite {
  private def mocked(result: js.Any)(body: => Future[Boolean]): Future[Boolean] = {
    val global = HandlerTestKit.global
    val previous = global.fetch
    global.fetch = ((url: String, init: js.Dynamic) => {
      assertEquals(url, "https://challenges.cloudflare.com/turnstile/v0/siteverify")
      assertEquals(init.method.asInstanceOf[String], "POST")
      val encoded = init.body.asInstanceOf[String]
      assert(encoded.contains("response=test-token"))
      js.Promise.resolve(js.Dynamic.literal(ok = true, json = (() => js.Promise.resolve(result)): js.Function0[js.Promise[js.Any]]))
    }): js.Function2[String, js.Dynamic, js.Promise[js.Dynamic]]
    body.transform { value => global.fetch = previous; value }
  }
  test("外部 siteverify の成功と同一ホストだけを認証する") {
    mocked(js.Dynamic.literal(success = true, hostname = "www.3ch.io")) {
      TurnstileVerification.verify("test-server-key", "test-token", "192.0.2.1", "www.3ch.io")
    }.map(verified => assert(verified))
  }
  test("siteverify が成功しても異なるホストは拒否する") {
    mocked(js.Dynamic.literal(success = true, hostname = "evil.test")) {
      TurnstileVerification.verify("test-server-key", "test-token", "192.0.2.1", "www.3ch.io")
    }.map(verified => assert(!verified))
  }
  test("ホストの無い成功結果を拒否する") {
    mocked(js.Dynamic.literal(success = true)) {
      TurnstileVerification.verify("test-server-key", "test-token", "192.0.2.1", "www.3ch.io")
    }.map(verified => assert(!verified))
  }
  test("siteverify の失敗結果を拒否する") {
    mocked(js.Dynamic.literal(success = false, hostname = "www.3ch.io")) {
      TurnstileVerification.verify("test-server-key", "test-token", "192.0.2.1", "www.3ch.io")
    }.map(verified => assert(!verified))
  }
}
