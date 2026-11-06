package io._3ch.bbs.handler.auth

import io._3ch.bbs.external.web.{Response, URL}
import io._3ch.common.domain.account.AccountKind
import munit.FunSuite
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

class GoogleHandlerSuite extends FunSuite {
  private def location(response: Response): URL = new URL(response.headers.get("Location"), "https://www.3ch.io")

  /** 開始から callback までを通し、callback の応答を返す */
  private def roundTrip(t: AuthSignInTestKit, keys: GoogleTestKeys, sub: String, fields: Seq[(String, String)],
    nonce: Option[String] = None, state: Option[String] = None): Future[Response] = for {
    started <- t.send(t.post("/auth/google/start", fields))
    _ = assertEquals(started.status, 302)
    authorize = location(started)
    oauth = t.cookie(started, "ch3s_oauth").get
    _ = { t.jwks = js.Dynamic.literal(keys = js.Array(keys.jwk)) }
    token <- keys.token(GoogleTestKeys.claims(sub, nonce.getOrElse(authorize.searchParams.get("nonce")), math.floor(js.Date.now() / 1000)))
    _ = { t.idToken = token }
    callback <- t.send(t.get(s"/auth/google/callback/?code=test-code&state=${state.getOrElse(authorize.searchParams.get("state"))}", cookie = oauth))
  } yield callback

  test("開始は state・nonce・PKCE を署名付き Cookie に保存して Google へ送る") {
    val t = new AuthSignInTestKit(google = true)
    t.run {
      t.send(t.post("/auth/google/start", Seq("uid" -> "hanako", "agreement" -> "v1", "return" -> "/sample/"))).map { response =>
        val url = location(response)
        assertEquals(url.origin + url.pathname, "https://accounts.google.com/o/oauth2/v2/auth")
        Seq("client_id" -> "test-client.apps.googleusercontent.com", "redirect_uri" -> "https://www.3ch.io/auth/google/callback/",
          "response_type" -> "code", "scope" -> "openid email", "code_challenge_method" -> "S256", "prompt" -> "select_account").foreach {
          case (name, value) => assertEquals(url.searchParams.get(name), value, name)
        }
        assert(url.searchParams.get("code_challenge").matches("[A-Za-z0-9_-]{43}"))
        val cookie = response.headers.get("Set-Cookie")
        assert(cookie.startsWith("ch3s_oauth=2|"), cookie)
        assert(cookie.contains("; Max-Age=600; Path=/auth/google; Secure; HttpOnly; SameSite=Lax"))
        assert(cookie.contains(url.searchParams.get("state")))
        assert(!cookie.contains(url.searchParams.get("code_challenge")))
      }
    }
  }

  test("未登録の Google アカウントは入力したユーザー ID で Lv10 のアカウントを作り、次からはユーザー ID なしでログインする") {
    val t = new AuthSignInTestKit(google = true)
    t.run {
      for {
        keys <- GoogleTestKeys.generate("handler-kid-1")
        created <- roundTrip(t, keys, "109876543210", Seq("uid" -> "Hanako", "agreement" -> "v1", "return" -> "/sample/"))
        _ = assertEquals(created.status, 302)
        _ = assertEquals(created.headers.get("Location"), "/sample/")
        session <- SessionCookies.verify(t.cookie(created, "__Host-ch3s_sid").get, AuthTestEnv.secret, js.Date.now() / 1000)
        _ = assertEquals(session.map(s => (s.uid, s.lv)), Some(("hanako", 10)))
        _ = assertEquals(t.cookie(created, "ch3s_oauth"), Some("ch3s_oauth="))
        panel <- t.kit.account(session.get.aid).inspect().toFuture
        _ = assertEquals(panel.account.kind.asInstanceOf[String], AccountKind.Google.code)
        again <- roundTrip(t, keys, "109876543210", Seq("return" -> "/"))
        next <- SessionCookies.verify(t.cookie(again, "__Host-ch3s_sid").get, AuthTestEnv.secret, js.Date.now() / 1000)
        _ = assertEquals(next.map(_.aid), session.map(_.aid))
        taken <- roundTrip(t, keys, "200000000000", Seq("uid" -> "hanako", "agreement" -> "v1"))
        _ = assertEquals(taken.headers.get("Location"), "/auth/?error=uid_taken&return=%2F")
        unregistered <- roundTrip(t, keys, "300000000000", Seq("return" -> "/"))
      } yield assertEquals(unregistered.headers.get("Location"), "/auth/?error=google_register&return=%2F")
    }
  }

  test("state・nonce・Cookie が合わない callback はアカウントに触れずに戻す") {
    val t = new AuthSignInTestKit(google = true)
    t.run {
      for {
        keys <- GoogleTestKeys.generate("handler-kid-2")
        wrongState <- roundTrip(t, keys, "109876543210", Seq("uid" -> "hanako", "agreement" -> "v1"), state = Some("A" * 43))
        _ = assertEquals(wrongState.headers.get("Location"), "/auth/?error=oauth_state&return=%2F")
        wrongNonce <- roundTrip(t, keys, "109876543210", Seq("uid" -> "hanako", "agreement" -> "v1"), nonce = Some("B" * 43))
        _ = assertEquals(wrongNonce.headers.get("Location"), "/auth/?error=google&return=%2F")
        noCookie <- t.send(t.get("/auth/google/callback/?code=x&state=y"))
        _ = assertEquals(noCookie.headers.get("Location"), "/auth/?mode=login&error=oauth_state&return=%2F")
        denied <- t.send(t.post("/auth/google/start", Seq("return" -> "/"))).flatMap { started =>
          t.send(t.get("/auth/google/callback/?error=access_denied", cookie = t.cookie(started, "ch3s_oauth").get))
        }
        _ = assertEquals(denied.headers.get("Location"), "/auth/?mode=login&error=google_denied&return=%2F")
        noAgreement <- t.send(t.post("/auth/google/start", Seq("uid" -> "hanako")))
        _ = assertEquals(noAgreement.headers.get("Location"), "/auth/?error=agreement&return=%2F")
        badUid <- t.send(t.post("/auth/google/start", Seq("uid" -> "x", "agreement" -> "v1")))
      } yield {
        assertEquals(badUid.headers.get("Location"), "/auth/?error=uid&return=%2F")
        assertEquals(t.kit.accountNames, Set.empty[String])
      }
    }
  }

  test("ポップアップでは開いた側へ通知して閉じる画面を返す") {
    val t = new AuthSignInTestKit(google = true)
    t.run {
      for {
        keys <- GoogleTestKeys.generate("handler-kid-3")
        response <- roundTrip(t, keys, "109876543210", Seq("uid" -> "hanako", "agreement" -> "v1", "popup" -> "1"))
        html <- response.text().toFuture
      } yield {
        assertEquals(response.status, 200)
        val csp = response.headers.get("Content-Security-Policy")
        val nonce = "script-src 'nonce-([A-Za-z0-9_-]+)'".r.findFirstMatchIn(csp).get.group(1)
        assert(html.contains(s"""<script nonce="$nonce">"""))
        assert(html.contains(""""type":"ch3s-auth","status":"ok","uid":"hanako","lv":10"""))
        assert(html.contains("BroadcastChannel(\"ch3s-auth\")"))
        assert(t.cookie(response, "__Host-ch3s_sid").isDefined)
      }
    }
  }

  test("クライアントが未設定なら Google の開始も callback も扱わない") {
    val t = new AuthSignInTestKit()
    t.run {
      for {
        start <- t.send(t.post("/auth/google/start", Seq("uid" -> "hanako", "agreement" -> "v1")))
        callback <- t.send(t.get("/auth/google/callback/?code=x&state=y"))
      } yield {
        assertEquals(start.status, 404)
        assertEquals(callback.status, 404)
        assert(t.external.isEmpty)
      }
    }
  }
}
