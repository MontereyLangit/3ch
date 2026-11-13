package io._3ch.bbs.handler.auth

import io._3ch.common.domain.account.{AccountRole, Roles}
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*

class AccountMailHandlerSuite extends munit.FunSuite {
  private def register(id: String, email: String = "") =
    Seq("id" -> id, "password" -> "correct horse", "password2" -> "correct horse", "agreement" -> "v1", "cf-turnstile-response" -> "accepted") ++
      (if (email.nonEmpty) Seq("email" -> email) else Nil)
  private def now: Double = math.floor(js.Date.now() / 1000)

  test("登録のメール欄から確認メールを送り、リンクの確認でアドレスを確定させて Lv3 にする") {
    val t = new AuthSignInTestKit(mail = true)
    t.run {
      for {
        registered <- t.send(t.post("/auth/register", register("taro_2026", " Taro.Yamada+bbs@GoogleMail.com ")))
        body <- t.body(registered)
        _ = assertEquals((registered.status, body.lv.asInstanceOf[Int], body.mail.asInstanceOf[String]), (200, 1, "sent"))
        _ = assertEquals(t.outbox.map(_._1).toVector, Vector("taro.yamada+bbs@googlemail.com"))
        _ = assert(t.outbox.head._3.contains("https://www.3ch.io/auth/email/verify/?token="))
        cookie = t.cookie(registered, "__Host-ch3s_sid").get
        verified <- t.send(t.post("/auth/email/verify", Seq("token" -> t.mailedToken()), cookie = cookie))
        verifiedBody <- t.body(verified)
        _ = assertEquals((verified.status, verifiedBody.lv.asInstanceOf[Int]), (200, 3), js.JSON.stringify(verifiedBody))
        session <- SessionCookies.verify(t.cookie(verified, "__Host-ch3s_sid").get, AuthTestEnv.secret, now)
        _ = assertEquals(session.map(_.lv), Some(3))
        again <- t.send(t.post("/auth/email/verify", Seq("token" -> t.mailedToken())))
        againBody <- t.body(again)
        _ = assertEquals((again.status, againBody.code.asInstanceOf[String]), (400, "auth:pending:invalid"))
        emailHash <- t.index("email", "taroyamada@gmail.com")
        owner <- t.kit.index("email").lookup(emailHash).toFuture
        other <- t.send(t.post("/auth/register", register("jiro_2026", "taroyamada@gmail.com"), ip = "192.0.2.40"))
        otherBody <- t.body(other)
      } yield {
        assertEquals(owner.aid.asInstanceOf[String], session.get.aid)
        assertEquals((other.status, otherBody.code.asInstanceOf[String]), (409, "auth:email:taken"))
        assertEquals(t.outbox.size, 1)
      }
    }
  }

  test("使い捨てのドメインと書式の誤りはメール欄だけの誤りにし、送信先が無ければ準備中にする") {
    val t = new AuthSignInTestKit(mail = true)
    t.kit.putR2("disposable-domains.txt", "# 使い捨て\nmailinator.com\n")
    val disabled = new AuthSignInTestKit()
    t.run {
      for {
        disposable <- t.send(t.post("/auth/register", register("taro_2026", "x@sub.mailinator.com")))
        disposableBody <- t.body(disposable)
        invalid <- t.send(t.post("/auth/register", register("taro_2026", "no-at-mark")))
        invalidBody <- t.body(invalid)
        off <- disabled.run(disabled.send(disabled.post("/auth/register", register("taro_2026", "taro@example.com"))))
        offBody <- disabled.body(off)
        offPage <- disabled.run(disabled.send(disabled.post("/auth/email", Seq("email" -> "taro@example.com"))))
      } yield {
        assertEquals((disposable.status, disposableBody.code.asInstanceOf[String]), (400, "auth:email:disposable"))
        assertEquals((invalid.status, invalidBody.code.asInstanceOf[String]), (400, "auth:email:invalid"))
        assertEquals((off.status, offBody.code.asInstanceOf[String]), (503, "auth:mail:unavailable"))
        assertEquals(offPage.status, 503)
        assertEquals(t.kit.accountNames, Set.empty[String])
        assertEquals(t.outbox.size, 0)
      }
    }
  }

  test("送信に失敗しても登録は成功のままにし、ログイン後のメール欄から送り直せる（一日三回まで）") {
    val t = new AuthSignInTestKit(mail = true)
    t.mailFails = true
    t.run {
      for {
        registered <- t.send(t.post("/auth/register", register("taro_2026", "taro@example.com")))
        body <- t.body(registered)
        _ = assertEquals((registered.status, body.mail.asInstanceOf[String]), (200, "failed"))
        _ = t.mailFails = false
        cookie = t.cookie(registered, "__Host-ch3s_sid").get
        sends <- (1 to 3).foldLeft(Future.successful(Vector.empty[Int])) { (done, _) =>
          done.flatMap(list => t.send(t.post("/auth/email", Seq("email" -> "taro@example.com", "current" -> "correct horse"), cookie = cookie)).map(r => list :+ r.status))
        }
      } yield {
        assertEquals(sends, Vector(200, 200, 429))
        assertEquals(t.outbox.size, 2)
      }
    }
  }

  test("アドレスの変更は確認まで前のアドレスを残し、確認後に前の索引を外して Lv は下げない") {
    val t = new AuthSignInTestKit(mail = true)
    t.run {
      for {
        registered <- t.send(t.post("/auth/register", register("taro_2026", "old@example.com")))
        cookie = t.cookie(registered, "__Host-ch3s_sid").get
        _ <- t.send(t.post("/auth/email/verify", Seq("token" -> t.mailedToken())))
        changed <- t.send(t.post("/auth/email", Seq("email" -> "new@example.com", "current" -> "correct horse"), cookie = cookie))
        _ = assertEquals(changed.status, 200)
        aid = t.kit.accountNames.head
        panel <- t.kit.account(aid).inspect().toFuture
        _ = assertEquals((panel.email.asInstanceOf[String], panel.pendingEmail.asInstanceOf[String]), ("old@example.com", "new@example.com"))
        verified <- t.send(t.post("/auth/email/verify", Seq("token" -> t.mailedToken())))
        verifiedBody <- t.body(verified)
        oldHash <- t.index("email", "old@example.com")
        newHash <- t.index("email", "new@example.com")
        oldOwner <- t.kit.index("email").lookup(oldHash).toFuture
        newOwner <- t.kit.index("email").lookup(newHash).toFuture
      } yield {
        assertEquals(verifiedBody.lv.asInstanceOf[Int], 3)
        assertEquals(oldOwner.code.asInstanceOf[String], "auth:index:missing")
        assertEquals(newOwner.aid.asInstanceOf[String], aid)
      }
    }
  }

  test("再設定は結果を区別せずに返し、メール認証済みで役割の無いアカウントにだけ送る") {
    val t = new AuthSignInTestKit(mail = true)
    def request(id: String, ip: String = "192.0.2.50") = t.send(t.post("/auth/reset/request", Seq("id" -> id, "cf-turnstile-response" -> "accepted"), ip = ip))
    t.run {
      for {
        registered <- t.send(t.post("/auth/register", register("taro_2026", "taro@example.com")))
        unverified <- request("taro_2026")
        _ = assertEquals(t.outbox.size, 1)
        _ <- t.send(t.post("/auth/email/verify", Seq("token" -> t.mailedToken())))
        unknown <- request("nobody_here")
        sent <- request("taro_2026", ip = "192.0.2.51")
        bodies <- Future.traverse(Vector(unverified, unknown, sent))(t.body)
        _ = assertEquals(bodies.map(js.JSON.stringify(_)).distinct, Vector("""{"ok":true}"""))
        _ = assertEquals(t.outbox.size, 2)
        _ = assert(t.outbox.last._3.contains("/auth/reset/?token="))
        token = t.mailedToken()
        mismatch <- t.send(t.post("/auth/reset", Seq("token" -> token, "password" -> "brand new pass", "password2" -> "brand new pasz")))
        _ = assertEquals(mismatch.status, 400)
        reset <- t.send(t.post("/auth/reset", Seq("token" -> token, "password" -> "brand new pass", "password2" -> "brand new pass")))
        _ = assertEquals(reset.status, 200)
        reused <- t.send(t.post("/auth/reset", Seq("token" -> token, "password" -> "another pass1", "password2" -> "another pass1")))
        old <- t.send(t.post("/auth/login", Seq("id" -> "taro_2026", "password" -> "correct horse", "cf-turnstile-response" -> "accepted"), ip = "192.0.2.52"))
        fresh <- t.send(t.post("/auth/login", Seq("id" -> "taro_2026", "password" -> "brand new pass", "cf-turnstile-response" -> "accepted"), ip = "192.0.2.52"))
        stale <- t.send(t.get("/auth/session", cookie = t.cookie(registered, "__Host-ch3s_sid").get))
        staleBody <- t.body(stale)
      } yield {
        assertEquals(reused.status, 400)
        assertEquals(old.status, 401)
        assertEquals(fresh.status, 200)
        assertEquals(staleBody, null)
      }
    }
  }

  test("役割のあるアカウントには再設定のメールを送らず、申請は接続元ごとに一日三回まで") {
    val t = new AuthSignInTestKit(mail = true)
    def request(id: String) = t.send(t.post("/auth/reset/request", Seq("id" -> id, "cf-turnstile-response" -> "accepted"), ip = "192.0.2.60"))
    t.run {
      for {
        _ <- t.send(t.post("/auth/register", register("taro_2026", "taro@example.com")))
        _ <- t.send(t.post("/auth/email/verify", Seq("token" -> t.mailedToken())))
        aid = t.kit.accountNames.head
        actor <- t.admin()
        _ <- t.kit.account(aid).setRoles(js.Array(AccountRole.Delete.code), actor, now).toFuture
        statuses <- (1 to 4).foldLeft(Future.successful(Vector.empty[Int]))((done, _) => done.flatMap(list => request("taro_2026").map(r => list :+ r.status)))
      } yield {
        assertEquals(statuses, Vector(200, 200, 200, 429))
        assertEquals(t.outbox.size, 1)
      }
    }
  }

  test("パスワードの変更は現在のパスワードを確かめ、この端末にだけ新しい Cookie を出して役割があれば16文字を求める") {
    val t = new AuthSignInTestKit()
    t.run {
      for {
        registered <- t.send(t.post("/auth/register", register("taro_2026")))
        cookie = t.cookie(registered, "__Host-ch3s_sid").get
        wrong <- t.send(t.post("/auth/password", Seq("current" -> "wrong password", "password" -> "brand new pass", "password2" -> "brand new pass"), cookie = cookie))
        _ = assertEquals(wrong.status, 401)
        changed <- t.send(t.post("/auth/password", Seq("current" -> "correct horse", "password" -> "brand new pass", "password2" -> "brand new pass"), cookie = cookie))
        _ = assertEquals(changed.status, 200)
        next = t.cookie(changed, "__Host-ch3s_sid").get
        old <- t.send(t.get("/auth/session", cookie = cookie)).flatMap(t.body)
        current <- t.send(t.get("/auth/session", cookie = next)).flatMap(t.body)
        aid = t.kit.accountNames.find(_ != "dummy").get
        actor <- t.admin()
        _ <- t.kit.account(aid).setRoles(js.Array(AccountRole.Delete.code), actor, now).toFuture
        short <- t.send(t.post("/auth/password", Seq("current" -> "brand new pass", "password" -> "fifteen chars!!", "password2" -> "fifteen chars!!"), cookie = next))
        shortBody <- t.body(short)
      } yield {
        assertEquals(old, null)
        assertEquals(current.uid.asInstanceOf[String], "taro_2026")
        assertEquals((short.status, shortBody.error.asInstanceOf[String]), (400, "権限のあるアカウントのパスワードは16文字以上にしてください。"))
      }
    }
  }

  test("メールの登録・変更は現在のパスワードを確かめ、無い・違うパスワードでは確認メールを送らない") {
    val t = new AuthSignInTestKit(mail = true)
    t.run {
      for {
        registered <- t.send(t.post("/auth/register", register("taro_2026")))
        cookie = t.cookie(registered, "__Host-ch3s_sid").get
        missing <- t.send(t.post("/auth/email", Seq("email" -> "taro@example.com"), cookie = cookie))
        wrong <- t.send(t.post("/auth/email", Seq("email" -> "taro@example.com", "current" -> "wrong password"), cookie = cookie))
        _ = assertEquals((missing.status, wrong.status, t.outbox.size), (401, 401, 0))
        sent <- t.send(t.post("/auth/email", Seq("email" -> "taro@example.com", "current" -> "correct horse"), cookie = cookie))
      } yield assertEquals((sent.status, t.outbox.size), (200, 1))
    }
  }

  test("パスワードの変更は、それ以前に出した確認と再設定のリンクを使えなくする") {
    val t = new AuthSignInTestKit(mail = true)
    t.run {
      for {
        registered <- t.send(t.post("/auth/register", register("taro_2026", "taro@example.com")))
        cookie = t.cookie(registered, "__Host-ch3s_sid").get
        _ <- t.send(t.post("/auth/email/verify", Seq("token" -> t.mailedToken())))
        _ <- t.send(t.post("/auth/email", Seq("email" -> "new@example.com", "current" -> "correct horse"), cookie = cookie))
        change = t.mailedToken()
        _ <- t.send(t.post("/auth/reset/request", Seq("id" -> "taro_2026", "cf-turnstile-response" -> "accepted"), ip = "192.0.2.70"))
        reset = t.mailedToken()
        changed <- t.send(t.post("/auth/password", Seq("current" -> "correct horse", "password" -> "brand new pass", "password2" -> "brand new pass"), cookie = cookie))
        _ = assertEquals(changed.status, 200)
        staleChange <- t.send(t.post("/auth/email/verify", Seq("token" -> change)))
        staleReset <- t.send(t.post("/auth/reset", Seq("token" -> reset, "password" -> "another pass1", "password2" -> "another pass1")))
      } yield assertEquals((staleChange.status, staleReset.status), (400, 400))
    }
  }

  test("メールの確認は、それ以前に出した再設定のリンクも捨てる") {
    val t = new AuthSignInTestKit(mail = true)
    t.run {
      for {
        registered <- t.send(t.post("/auth/register", register("taro_2026", "taro@example.com")))
        cookie = t.cookie(registered, "__Host-ch3s_sid").get
        _ <- t.send(t.post("/auth/email/verify", Seq("token" -> t.mailedToken())))
        _ <- t.send(t.post("/auth/email", Seq("email" -> "new@example.com", "current" -> "correct horse"), cookie = cookie))
        change = t.mailedToken()
        _ <- t.send(t.post("/auth/reset/request", Seq("id" -> "taro_2026", "cf-turnstile-response" -> "accepted"), ip = "192.0.2.71"))
        reset = t.mailedToken()
        verified <- t.send(t.post("/auth/email/verify", Seq("token" -> change)))
        stale <- t.send(t.post("/auth/reset", Seq("token" -> reset, "password" -> "another pass1", "password2" -> "another pass1")))
      } yield assertEquals((verified.status, stale.status), (200, 400))
    }
  }

  test("パスワードの再設定は専ブラキーを全て無効にし、通常のパスワード変更は残す") {
    val t = new AuthSignInTestKit(mail = true)
    t.run {
      for {
        registered <- t.send(t.post("/auth/register", register("taro_2026", "taro@example.com")))
        cookie = t.cookie(registered, "__Host-ch3s_sid").get
        _ <- t.send(t.post("/auth/email/verify", Seq("token" -> t.mailedToken())))
        _ <- t.send(t.post("/auth/token/issue", Nil, cookie = cookie))
        aid = t.kit.accountNames.find(_ != "dummy").get
        changed <- t.send(t.post("/auth/password", Seq("current" -> "correct horse", "password" -> "brand new pass", "password2" -> "brand new pass"), cookie = cookie))
        afterChange <- t.kit.account(aid).inspect().toFuture
        _ <- t.send(t.post("/auth/reset/request", Seq("id" -> "taro_2026", "cf-turnstile-response" -> "accepted"), ip = "192.0.2.72"))
        reset <- t.send(t.post("/auth/reset", Seq("token" -> t.mailedToken(), "password" -> "another pass1", "password2" -> "another pass1")))
        afterReset <- t.kit.account(aid).inspect().toFuture
      } yield {
        assertEquals((changed.status, reset.status), (200, 200))
        def revoked(panel: js.Dynamic) = panel.tokens.asInstanceOf[js.Array[js.Dynamic]].toVector.map(token => !js.isUndefined(token.revokedAt))
        assertEquals(revoked(afterChange), Vector(false))
        assertEquals(revoked(afterReset), Vector(true))
      }
    }
  }

  test("確認画面に出す対象はリンクの持ち主のユーザー ID と確定させるアドレスで、再設定のリンクはユーザー ID だけ") {
    val t = new AuthSignInTestKit(mail = true)
    val repository = new io._3ch.bbs.repository.AccountRepository(t.env)
    t.run {
      for {
        _ <- t.send(t.post("/auth/register", register("taro_2026", "taro@example.com")))
        link = io._3ch.common.domain.account.PendingLink.parse(t.mailedToken()).get
        service = AccountMailHandler.service(t.env, repository, AuthEnv.settings(t.env).get).get
        target <- service.emailTarget(link, now)
        gone <- service.emailTarget(link.copy(secret = "x" * 43), now).failed
      } yield {
        assertEquals((target.uid, target.email.map(io._3ch.common.domain.account.EmailAddress.mask)), ("taro_2026", Some("t***@example.com")))
        assert(gone.isInstanceOf[io._3ch.bbs.application.auth.ports.AccountStoreException])
      }
    }
  }
}
