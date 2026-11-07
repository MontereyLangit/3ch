package io._3ch.bbs.handler.auth

import io._3ch.bbs.external.web.Request
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

class AuthEdgeSuite extends munit.FunSuite {
  private val verify = Seq("agreement" -> "v1", "cf-turnstile-response" -> "accepted")
  private def from(request: Request, asn: Int): Request = {
    request.asInstanceOf[js.Dynamic].cf = js.Dynamic.literal(asn = asn, country = "JP")
    request
  }

  test("規則が on ならデータセンターの AS からの認証を Turnstile と DO より先に拒否し、log なら通す") {
    val t = new AuthSignInTestKit()
    t.kit.putR2("edge-rules.json", """{"mode":"on","datacenterAsns":[16509]}""")
    t.run {
      for {
        denied <- t.send(from(t.post("/auth/verify", verify), 16509))
        body <- t.body(denied)
        _ = assertEquals((denied.status, body.code.asInstanceOf[String]), (403, "auth:edge:datacenter"))
        _ = assertEquals(t.external.size, 0)
        login <- t.send(from(t.post("/auth/login", Seq("id" -> "taro_2026", "password" -> "x" * 8, "cf-turnstile-response" -> "accepted")), 16509))
        _ = assertEquals(login.status, 403)
        home <- t.send(from(t.post("/auth/verify", verify), 2516))
        _ = assertEquals(home.status, 200)
        _ = t.kit.putR2("edge-rules.json", """{"mode":"log","datacenterAsns":[16509]}""")
        logged <- t.send(from(t.post("/auth/verify", verify, ip = "192.0.2.70"), 16509))
      } yield assertEquals(logged.status, 200)
    }
  }

  test("乱造検知は閾値を超えた認証を拒否し、自動では規制せず「要確認」に接続元と理由を残す") {
    val t = new AuthSignInTestKit()
    val now = math.floor(js.Date.now() / 1000)
    t.run {
      for {
        key <- t.keyIp("192.0.2.77")
        rate = t.kit.worker.AUTH_RATE_DO.get(t.kit.worker.AUTH_RATE_DO.idFromName(key))
        _ <- Future.traverse((1 to 10).toVector)(_ => rate.hit("verify", now).toFuture)
        limited <- t.send(t.post("/auth/verify", verify, ip = "192.0.2.77"))
        again <- t.send(t.post("/auth/verify", verify, ip = "192.0.2.77"))
        review <- t.kit.worker.AUTH_RATE_DO.get(t.kit.worker.AUTH_RATE_DO.idFromName("review")).flagged().toFuture
        _ <- Future.traverse((1 to 5).toVector)(_ => rate.hit("register", now).toFuture)
        register <- t.send(t.post("/auth/register", Seq("id" -> "taro_2026", "password" -> "correct horse", "password2" -> "correct horse") ++ verify, ip = "192.0.2.77"))
        afterRegister <- t.kit.worker.AUTH_RATE_DO.get(t.kit.worker.AUTH_RATE_DO.idFromName("review")).flagged().toFuture
      } yield {
        assertEquals((limited.status, again.status, register.status), (429, 429, 429))
        assertEquals(review.toVector.map(f => (f.keyIpHash.asInstanceOf[String], f.reason.asInstanceOf[String], f.count.asInstanceOf[Int])), Vector((key, "verify", 2)))
        assertEquals(afterRegister.toVector.map(_.reason.asInstanceOf[String]).sorted, Vector("register", "verify"))
        assertEquals(t.kit.accountNames, Set.empty[String])
      }
    }
  }
}

