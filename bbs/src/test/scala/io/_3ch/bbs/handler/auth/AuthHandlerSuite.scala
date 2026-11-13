package io._3ch.bbs.handler.auth

import io._3ch.bbs.external.cloudflare.WorkerEnv
import io._3ch.bbs.handler.HandlerTestKit
import io._3ch.common.domain.account.SessionBinding
import scala.concurrent.Future
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

  private val register = Seq("id" -> "Taro_2026", "password" -> "correct horse", "password2" -> "correct horse", "agreement" -> "v1", "cf-turnstile-response" -> "accepted")
  private def login(id: String, password: String) = Seq("id" -> id, "password" -> password, "cf-turnstile-response" -> "accepted")

  test("ID・パスワードで登録し、ログアウトしてから同じ ID とパスワードでログインし直せる") {
    val t = new AuthSignInTestKit()
    t.run {
      for {
        registered <- t.send(t.post("/auth/register", register :+ ("return" -> "/sample/")))
        created <- t.body(registered)
        _ = assertEquals(registered.status, 200, js.JSON.stringify(created))
        _ = assertEquals(created.uid.asInstanceOf[String], "taro_2026")
        _ = assertEquals(created.lv.asInstanceOf[Int], 1)
        _ = assertEquals(created.returnPath.asInstanceOf[String], "/sample/")
        cookie = t.cookie(registered, "__Host-ch3s_sid").get
        session <- SessionCookies.verify(cookie, AuthTestEnv.secret, js.Date.now() / 1000)
        _ = assertEquals(session.map(_.uid), Some("taro_2026"))
        loginHash <- t.index("login", "taro_2026")
        uidHash <- t.index("uid", "taro_2026")
        byLogin <- t.kit.index("login").lookup(loginHash).toFuture
        byUid <- t.kit.index("uid").lookup(uidHash).toFuture
        _ = assertEquals(byLogin.aid.asInstanceOf[String], session.get.aid)
        _ = assertEquals(byUid.aid.asInstanceOf[String], session.get.aid)
        out <- t.send(t.post("/auth/logout", Nil, cookie = cookie))
        _ = assertEquals(out.status, 200)
        refused <- t.kit.account(session.get.aid).describeForPosting(session.get.aid, 0, math.floor(js.Date.now() / 1000)).toFuture
        _ = assertEquals(refused.code.asInstanceOf[String], "auth:session:invalid")
        again <- t.send(t.post("/auth/login", login("TARO_2026", "correct horse")))
        signedIn <- t.body(again)
        _ = assertEquals(again.status, 200, js.JSON.stringify(signedIn))
        _ = assertEquals(signedIn.uid.asInstanceOf[String], "taro_2026")
        next <- SessionCookies.verify(t.cookie(again, "__Host-ch3s_sid").get, AuthTestEnv.secret, js.Date.now() / 1000)
      } yield {
        assertEquals(next.map(c => (c.aid, c.generation)), Some((session.get.aid, 1L)))
        assertEquals(again.headers.get("Cache-Control"), "private, no-store")
        assert(again.headers.get("Content-Security-Policy").contains("frame-ancestors 'self'"))
      }
    }
  }

  test("存在しない ID・誤ったパスワード・ロック中は同じ状態と文言にし、どれも Account DO で照合する") {
    val t = new AuthSignInTestKit()
    t.run {
      for {
        _ <- t.send(t.post("/auth/register", register))
        unknown <- t.send(t.post("/auth/login", login("nobody_here", "correct horse")))
        unknownBody <- t.body(unknown)
        _ = assert(t.kit.accountNames.contains("dummy"))
        wrong <- t.send(t.post("/auth/login", login("taro_2026", "wrong password")))
        wrongBody <- t.body(wrong)
        key <- t.keyIp("192.0.2.10")
        aid = t.kit.accountNames.find(_ != "dummy").get
        _ <- Future.traverse((1 to 9).toVector)(_ => t.kit.account(aid).verifyPassword("wrong password", key, math.floor(js.Date.now() / 1000)).toFuture)
        locked <- t.send(t.post("/auth/login", login("taro_2026", "correct horse")))
        lockedBody <- t.body(locked)
        elsewhere <- t.send(t.post("/auth/login", login("taro_2026", "correct horse"), ip = "198.51.100.7"))
      } yield {
        Seq(unknown, wrong, locked).foreach(response => assertEquals(response.status, 401))
        Seq(unknownBody, wrongBody, lockedBody).foreach { value =>
          assertEquals(js.JSON.stringify(value), js.JSON.stringify(unknownBody))
          assertEquals(value.error.asInstanceOf[String], "ユーザーIDまたはパスワードが違います。")
        }
        assertEquals(locked.headers.get("Set-Cookie"), null)
        assertEquals(t.delays.toVector, Vector.empty[Double])
        assertEquals(elsewhere.status, 200)
      }
    }
  }

  test("失敗の遅延は接続元ごとの失敗数だけで決まり、ID が存在するかで変わらない") {
    val t = new AuthSignInTestKit()
    t.run {
      def failures(id: String, ip: String): Future[Vector[Double]] = {
        val before = t.delays.size
        (1 to 7).foldLeft(Future.unit)((previous, _) => previous.flatMap(_ => t.send(t.post("/auth/login", login(id, "wrong password"), ip = ip)).map(_ => ())))
          .map(_ => t.delays.toVector.drop(before))
      }
      for {
        _ <- t.send(t.post("/auth/register", register))
        unknown <- failures("nobody_here", "198.51.100.1")
        existing <- failures("taro_2026", "198.51.100.2")
      } yield {
        assertEquals(unknown, Vector(1d, 2d, 3d))
        assertEquals(existing, unknown)
      }
    }
  }

  test("専ブラのキーに結び付いたセッションは、キー発行・失効・同意の更新に使えず、ログアウトだけできる") {
    val t = new AuthSignInTestKit()
    t.run {
      val time = js.Date.now() / 1000
      for {
        registered <- t.send(t.post("/auth/register", register))
        session = t.cookie(registered, "__Host-ch3s_sid").get
        claims <- SessionCookies.verify(session, AuthTestEnv.secret, time)
        uaHash <- io._3ch.bbs.hash.AuthHmac.uaHash(AuthTestEnv.secret, "Monazilla/1.00 Test/1.0")
        issued <- SessionCookies.issue(claims.get.copy(binding = Some(SessionBinding("token1", uaHash.get.take(16)))), AuthTestEnv.secret, t.origin + "/auth/", time)
        bound = issued.get.takeWhile(_ != ';')
        send = (path: String, form: Seq[(String, String)]) => t.send(t.post(path, form, cookie = bound))
        issue <- send("/auth/token/issue", Nil)
        revoke <- send("/auth/token/revoke", Seq("id" -> "ABCDEFGHIJKLMNOP"))
        agree <- send("/auth/agree", Seq("agreement" -> "v1"))
        logout <- send("/auth/logout", Nil)
      } yield {
        Seq(issue, revoke, agree).foreach(response => assertEquals(response.status, 401))
        assert(logout.headers.get("Set-Cookie").startsWith("__Host-ch3s_sid=; Max-Age=0"))
      }
    }
  }

  test("ログインは接続元ごとに一時間三十回、登録は一日五件までで、計数できなければ拒否する") {
    val t = new AuthSignInTestKit()
    t.run {
      val now = math.floor(js.Date.now() / 1000)
      for {
        key <- t.keyIp("192.0.2.20")
        _ <- Future.traverse((1 to 30).toVector)(_ => t.kit.worker.AUTH_RATE_DO.get(t.kit.worker.AUTH_RATE_DO.idFromName(key)).hit("login", now).toFuture)
        limited <- t.send(t.post("/auth/login", login("taro_2026", "correct horse"), ip = "192.0.2.20"))
        _ = assertEquals(limited.status, 429)
        _ = assert(!t.kit.accountNames.contains("dummy"))
        _ <- Future.traverse((1 to 5).toVector)(_ => t.kit.worker.AUTH_RATE_DO.get(t.kit.worker.AUTH_RATE_DO.idFromName(key)).hit("register", now).toFuture)
        registerLimited <- t.send(t.post("/auth/register", register, ip = "192.0.2.20"))
        _ = assertEquals(registerLimited.status, 429)
        _ = t.env.asInstanceOf[js.Dynamic].AUTH_RATE_DO = js.undefined
        unavailable <- t.send(t.post("/auth/login", login("taro_2026", "correct horse"), ip = "192.0.2.21"))
        _ = assertEquals(unavailable.status, 503)
        noRegister <- t.send(t.post("/auth/register", register, ip = "192.0.2.21"))
      } yield {
        assertEquals(noRegister.status, 503)
        assertEquals(t.kit.accountNames, Set.empty[String])
      }
    }
  }

  test("Origin が無い・Turnstile に通らない登録とログインは DO に触れる前に拒否する") {
    val t = new AuthSignInTestKit()
    t.run {
      for {
        noOrigin <- t.send(t.post("/auth/register", register, requestOrigin = ""))
        _ = assertEquals(noOrigin.status, 403)
        foreign <- t.send(t.post("/auth/login", login("taro_2026", "correct horse"), requestOrigin = "https://evil.example"))
        _ = assertEquals(foreign.status, 403)
        rejected <- t.send(t.post("/auth/register", register.filterNot(_._1 == "cf-turnstile-response") :+ ("cf-turnstile-response" -> "rejected")))
        rejectedBody <- t.body(rejected)
        _ = assertEquals((rejected.status, rejectedBody.code.asInstanceOf[String]), (400, "auth:turnstile"))
        loginRejected <- t.send(t.post("/auth/login", Seq("id" -> "taro_2026", "password" -> "correct horse")))
        _ = assertEquals(loginRejected.status, 400)
        noAgreement <- t.send(t.post("/auth/register", register.filterNot(_._1 == "agreement")))
      } yield {
        assertEquals(noAgreement.status, 400)
        assertEquals(t.kit.accountNames, Set.empty[String])
        assertEquals(t.external.count(_.contains("siteverify")), 1)
      }
    }
  }

  test("使えない ID は予約語・板名・使用済みを同じ文言にし、確認の不一致と ID を含むパスワードは確認の前に拒否する") {
    val t = new AuthSignInTestKit()
    t.kit.putR2("sample/SETTING.TXT", "BBS_TITLE=sample")
    t.kit.putR2("reserved-ids.txt", "# 予約\nkanri\n")
    def attempt(id: String, password: String = "correct horse", confirm: String = "correct horse") =
      t.send(t.post("/auth/register", Seq("id" -> id, "password" -> password, "password2" -> confirm, "agreement" -> "v1", "cf-turnstile-response" -> "accepted"), ip = "192.0.2.30"))
    t.run {
      for {
        first <- attempt("taro_2026")
        _ = assertEquals(first.status, 200)
        taken <- Future.sequence(Vector("taro_2026", "admin", "sample", "kanri").map(id => attempt(id).flatMap(r => t.body(r).map(r.status -> _.error.asInstanceOf[String]))))
        _ = assertEquals(taken.distinct, Vector(409 -> "このユーザーIDは使えません。別のIDを入力してください。"))
        before = t.external.size
        mismatch <- attempt("jiro_2026", confirm = "correct horsf")
        mismatchBody <- t.body(mismatch)
        containing <- attempt("jiro_2026", password = "my-JIRO_2026", confirm = "my-JIRO_2026")
        containingBody <- t.body(containing)
        short <- attempt("jiro_2026", password = "short", confirm = "short")
        badId <- attempt("ji")
      } yield {
        assertEquals((mismatch.status, mismatchBody.code.asInstanceOf[String]), (400, "auth:password:confirmation"))
        assertEquals((containing.status, containingBody.error.asInstanceOf[String]), (400, "パスワードにユーザーIDを含めないでください。"))
        assertEquals(short.status, 400)
        assertEquals(badId.status, 400)
        assertEquals(t.external.size, before)
      }
    }
  }
}
