package io._3ch.bbs.handler.auth

import io._3ch.bbs.external.cloudflare.WorkerEnv
import io._3ch.bbs.external.hono.HonoContextFacade
import io._3ch.bbs.external.web.Request
import io._3ch.bbs.hash.AuthHmac
import io._3ch.common.domain.account.{CookieCodec, SessionBinding, SessionClaims, SessionView}
import munit.FunSuite
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

class SessionReaderSuite extends FunSuite {
  private val secret = "test-session-secret"
  private val now = 2000000000d
  private val claims = SessionClaims("abcdefghijklmnop", "user_123", 2, 2000003600L, 3)
  private val https = "https://www.example.test/auth/"

  private def header(setCookie: String): String = setCookie.takeWhile(_ != ';')
  private def signed(value: SessionClaims): Future[String] = {
    val payload = CookieCodec.payload(value).get
    AuthHmac.sessionSignature(secret, payload).map(signature => s"${CookieCodec.Name}=$payload.${signature.get}")
  }

  test("署名と期限を検証した表示だけを返し、クライアント向け情報に aid を含めない") {
    for {
      cookie <- signed(claims.copy(device = "p0123456789abcdef01", binding = Some(SessionBinding("token", "0123456789abcdef"))))
      result <- SessionReader.read("PON=old; " + cookie, secret, now)
      _ = assertEquals(result, Some(SessionSnapshot(claims.aid, claims.uid, claims.lv)))
      _ = assertEquals(result.map(_.view), Some(SessionView("user_123", 3)))
    } yield ()
  }

  test("失効時刻ちょうど・改竄・異なる鍵・鍵の欠落は未認証") {
    for {
      cookie <- signed(claims)
      before <- SessionReader.read(cookie, secret, claims.expiresAt.toDouble - 0.1)
      expired <- SessionReader.read(cookie, secret, claims.expiresAt.toDouble)
      after <- SessionReader.read(cookie, secret, claims.expiresAt.toDouble + 1)
      changed <- SessionReader.read(cookie.replace("user_123", "other_12"), secret, now)
      otherKey <- SessionReader.read(cookie, "another-secret", now)
      noKey <- SessionReader.read(cookie, "", now)
      _ = assert(before.isDefined)
      _ = assertEquals(Seq(expired, after, changed, otherKey, noKey), Seq.fill(5)(None))
    } yield ()
  }

  test("Cookie の欠落・同名重複・不正な形式は未認証") {
    for {
      cookie <- signed(claims)
      results <- Future.traverse(Seq(null, "", "PON=value", cookie + "; " + cookie, cookie + ".extra"))(SessionReader.read(_, secret, now))
      _ = assert(results.forall(_.isEmpty))
    } yield ()
  }

  test("fromRequest は Account のバインディングが無くても表示情報を読める") {
    for {
      cookie <- signed(claims.copy(expiresAt = (js.Date.now() / 1000).toLong + 3600))
      request = js.Dynamic.literal(headers = js.Dynamic.literal(get = ((_: String) => cookie): js.Function1[String, String])).asInstanceOf[Request]
      env = js.Dynamic.literal(AUTH_SECRET = secret).asInstanceOf[WorkerEnv]
      result <- SessionReader.fromRequest(request, env)
      missing <- SessionReader.fromRequest(request, js.Dynamic.literal().asInstanceOf[WorkerEnv])
      context = js.Dynamic.literal(req = js.Dynamic.literal(header = ((_: String) => cookie): js.Function1[String, String]), env = env).asInstanceOf[HonoContextFacade]
      hono <- SessionReader.fromRequest(context)
      _ = assertEquals(hono, result)
      _ = assertEquals(result, Some(SessionSnapshot(claims.aid, claims.uid, claims.lv)))
      _ = assertEquals(missing, None)
    } yield ()
  }

  test("発行は host-only の Secure・HttpOnly・SameSite=Lax で有効期限までにする") {
    for {
      cookie <- SessionCookies.issue(claims, secret, https, now)
      _ = assert(cookie.exists(_.endsWith("; Max-Age=3600; Path=/; Secure; HttpOnly; SameSite=Lax")))
      _ = assert(!cookie.get.contains("Domain="))
      result <- SessionCookies.verify(header(cookie.get), secret, now)
      _ = assertEquals(result, Some(claims))
      _ = assertEquals(SessionCookies.clear(https), Some(s"${CookieCodec.Name}=; Max-Age=0; Path=/; Secure; HttpOnly; SameSite=Lax"))
    } yield ()
  }

  test("HTTP は localhost にだけ発行し、鍵なし・期限切れ・不正な claims は発行しない") {
    val urls = Seq("https://example.test/", "http://localhost:8787/", "http://example.test/", "http://127.0.0.1:8787/", "http://localhost.example.test/", "ftp://localhost/", "invalid")
    for {
      results <- Future.traverse(urls)(SessionCookies.issue(claims, secret, _, now))
      _ = assertEquals(results.map(_.isDefined), Seq(true, true, false, false, false, false, false))
      noKey <- SessionCookies.issue(claims, "", https, now)
      expired <- SessionCookies.issue(claims, secret, https, claims.expiresAt.toDouble)
      invalid <- SessionCookies.issue(claims.copy(uid = "bad-user"), secret, https, now)
      _ = assertEquals(Seq(noKey, expired, invalid), Seq(None, None, None))
      _ = assertEquals(SessionCookies.clear("http://example.test/"), None)
    } yield ()
  }
}
