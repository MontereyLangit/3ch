package io._3ch.bbs.handler.auth

import io._3ch.bbs.external.web.{Response, URL}
import io._3ch.common.domain.account.AccountKind
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

class AccountUpgradeSuite extends munit.FunSuite {
  private def now: Double = math.floor(js.Date.now() / 1000)
  private val verify = Seq("agreement" -> "v1", "cf-turnstile-response" -> "accepted")
  private def upgrade(id: String) = Seq("id" -> id, "password" -> "correct horse", "password2" -> "correct horse", "agreement" -> "v1",
    "cf-turnstile-response" -> "accepted", "upgrade" -> "1")

  /** かんたん登録して Cookie と aid・自動採番の uid を返す */
  private def client(t: AuthSignInTestKit, ip: String = "192.0.2.80"): Future[(String, String, String)] =
    t.send(t.post("/auth/verify", verify, ip = ip)).flatMap { response =>
      val cookie = t.cookie(response, "__Host-ch3s_sid").get
      SessionCookies.verify(cookie, AuthTestEnv.secret, now).map(s => (cookie, s.get.aid, s.get.uid))
    }

  test("かんたん登録を ID・パスワードへ移すと aid と Lv・書き込み数を引き継ぎ、uid を替えて旧 uid の索引を残す") {
    val t = new AuthSignInTestKit()
    t.run {
      for {
        (cookie, aid, oldUid) <- client(t)
        _ <- t.kit.account(aid).setLevel(4, now).toFuture
        moved <- t.send(t.post("/auth/register", upgrade("Taro_2026"), cookie = cookie))
        body <- t.body(moved)
        _ = assertEquals((moved.status, body.uid.asInstanceOf[String], body.lv.asInstanceOf[Int]), (200, "taro_2026", 4), js.JSON.stringify(body))
        session <- SessionCookies.verify(t.cookie(moved, "__Host-ch3s_sid").get, AuthTestEnv.secret, now)
        panel <- t.kit.account(aid).inspect().toFuture
        oldHash <- t.index("uid", oldUid)
        newHash <- t.index("uid", "taro_2026")
        oldOwner <- t.kit.index("uid").lookup(oldHash).toFuture
        newOwner <- t.kit.index("uid").lookup(newHash).toFuture
        stale <- t.send(t.get("/auth/session", cookie = cookie)).flatMap(t.body)
        login <- t.send(t.post("/auth/login", Seq("id" -> "taro_2026", "password" -> "correct horse", "cf-turnstile-response" -> "accepted"), ip = "192.0.2.81"))
        loginBody <- t.body(login)
      } yield {
        assertEquals(session.map(s => (s.aid, s.generation)), Some((aid, 1L)))
        assertEquals(panel.account.kind.asInstanceOf[String], AccountKind.Password.code)
        assert(js.isUndefined(panel.account.expiresAt))
        assertEquals((oldOwner.aid.asInstanceOf[String], newOwner.aid.asInstanceOf[String]), (aid, aid))
        assertEquals(stale, null)
        assertEquals(loginBody.uid.asInstanceOf[String], "taro_2026")
      }
    }
  }

  test("使えないユーザー ID への移行は何も変えず、クライアント ID 以外とログインしていない要求は移さない") {
    val t = new AuthSignInTestKit()
    t.run {
      for {
        _ <- t.send(t.post("/auth/register", upgrade("taro_2026").filterNot(_._1 == "upgrade"), ip = "192.0.2.82"))
        (cookie, aid, _) <- client(t)
        taken <- t.send(t.post("/auth/register", upgrade("taro_2026"), cookie = cookie))
        _ = assertEquals(taken.status, 409)
        unchanged <- t.kit.account(aid).inspect().toFuture
        _ = assertEquals(unchanged.account.kind.asInstanceOf[String], AccountKind.Client.code)
        same <- t.send(t.get("/auth/session", cookie = cookie)).flatMap(t.body)
        _ = assert(same != null)
        moved <- t.send(t.post("/auth/register", upgrade("jiro_2026"), cookie = cookie))
        twice <- t.send(t.post("/auth/register", upgrade("saburo_26"), cookie = t.cookie(moved, "__Host-ch3s_sid").get))
        twiceBody <- t.body(twice)
        anonymous <- t.send(t.post("/auth/register", upgrade("shiro_26")))
      } yield {
        assertEquals(moved.status, 200)
        assertEquals((twice.status, twiceBody.code.asInstanceOf[String]), (403, "auth:kind:forbidden"))
        assertEquals(anonymous.status, 401)
      }
    }
  }

  private def location(response: Response): URL = new URL(response.headers.get("Location"), "https://www.3ch.io")

  test("Google への移行は同じ aid を Lv10 の Google にし、別のアカウントに連携済みの Google アカウントでは移さない") {
    val t = new AuthSignInTestKit(google = true)
    def roundTrip(keys: GoogleTestKeys, sub: String, fields: Seq[(String, String)], cookie: String): Future[Response] = for {
      started <- t.send(t.post("/auth/google/start", fields, cookie = cookie))
      _ = assertEquals(started.status, 302)
      authorize = location(started)
      _ = { t.jwks = js.Dynamic.literal(keys = js.Array(keys.jwk)) }
      token <- keys.token(GoogleTestKeys.claims(sub, authorize.searchParams.get("nonce"), now))
      _ = { t.idToken = token }
      callback <- t.send(t.get(s"/auth/google/callback/?code=test-code&state=${authorize.searchParams.get("state")}",
        cookie = Seq(t.cookie(started, "__Host-ch3s_oauth").get, cookie).filter(_.nonEmpty).mkString("; ")))
    } yield callback
    t.run {
      for {
        keys <- GoogleTestKeys.generate("upgrade-kid")
        _ <- roundTrip(keys, "111111111111", Seq("uid" -> "hanako", "agreement" -> "v1"), "")
        (cookie, aid, _) <- client(t)
        linked <- roundTrip(keys, "111111111111", Seq("uid" -> "jiro_2026", "agreement" -> "v1", "purpose" -> "upgrade", "return" -> "/auth/account/"), cookie)
        _ = assertEquals(linked.headers.get("Location"), "/auth/?upgrade=1&error=google_linked&return=%2Fauth%2Faccount%2F")
        moved <- roundTrip(keys, "222222222222", Seq("uid" -> "jiro_2026", "agreement" -> "v1", "purpose" -> "upgrade", "return" -> "/auth/account/"), cookie)
        _ = assertEquals(moved.headers.get("Location"), "/auth/account/")
        session <- SessionCookies.verify(t.cookie(moved, "__Host-ch3s_sid").get, AuthTestEnv.secret, now)
        panel <- t.kit.account(aid).inspect().toFuture
        noSession <- t.send(t.post("/auth/google/start", Seq("uid" -> "saburo_26", "agreement" -> "v1", "purpose" -> "upgrade")))
      } yield {
        assertEquals(session.map(s => (s.aid, s.uid, s.lv, s.generation)), Some((aid, "jiro_2026", 10, 1L)))
        assertEquals(panel.account.kind.asInstanceOf[String], AccountKind.Google.code)
        assertEquals(noSession.headers.get("Location"), "/auth/?upgrade=1&error=session&return=%2F")
      }
    }
  }
}
