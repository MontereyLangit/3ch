package io._3ch.bbs.handler.auth

import io._3ch.bbs.application.posting.PostingRequest
import io._3ch.bbs.durable.codec.AccountCodec
import io._3ch.bbs.external.cloudflare.WorkerEnv
import io._3ch.bbs.handler.HandlerTestKit
import io._3ch.bbs.hash.AuthHmac
import io._3ch.bbs.repository.PostingAccountSource
import io._3ch.common.domain.account.*
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*

class AuthVerifyBindingSuite extends munit.FunSuite {
  test("verify で既存キー Cookie の binding を保持し、キーの失効後は投稿を認証しない") {
    val time = 1800000000d
    val secret = "test-secret"
    val userAgent = "Monazilla/1.00 Test/1.0"
    val account = AccountDescription("abcdefghijklmnop", "abcdefgh", AccountKind.Client, 1, Some(time + 31536000), 1, Roles(), time - 1, "v1")
    var revoked = false
    val accountStub = js.Dynamic.literal(
      describeForPosting = ((_: String, _: Int, _: Double, _: js.UndefOr[String], _: js.UndefOr[String]) =>
        js.Promise.resolve(if (revoked) js.Dynamic.literal(code = AccountRejection.TokenRevoked.code) else AccountCodec.description(account))): js.Function5[String, Int, Double, js.UndefOr[String], js.UndefOr[String], js.Promise[js.Dynamic]],
      bindDevice = ((_: String, _: String, _: Double) => js.Promise.resolve("")): js.Function3[String, String, Double, js.Promise[String]],
      agree = ((_: String, _: Double) => js.Promise.resolve("")): js.Function2[String, Double, js.Promise[String]]
    )
    def namespace(stub: js.Dynamic): js.Dynamic = js.Dynamic.literal(
      idFromName = ((value: String) => value): js.Function1[String, String],
      get = ((_: js.Any) => stub): js.Function1[js.Any, js.Dynamic])
    val rate = js.Dynamic.literal(hit = ((_: String, _: Double) => js.Promise.resolve(js.Dynamic.literal(code = "", hour = 1, day = 1, distinctDevices24h = 0))): js.Function2[String, Double, js.Promise[js.Dynamic]])
    val env = js.Dynamic.literal(AUTH_SECRET = secret, AGREEMENT_VERSION = "v1", TURNSTILE_SECRET_KEY = "test-server-key",
      ACCOUNT_DO = namespace(accountStub), AUTH_RATE_DO = namespace(rate),
      ch3www = js.Dynamic.literal(get = ((_: String) => js.Promise.resolve[js.Any](null)): js.Function1[String, js.Promise[js.Any]])).asInstanceOf[WorkerEnv]
    val global = HandlerTestKit.global
    val originalFetch = global.fetch
    global.fetch = ((_: String, _: js.Any) => js.Promise.resolve(js.Dynamic.literal(ok = true,
      json = (() => js.Promise.resolve(js.Dynamic.literal(success = true, hostname = "www.3ch.io"))): js.Function0[js.Promise[js.Dynamic]]))): js.Function2[String, js.Any, js.Promise[js.Dynamic]]
    HandlerTestKit.withFixedTime(time * 1000) {
      for {
        hash <- AuthHmac.uaHash(secret, userAgent)
        binding = SessionBinding("token1", hash.get.take(16))
        cookie <- SessionCookies.issue(SessionClaims(account.aid, account.uid, 1, account.expiresAt.get.toLong, 1, "", Some(binding)), secret, "https://www.3ch.io/auth/verify", time)
        request = HandlerTestKit.request("https://www.3ch.io/auth/verify", headers = Seq("Content-Type" -> "application/x-www-form-urlencoded", "Origin" -> "https://www.3ch.io", "User-Agent" -> userAgent, "CF-Connecting-IP" -> "192.0.2.1", "Cookie" -> cookie.get.takeWhile(_ != ';')),
          method = "POST", body = "agreement=v1&cf-turnstile-response=test-token")
        response <- AuthHandler.handle(request, env).toFuture
        _ = assertEquals(response.status, 200)
        nextCookie = response.headers.get("Set-Cookie").takeWhile(_ != ';')
        claims <- SessionCookies.verify(nextCookie, secret, time)
        _ = assertEquals(claims.flatMap(_.binding), Some(binding))
        _ = { revoked = true }
        result <- new PostingAccountSource(request, env).authenticate("", None, PostingRequest.from(request).copy(cookie = nextCookie), time)
      } yield assertEquals(result.toOption.flatMap(_.user), None)
    }.transform { result => global.fetch = originalFetch; result }
  }
}
