package io._3ch.bbs.handler.auth

import io._3ch.bbs.application.auth.{AuthServiceException, AuthServiceFailure, AuthSettings, ClientAuthentication, DeviceEvidence, TokenIssuing}
import io._3ch.bbs.application.auth.ports.{AuthenticationKeys, TokenProof}
import io._3ch.bbs.components.{AccountComponent, AccountProps, AccountTokenProps, AccountPostProps, AccountTokenWarningProps, AuthComponent, AuthProps}
import io._3ch.bbs.external.cloudflare.WorkerEnv
import io._3ch.bbs.external.hono.{HonoContextFacade, RenderProps}
import io._3ch.bbs.external.web.{Request, Response, TextDecoder, URL}
import io._3ch.bbs.handler.{PublicText, Responses}
import io._3ch.bbs.hash.AuthHmac
import io._3ch.bbs.repository.AccountRepository
import io._3ch.common.domain.account.*
import io._3ch.common.domain.device.{ClientHints, DeviceHashRef, DeviceRejection, IosDeviceProfile}
import io._3ch.bbs.domain.posting.KeyIp
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import scala.util.Try
import scala.util.control.NonFatal

/** 認証 API の拒否理由。code は応答の JSON に載せる識別子 */
enum AuthApiError {
  case NotFound
  case Method
  case Origin
  case Invalid
  case LoginRequired
  case Session(reason: AccountRejection)
  case AgreementRequired
  case Turnstile
  case Device(reason: DeviceRejection)
  case Service(failure: AuthServiceFailure)
  case Unavailable

  def code: String = this match {
    case NotFound => "auth:not-found"
    case Method => "auth:method"
    case Origin => "auth:origin"
    case Invalid => "auth:invalid"
    case LoginRequired => "auth:required:login"
    case Session(reason) => reason.code
    case AgreementRequired => "auth:agreement:required"
    case Turnstile => "auth:turnstile"
    case Device(reason) => reason.code
    case Service(failure) => failure.code
    case Unavailable => "auth:unavailable"
  }

  def status: Int = this match {
    case NotFound => 404
    case Method => 405
    case Origin => 403
    case LoginRequired | Session(_) => 401
    case Service(AuthServiceFailure.RateLimited) => 429
    case Service(_) | Unavailable => 503
    case Invalid | AgreementRequired | Turnstile | Device(_) => 400
  }

  /** セッションが無い・切れた・ログアウト済みで、ログインし直せば解消するか */
  def signedOut: Boolean = this match {
    case LoginRequired | Session(AccountRejection.Generation | AccountRejection.Expired | AccountRejection.Missing) => true
    case _ => false
  }
}

/** クライアント認証と本人のセッション・専ブラキーを扱う */
object AuthHandler {
  private final case class Refused(error: AuthApiError) extends RuntimeException(error.code)
  private val AccountPath = "/auth/account/"
  private def now: Double = math.floor(js.Date.now() / 1000)
  private def header(request: Request, name: String): String = Option(request.headers.get(name)).getOrElse("")
  private def json(value: js.Any, status: Int = 200, cookie: Option[String] = None): Response = {
    val response = Responses.response(js.JSON.stringify(value), status, js.Dictionary("Content-Type" -> "application/json;charset=UTF-8"))
    secure(response)
    cookie.foreach(response.headers.append("Set-Cookie", _))
    response
  }
  private def secure(response: Response): Response = {
    response.headers.set("Cache-Control", "private, no-store")
    response.headers.set("Vary", "Cookie")
    response.headers.set("Referrer-Policy", "no-referrer")
    response.headers.set("Content-Security-Policy", "frame-ancestors 'self'; script-src 'self' https://challenges.cloudflare.com; frame-src 'self' https://challenges.cloudflare.com; form-action 'self' https://accounts.google.com")
    response
  }
  private def message(error: AuthApiError): String = error match {
    case AuthApiError.AgreementRequired => "利用規約と投稿の法的責任への同意が必要です。"
    case AuthApiError.Device(DeviceRejection.DeclarationMismatch) => "最新のSafari・Chrome・Edge・Firefoxでお試しください。"
    case AuthApiError.Device(DeviceRejection.Fingerprint) => "端末情報を確認できませんでした。別のブラウザでお試しください。"
    case AuthApiError.Turnstile => "確認を完了してから、もう一度お試しください。"
    case AuthApiError.Service(AuthServiceFailure.RateLimited) => "認証の回数が多すぎます。しばらく待ってからお試しください。"
    case AuthApiError.LoginRequired => "認証・登録が必要です。"
    case AuthApiError.Origin => "このページから送信してください。"
    case AuthApiError.Service(AuthServiceFailure.Unavailable) | AuthApiError.Unavailable => "認証を利用できません。しばらく待ってからお試しください。"
    case _ => "認証を完了できませんでした。"
  }
  private def reason(error: Throwable): AuthApiError = error match {
    case Refused(value) => value
    case AuthServiceException(value) => AuthApiError.Service(value)
    case NonFatal(_) => AuthApiError.Unavailable
  }
  private def failure(error: Throwable, cookie: Option[String] = None): Response = {
    val value = reason(error)
    json(js.Dynamic.literal(error = message(value), code = value.code), value.status, cookie)
  }
  /** 認証を設定していない環境では、認証が無かったときと同じ 404 を返す */
  private def notFound(request: Request): Response = {
    val path = Try(new URL(request.url).pathname).getOrElse("/auth/")
    val escaped = path.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    Responses.response(PublicText.respond404(escaped), 404, js.Dictionary("content-type" -> "text/html;charset=UTF-8"))
  }
  private def origin(url: String): String = new URL(url).origin

  /** 戻り先は同一オリジンの安全なパスだけを受け付ける */
  def safeReturn(value: String, requestUrl: String): String = {
    if (!value.startsWith("/") || value.startsWith("//") || !value.matches("[A-Za-z0-9/_\\.\\-?=&%]+")) "/"
    else {
      val decoded = Try(js.URIUtils.decodeURIComponent(value)).getOrElse("")
      if (decoded.isEmpty || decoded.startsWith("//") || decoded.contains('\\') || decoded.exists(c => c < ' ' || c == 127)) "/"
      else Try { val uri = new URL(value, origin(requestUrl)); if (uri.origin == origin(requestUrl)) value else "/" }.getOrElse("/")
    }
  }
  /** application/x-www-form-urlencoded の本文を読む。不正な % は要求の誤りにする */
  private def fields(request: Request): Future[Map[String, String]] = request.arrayBuffer().toFuture.map { bytes =>
    val text = new TextDecoder().decode(new scala.scalajs.js.typedarray.Uint8Array(bytes))
    def decode(value: String): String = Try(js.URIUtils.decodeURIComponent(value.replace("+", " "))).getOrElse(throw Refused(AuthApiError.Invalid))
    text.split("&").iterator.filter(_.nonEmpty).map { entry =>
      val pair = entry.split("=", 2)
      decode(pair(0)) -> decode(pair.lift(1).getOrElse(""))
    }.toMap
  }
  private def claims(account: AccountDescription, time: Double, device: String, binding: Option[SessionBinding] = None): SessionClaims =
    SessionClaims(account.aid, account.uid, account.generation.toLong, account.expiresAt.getOrElse(time + AccountProfile.clientLifetime).toLong, account.lv, device, binding)
  private def signed(account: AccountDescription, device: String, request: Request, secret: String, time: Double, binding: Option[SessionBinding] = None): Future[String] =
    SessionCookies.issue(claims(account, time, device, binding), secret, request.url, time).map(_.getOrElse(throw Refused(AuthApiError.Unavailable)))
  private def current(request: Request, secret: String, repository: AccountRepository, time: Double): Future[(SessionClaims, AccountDescription)] =
    SessionCookies.verify(header(request, "Cookie"), secret, time).flatMap {
      case None => Future.failed(Refused(AuthApiError.LoginRequired))
      case Some(session) if !session.generation.isValidInt || session.generation < 0 => Future.failed(Refused(AuthApiError.LoginRequired))
      case Some(session) =>
        val proof = session.binding.fold(Future.successful(Option.empty[TokenProof])) { binding =>
          AuthHmac.uaHash(secret, header(request, "User-Agent")).map { hash =>
            Some(TokenProof(binding.tokenId, hash.filter(_.startsWith(binding.uaHash)).getOrElse(throw Refused(AuthApiError.LoginRequired))))
          }
        }
        proof.flatMap(repository.describe(session.aid, session.generation.toInt, time, _)).map { result =>
          session -> result.fold(reason => throw Refused(AuthApiError.Session(reason)), identity)
        }
    }

  /** 認証画面と本人向け設定画面を SSR する。未ログインでアカウント設定を開いたら認証画面へ送る */
  def page(c: HonoContextFacade, accountPage: Boolean = false): js.Promise[Response] = {
    val request = c.req.raw
    AuthEnv.settings(c.env) match {
      case None => js.Promise.resolve[Response](notFound(request))
      case Some(config) =>
        val props = new RenderProps {}
        props.title = if (accountPage) "アカウント設定" else "認証・登録"
        val rendered = Future.unit.flatMap { _ =>
          if (accountPage) account(c, config, props)
          else c.renderPromise(AuthComponent.Component(AuthProps(config.turnstileSiteKey, safeReturn(c.req.query("return").getOrElse("/"), request.url),
            c.req.query("embed").contains("1"), c.req.query("mode").contains("login"), config.agreementVersion)).rawElement, props).toFuture
        }
        rendered.map(secure).recover {
          case Refused(error) if accountPage && error.signedOut =>
            secure(Responses.response(null, 302, js.Dictionary("Location" -> s"/auth/?return=$AccountPath")))
          case error => failure(error)
        }.toJSPromise
    }
  }

  private def account(c: HonoContextFacade, config: AuthSettings, props: RenderProps): Future[Response] = {
    val repository = new AccountRepository(c.env)
    current(c.req.raw, config.secret, repository, now).flatMap { case (session, account) =>
      repository.inspect(account.aid).flatMap { panel =>
        val tokens = panel.tokens.map(token => AccountTokenProps(token.id, token.label, token.issuedAt, token.expiresAt,
          token.boundAt, token.lastUsedAt, token.revokedAt, token.revokeReason, token.replacedTokenAt,
          token.recentPosts.map(post => AccountPostProps(post.t, post.bbs, post.key, post.title, post.resNum, post.excerpt, post.network)),
          token.warnings.map(warning => AccountTokenWarningProps(warning.t, warning.uaLabel))))
        val device = DeviceHashRef.parse(session.device).map(_.granularity)
        c.renderPromise(AccountComponent.Component(AccountProps(account.uid, account.lv, tokens, panel.activeCount,
          kind = account.kind, expiresAt = account.expiresAt, device = device, deviceCount = panel.deviceCount)).rawElement, props).toFuture
      }
    }
  }

  /** 認証 API。変更要求は Origin の完全一致を必須にする */
  def handle(request: Request, env: WorkerEnv): js.Promise[Response] = AuthEnv.settings(env) match {
    case None => js.Promise.resolve[Response](notFound(request))
    case Some(config) => Future.unit.flatMap { _ =>
      val repository = new AccountRepository(env)
      val path = new URL(request.url).pathname.stripSuffix("/")
      val time = now
      if (request.method == "GET" && path == "/auth/session") {
        current(request, config.secret, repository, time).map { case (_, account) => json(js.Dynamic.literal(uid = account.uid, lv = account.lv)) }
          .recover { case Refused(_) => json(null) }
      } else {
        if (request.method != "POST") throw Refused(AuthApiError.Method)
        if (header(request, "Origin") != origin(request.url)) throw Refused(AuthApiError.Origin)
        if (!header(request, "Content-Type").contains("application/x-www-form-urlencoded")) throw Refused(AuthApiError.Invalid)
        fields(request).flatMap { form =>
          path match {
            case "/auth/verify" => verify(request, env, repository, form, config, time)
            case "/auth/logout" => logout(request, config, repository, time)
            case "/auth/agree" => current(request, config.secret, repository, time).flatMap { case (_, account) =>
              if (!form.get("agreement").contains(config.agreementVersion)) throw Refused(AuthApiError.AgreementRequired)
              repository.agree(account.aid, config.agreementVersion, time).map(_ => json(js.Dynamic.literal(uid = account.uid, lv = account.lv)))
            }
            case "/auth/token/issue" => current(request, config.secret, repository, time).flatMap { case (_, account) =>
              if (account.agreementVersion != config.agreementVersion) throw Refused(AuthApiError.AgreementRequired)
              issueToken(repository, account, config.secret, time)
            }
            case "/auth/token/revoke" => current(request, config.secret, repository, time).flatMap { case (_, account) =>
              val id = form.getOrElse("id", "")
              if (!DedicatedBrowserTokens.validId(id)) throw Refused(AuthApiError.Invalid)
              repository.revokeToken(account.aid, id, time).map(_ => json(js.Dynamic.literal(uid = account.uid, lv = account.lv)))
            }
            case _ => Future.failed(Refused(AuthApiError.NotFound))
          }
        }
      }
    }.recover { case error => failure(error) }.toJSPromise
  }

  /** 世代を進めて全端末をログアウトさせる。セッションの検証や保存に失敗しても、この端末の Cookie は消す */
  private def logout(request: Request, config: AuthSettings, repository: AccountRepository, time: Double): Future[Response] = {
    val cleared = SessionCookies.clear(request.url)
    current(request, config.secret, repository, time)
      .flatMap { case (_, account) => repository.logout(account.aid, account.generation, time) }
      .map(_ => json(js.Dynamic.literal(ok = true), cookie = cleared))
      .recover {
        case Refused(error) if error.signedOut => json(js.Dynamic.literal(ok = true), cookie = cleared)
        case error => failure(error, cleared)
      }
  }

  private def verify(request: Request, env: WorkerEnv, repository: AccountRepository, form: Map[String, String], config: AuthSettings, time: Double): Future[Response] = {
    val ip = header(request, "CF-Connecting-IP")
    val hints = ClientHints.fromHeaders(header(request, "Sec-CH-UA-Platform"), header(request, "Sec-CH-UA-Platform-Version"), header(request, "Sec-CH-UA-Model"), header(request, "Sec-CH-UA-Arch"), header(request, "Sec-CH-UA-Mobile"))
    if (!form.get("agreement").contains(config.agreementVersion)) Future.failed(Refused(AuthApiError.AgreementRequired))
    else if (ip.isEmpty) Future.failed(Refused(AuthApiError.Unavailable))
    else for {
      keyIp <- AuthHmac.indexKey(config.secret, "key-ip", KeyIp.of(ip)).map(_.getOrElse(throw Refused(AuthApiError.Unavailable)))
      verified <- TurnstileVerification.verify(config.turnstileSecretKey, form.getOrElse("cf-turnstile-response", ""), ip, new URL(request.url).hostname)
      _ = if (!verified) throw Refused(AuthApiError.Turnstile)
      profiles <- env.ch3www.get("ios-profiles.json").toFuture.flatMap(value =>
        if (value == null) Future.successful(Vector.empty[IosDeviceProfile])
        else value.text().toFuture.map(DeviceEvidence.profilesFromJson)).recover { case NonFatal(_) => Vector.empty }
      evidence = DeviceEvidence.fromJson(form.getOrElse("device", ""), hints, header(request, "User-Agent"), profiles).fold(reason => {
        if (reason == DeviceRejection.DeclarationMismatch) {
          DeviceEvidence.declarationRuleFromJson(form.getOrElse("device", ""), hints, header(request, "User-Agent"))
            .foreach(rule => js.Dynamic.global.console.warn(rule))
        }
        throw Refused(AuthApiError.Device(reason))
      }, identity)
      existing <- current(request, config.secret, repository, time).map(value => Some(value)).recover {
        case Refused(error) if error.signedOut => None
      }
      created <- new ClientAuthentication(repository, keys(config.secret), config.agreementVersion).verify(evidence.deviceHash, keyIp, time, existing.map(_._2))
      cookie <- signed(created, evidence.deviceHash, request, config.secret, time, existing.flatMap(_._1.binding))
    } yield json(js.Dynamic.literal(uid = created.uid, lv = created.lv, returnPath = safeReturn(form.getOrElse("return", "/"), request.url)), cookie = Some(cookie))
  }
  private def keys(secret: String): AuthenticationKeys = new AuthenticationKeys {
    def aid(): String = AuthHmac.randomAid()
    def uid(): String = AuthHmac.randomUid()
    def token(): String = AuthHmac.randomBase32(20)
    def tokenId(): String = AuthHmac.randomBase32(16)
    def index(kind: String, value: String): Future[Option[String]] = AuthHmac.indexKey(secret, kind, value)
  }
  private def issueToken(repository: AccountRepository, account: AccountDescription, secret: String, time: Double): Future[Response] =
    new TokenIssuing(repository, keys(secret)).issue(account, time).map(value =>
      json(js.Dynamic.literal(uid = account.uid, lv = account.lv, token = ("!#" + value.token))))
}
