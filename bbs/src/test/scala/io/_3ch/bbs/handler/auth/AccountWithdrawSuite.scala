package io._3ch.bbs.handler.auth

import io._3ch.bbs.application.posting.CapKey
import io._3ch.bbs.external.web.{Response, URL}
import io._3ch.bbs.hash.WebHasher
import io._3ch.bbs.repository.AccountRepository
import io._3ch.common.domain.account.{AccountRole, AccountStatus, Roles}
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

class AccountWithdrawSuite extends munit.FunSuite {
  private def now: Double = math.floor(js.Date.now() / 1000)
  private val register = Seq("id" -> "taro_2026", "password" -> "correct horse", "password2" -> "correct horse", "agreement" -> "v1",
    "cf-turnstile-response" -> "accepted", "email" -> "taro@example.com")

  test("ID・パスワードの退会はパスワードを確かめ、login・email 索引とキャップを外して uid の索引と記録を残す") {
    val t = new AuthSignInTestKit(mail = true)
    t.run {
      for {
        registered <- t.send(t.post("/auth/register", register))
        cookie = t.cookie(registered, "__Host-ch3s_sid").get
        _ <- t.send(t.post("/auth/email/verify", Seq("token" -> t.mailedToken())))
        aid = t.kit.accountNames.head
        _ <- new AccountRepository(t.env).setRoles(aid, Roles(Set(AccountRole.Cap)), Roles(Set(AccountRole.Admin)), now)
        issued <- t.send(t.post("/auth/cap/issue", Seq("name" -> "管理人"), cookie = cookie)).flatMap(t.body)
        capKey <- CapKey.of(WebHasher, issued.password.asInstanceOf[String].stripPrefix("#"), WebHasher.md5(String.valueOf(t.env.salt)))
        _ = assert(t.kit.capValue(capKey).isDefined)
        noAction <- t.send(t.post("/auth/account", Seq("password" -> "correct horse"), cookie = cookie))
        _ = assertEquals(noAction.status, 400)
        wrong <- t.send(t.post("/auth/account", Seq("action" -> "delete", "password" -> "wrong password"), cookie = cookie))
        _ = assertEquals(wrong.status, 401)
        deleted <- t.send(t.post("/auth/account", Seq("action" -> "delete", "password" -> "correct horse"), cookie = cookie))
        _ = assertEquals(deleted.status, 200)
        _ = assert(deleted.headers.get("Set-Cookie").startsWith("__Host-ch3s_sid=; Max-Age=0"))
        lookups <- Future.traverse(Vector("uid" -> "taro_2026", "login" -> "taro_2026", "email" -> "taro@example.com")) { case (kind, value) =>
          t.index(kind, value).flatMap(hash => t.kit.index(kind).lookup(hash).toFuture).map(v => (v.code.asInstanceOf[String], v.aid.asInstanceOf[js.UndefOr[String]].toOption))
        }
        capHash <- t.index("cap", capKey)
        capOwner <- t.kit.index("cap").lookup(capHash).toFuture
        session <- t.send(t.get("/auth/session", cookie = cookie)).flatMap(t.body)
        login <- t.send(t.post("/auth/login", Seq("id" -> "taro_2026", "password" -> "correct horse", "cf-turnstile-response" -> "accepted"), ip = "192.0.2.90"))
        again <- t.send(t.post("/auth/register", register.filterNot(_._1 == "email"), ip = "192.0.2.91"))
      } yield {
        assertEquals(lookups, Vector(("", Some(aid)), ("auth:index:missing", None), ("auth:index:missing", None)))
        assertEquals(capOwner.code.asInstanceOf[String], "auth:index:missing")
        assertEquals(t.kit.capValue(capKey), None)
        assertEquals(session, null)
        assertEquals(login.status, 401)
        assertEquals(again.status, 409)
      }
    }
  }

  test("かんたん登録は確認の印で退会し、退会後のセッションは使えない") {
    val t = new AuthSignInTestKit()
    t.run {
      for {
        verified <- t.send(t.post("/auth/verify", Seq("agreement" -> "v1", "cf-turnstile-response" -> "accepted")))
        cookie = t.cookie(verified, "__Host-ch3s_sid").get
        unconfirmed <- t.send(t.post("/auth/account", Seq("action" -> "delete"), cookie = cookie))
        deleted <- t.send(t.post("/auth/account", Seq("action" -> "delete", "confirm" -> "1"), cookie = cookie))
        aid = t.kit.accountNames.head
        described <- t.kit.account(aid).describeForPosting(aid, 1, now).toFuture
        anonymous <- t.send(t.post("/auth/account", Seq("action" -> "delete", "confirm" -> "1")))
      } yield {
        assertEquals((unconfirmed.status, deleted.status, anonymous.status), (400, 200, 401))
        assertEquals(described.code.asInstanceOf[String], "auth:deleted")
      }
    }
  }

  private def location(response: Response): URL = new URL(response.headers.get("Location"), "https://www.3ch.io")

  test("Google の退会は同じ Google アカウントの再認証が要り、違うアカウントでは退会しない") {
    val t = new AuthSignInTestKit(google = true)
    def roundTrip(keys: GoogleTestKeys, sub: String, fields: Seq[(String, String)], cookie: String = ""): Future[Response] = for {
      started <- t.send(t.post("/auth/google/start", fields, cookie = cookie))
      authorize = location(started)
      _ = { t.jwks = js.Dynamic.literal(keys = js.Array(keys.jwk)) }
      token <- keys.token(GoogleTestKeys.claims(sub, authorize.searchParams.get("nonce"), now))
      _ = { t.idToken = token }
      callback <- t.send(t.get(s"/auth/google/callback/?code=c&state=${authorize.searchParams.get("state")}",
        cookie = Seq(t.cookie(started, "ch3s_oauth").get, cookie).filter(_.nonEmpty).mkString("; ")))
    } yield callback
    t.run {
      for {
        keys <- GoogleTestKeys.generate("withdraw-kid")
        created <- roundTrip(keys, "111111111111", Seq("uid" -> "hanako", "agreement" -> "v1"))
        cookie = t.cookie(created, "__Host-ch3s_sid").get
        aid = t.kit.accountNames.head
        password <- t.send(t.post("/auth/account", Seq("action" -> "delete", "confirm" -> "1"), cookie = cookie))
        _ = assertEquals(password.status, 403)
        mismatch <- roundTrip(keys, "999999999999", Seq("purpose" -> "delete"), cookie)
        _ = assertEquals(mismatch.headers.get("Location"), "/auth/account/?error=google_mismatch")
        deleted <- roundTrip(keys, "111111111111", Seq("purpose" -> "delete"), cookie)
        googleHash <- t.index("google", "111111111111")
        owner <- t.kit.index("google").lookup(googleHash).toFuture
        described <- t.kit.account(aid).describeForPosting(aid, 1, now).toFuture
      } yield {
        assertEquals(deleted.headers.get("Location"), "/")
        assertEquals(t.cookie(deleted, "__Host-ch3s_sid"), Some("__Host-ch3s_sid="))
        assertEquals(owner.code.asInstanceOf[String], "auth:index:missing")
        assertEquals(described.code.asInstanceOf[String], "auth:deleted")
      }
    }
  }
}
