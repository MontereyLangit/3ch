package io._3ch.bbs.handler.auth

import io._3ch.bbs.application.auth.{AuthSettings, GoogleAuthentication, GoogleOutcome}
import io._3ch.bbs.external.cloudflare.WorkerEnv
import io._3ch.bbs.external.web.{Request, Response, URL}
import io._3ch.bbs.handler.Responses
import io._3ch.bbs.hash.{AuthHmac, JwtRs256}
import io._3ch.bbs.repository.AccountRepository
import io._3ch.common.domain.account.*
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.scalajs.js.URIUtils
import scala.util.control.NonFatal

/** Google の OpenID Connect（認可コード＋PKCE）でのログインと登録 */
object GoogleHandler {
  import AuthHandler.{Refused, header, secure}

  private val Attributes = "; Path=/; Secure; HttpOnly; SameSite=Lax"
  private val CallbackPath = "/auth/google/callback/"

  private def redirect(location: String, cookies: Seq[String]): Response = {
    val response = secure(Responses.response(null, 302, js.Dictionary("Location" -> location)))
    cookies.foreach(response.headers.append("Set-Cookie", _))
    response
  }

  /** 認証画面へ戻して理由を出す。login なら「ログイン」の表示にする */
  private def back(error: String, returnPath: String, login: Boolean, cookies: Seq[String] = Nil): Response =
    redirect(s"/auth/?${if (login) "mode=login&" else ""}error=$error&return=${URIUtils.encodeURIComponent(returnPath)}", cookies)

  private def clear(requestUrl: String): Seq[String] =
    Option.when(SessionCookies.canIssue(requestUrl))(s"${OAuthStateCodec.Name}=; Max-Age=0$Attributes").toSeq

  private def signature(secret: String, payload: String): Future[Option[String]] = AuthHmac.sessionSignature(secret, "oauth|" + payload)

  /** 同意・ユーザー ID・端末を確かめ、state・nonce・PKCE を署名付き Cookie に保存して Google へ送る */
  def start(request: Request, env: WorkerEnv, form: Map[String, String], config: AuthSettings, time: Double): Future[Response] =
    AuthEnv.google(env) match {
      case None => Future.failed(Refused(AuthApiError.NotFound))
      case Some(google) =>
        val returnPath = AuthHandler.safeReturn(form.getOrElse("return", "/"), request.url)
        val requested = form.getOrElse("uid", "")
        val login = requested.isEmpty
        val agreed = form.get("agreement").contains(config.agreementVersion)
        if (!login && !agreed) Future.successful(back("agreement", returnPath, login))
        else if (!SessionCookies.canIssue(request.url)) Future.failed(Refused(AuthApiError.Unavailable))
        else {
          val uid: Future[Either[LoginIdRejection, Option[LoginId]]] =
            if (login) Future.successful(Right(None)) else AuthHandler.available(env, requested).map(_.map(Some(_)))
          uid.flatMap {
            case Left(LoginIdRejection.Invalid) => Future.successful(back("uid", returnPath, login))
            case Left(LoginIdRejection.Reserved) => Future.successful(back("uid_taken", returnPath, login))
            case Right(id) => AuthHandler.device(request, env, form.getOrElse("device", "")).flatMap { evidence =>
              val state = OAuthState(AuthHmac.randomUrlSafe(43), AuthHmac.randomUrlSafe(43), AuthHmac.randomUrlSafe(64), evidence.deviceHash, returnPath,
                form.get("popup").contains("1"), id.map(_.value), if (agreed) config.agreementVersion else "", time.toLong + OAuthStateCodec.lifetime)
              val payload = OAuthStateCodec.payload(state).getOrElse(throw Refused(AuthApiError.Invalid))
              for {
                signed <- signature(config.secret, payload).map(_.flatMap(OAuthStateCodec.encode(state, _)).getOrElse(throw Refused(AuthApiError.Unavailable)))
                challenge <- JwtRs256.challenge(state.verifier)
              } yield {
                val query = Seq("client_id" -> google.clientId, "redirect_uri" -> (AuthHandler.origin(request.url) + CallbackPath), "response_type" -> "code",
                  "scope" -> "openid email", "state" -> state.state, "nonce" -> state.nonce, "code_challenge" -> challenge,
                  "code_challenge_method" -> "S256", "prompt" -> "select_account")
                  .map { case (key, value) => key + "=" + URIUtils.encodeURIComponent(value) }.mkString("&")
                redirect(GoogleIdTokens.AuthorizeUrl + "?" + query, Seq(s"${OAuthStateCodec.Name}=$signed; Max-Age=${OAuthStateCodec.lifetime}$Attributes"))
              }
            }
          }.recover {
            case Refused(AuthApiError.Device(_)) => back("device", returnPath, login)
            case NonFatal(_) => back("unavailable", returnPath, login)
          }
        }
    }

  private def sameText(a: String, b: String): Boolean =
    a.length == b.length && a.indices.foldLeft(0)((difference, i) => difference | (a.charAt(i) ^ b.charAt(i))) == 0

  private def readState(request: Request, secret: String, time: Double): Future[Option[OAuthState]] =
    OAuthStateCodec.fromHeader(header(request, "Cookie")).fold(Future.successful(Option.empty[OAuthState])) { signed =>
      AuthHmac.verifySession(secret, "oauth|" + signed.payload, signed.signature).map(valid => Option.when(valid && signed.state.isCurrent(time))(signed.state))
    }

  /** Cookie の state を一度だけ使い、ID トークンを検証してからログイン・登録する */
  def callback(request: Request, env: WorkerEnv, repository: AccountRepository, config: AuthSettings, time: Double): Future[Response] =
    AuthEnv.google(env) match {
      case None => Future.successful(AuthHandler.notFound(request))
      case Some(google) =>
        val url = new URL(request.url)
        def param(name: String): String = Option(url.searchParams.get(name)).getOrElse("")
        val cleared = clear(request.url)
        readState(request, config.secret, time).flatMap {
          case None => Future.successful(back("oauth_state", "/", login = true, cleared))
          case Some(state) if param("error").nonEmpty => Future.successful(back("google_denied", state.returnPath, state.uid.isEmpty, cleared))
          case Some(state) if state.uid.nonEmpty && state.agreedVersion != config.agreementVersion =>
            Future.successful(back("agreement", state.returnPath, login = false, cleared))
          case Some(state) if !sameText(param("state"), state.state) || param("code").isEmpty =>
            Future.successful(back("oauth_state", state.returnPath, state.uid.isEmpty, cleared))
          case Some(state) =>
            val login = state.uid.isEmpty
            val endpoints = new GoogleHttpEndpoints(google)
            (for {
              token <- endpoints.exchange(param("code"), state.verifier, AuthHandler.origin(request.url) + CallbackPath)
              identity <- token.fold(Future.successful(Option.empty[GoogleIdentity]))(GoogleIdTokens.verify(_, endpoints, time, state.nonce, google.clientId))
              response <- identity match {
                case None => Future.successful(back("google", state.returnPath, login, cleared))
                case Some(value) => AuthHandler.keyIp(request, config.secret).flatMap { key =>
                  val uid = state.uid.flatMap(LoginId.parse(_).toOption)
                  new GoogleAuthentication(repository, AuthHandler.keys(config.secret), config.agreementVersion)
                    .signIn(value, uid, state.agreedVersion == config.agreementVersion, state.device, key, time).flatMap {
                      case GoogleOutcome.Success(account) => AuthHandler.signed(account, state.device, request, config.secret, time).map { cookie =>
                        if (state.popup) popup(account, cleared :+ cookie) else redirect(state.returnPath, cleared :+ cookie)
                      }
                      case GoogleOutcome.Registration => Future.successful(back("google_register", state.returnPath, login = false, cleared))
                      case GoogleOutcome.Taken => Future.successful(back("uid_taken", state.returnPath, login = false, cleared))
                      case GoogleOutcome.Refused(_) => Future.successful(back("account", state.returnPath, login = true, cleared))
                    }
                }
              }
            } yield response).recover { case NonFatal(_) => back("unavailable", state.returnPath, login, cleared) }
        }
    }

  /** ポップアップで開いたときは開いた側と他のタブへ通知して閉じる */
  private def popup(account: AccountDescription, cookies: Seq[String]): Response = {
    val nonce = AuthHmac.randomUrlSafe(22)
    val message = js.JSON.stringify(js.Dynamic.literal(`type` = "ch3s-auth", status = "ok", uid = account.uid, lv = account.lv))
    val html = s"""<!doctype html><html lang="ja"><head><meta charset="utf-8"><title>ログインしました</title></head><body><p>ログインしました。このウィンドウを閉じてください。</p>""" +
      s"""<script nonce="$nonce">(()=>{const m=$message;try{if(window.opener)window.opener.postMessage(m,location.origin)}catch(e){}""" +
      """try{const c=new BroadcastChannel("ch3s-auth");c.postMessage(m);c.close()}catch(e){}window.close()})()</script></body></html>"""
    val response = secure(Responses.response(html, 200, js.Dictionary("Content-Type" -> "text/html;charset=UTF-8")))
    response.headers.set("Content-Security-Policy", s"default-src 'none'; script-src 'nonce-$nonce'; frame-ancestors 'none'")
    cookies.foreach(response.headers.append("Set-Cookie", _))
    response
  }
}
