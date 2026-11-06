package io._3ch.bbs.handler.auth

import io._3ch.bbs.application.auth.{AuthServiceException, ClientAuthentication, DeviceEvidence, TokenIssuing}
import io._3ch.bbs.application.auth.ports.AuthenticationKeys
import io._3ch.bbs.components.{AccountComponent, AccountProps, AccountTokenProps, AccountPostProps, AccountTokenWarningProps, AuthComponent, AuthProps}
import io._3ch.bbs.external.cloudflare.WorkerEnv
import io._3ch.bbs.external.hono.{HonoContextFacade, RenderProps}
import io._3ch.bbs.external.web.{Request, Response, TextDecoder}
import io._3ch.bbs.handler.Responses
import io._3ch.bbs.hash.AuthHmac
import io._3ch.bbs.repository.AccountRepository
import io._3ch.common.domain.account.*
import io._3ch.common.domain.device.ClientHints
import io._3ch.bbs.domain.posting.KeyIp
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import scala.util.Try
import scala.util.control.NonFatal

/** クライアント認証と本人のセッション・専ブラキーを扱う */
object AuthHandler {
  private final case class Refused(code: String, status: Int = 400) extends RuntimeException
  private def now: Double = math.floor(js.Date.now() / 1000)
  private def header(request: Request, name: String): String = Option(request.headers.get(name)).getOrElse("")
  private def config(env: WorkerEnv): (String, String) = {
    val secret = env.AUTH_SECRET.getOrElse("")
    val version = env.AGREEMENT_VERSION.getOrElse("")
    if (secret.isEmpty || version.isEmpty) throw Refused("auth:unavailable", 503)
    (secret, version)
  }
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
  private def message(code: String): String = code match {
    case "auth:agreement:required" => "利用規約と投稿の法的責任への同意が必要です。"
    case "declaration_mismatch" => "最新のSafari・Chrome・Edge・Firefoxでお試しください。"
    case "fingerprint" => "端末情報を確認できませんでした。別のブラウザでお試しください。"
    case "auth:turnstile" => "確認を完了してから、もう一度お試しください。"
    case "auth:rate" => "認証の回数が多すぎます。しばらく待ってからお試しください。"
    case "auth:required:login" => "認証・登録が必要です。"
    case "auth:origin" => "このページから送信してください。"
    case "auth:unavailable" => "認証を利用できません。しばらく待ってからお試しください。"
    case _ => "認証を完了できませんでした。"
  }
  private def failure(error: Throwable): Response = error match {
    case AuthServiceException(code) => json(js.Dynamic.literal(error = message(code), code = code), if (code == "auth:rate") 429 else 503)
    case Refused(code, status) => json(js.Dynamic.literal(error = message(code), code = code), status)
    case NonFatal(_) => json(js.Dynamic.literal(error = message("auth:unavailable"), code = "auth:unavailable"), 503)
  }
  private def origin(url: String): String = new io._3ch.bbs.external.web.URL(url).origin

  /** 戻り先は同一オリジンの安全なパスだけを受け付ける */
  def safeReturn(value: String, requestUrl: String): String = {
    if (!value.startsWith("/") || value.startsWith("//") || !value.matches("[A-Za-z0-9/_\\.\\-?=&%]+")) "/"
    else {
      val decoded = Try(js.URIUtils.decodeURIComponent(value)).getOrElse("")
      if (decoded.isEmpty || decoded.startsWith("//") || decoded.contains('\\') || decoded.exists(c => c < ' ' || c == 127)) "/"
      else Try { val uri = new io._3ch.bbs.external.web.URL(value, origin(requestUrl)); if (uri.origin == origin(requestUrl)) value else "/" }.getOrElse("/")
    }
  }
  private def fields(request: Request): Future[Map[String, String]] = request.arrayBuffer().toFuture.map { bytes =>
    val text = new TextDecoder().decode(new scala.scalajs.js.typedarray.Uint8Array(bytes))
    text.split("&").iterator.filter(_.nonEmpty).map { entry =>
      val pair = entry.split("=", 2)
      def decode(value: String): String = js.URIUtils.decodeURIComponent(value.replace("+", " "))
      decode(pair(0)) -> decode(pair.lift(1).getOrElse(""))
    }.toMap
  }
  private def claims(account: AccountDescription, time: Double, device: String, binding: Option[SessionBinding] = None): SessionClaims =
    SessionClaims(account.aid, account.uid, account.generation.toLong, account.expiresAt.getOrElse(time + AccountProfile.clientLifetime).toLong, account.lv, device, binding)
  private def signed(account: AccountDescription, device: String, request: Request, secret: String, time: Double, binding: Option[SessionBinding] = None): Future[String] =
    SessionCookies.issue(claims(account, time, device, binding), secret, request.url, time).map(_.getOrElse(throw Refused("auth:unavailable", 503)))
  private def current(request: Request, secret: String, repository: AccountRepository, time: Double): Future[(SessionClaims, AccountDescription)] =
    SessionCookies.verify(header(request, "Cookie"), secret, time).flatMap {
      case None => Future.failed(Refused("auth:required:login", 401))
      case Some(session) if !session.generation.isValidInt || session.generation < 0 => Future.failed(Refused("auth:required:login", 401))
      case Some(session) =>
        val proof = session.binding.fold(Future.successful(Option.empty[io._3ch.bbs.application.auth.ports.TokenProof])) { binding =>
          AuthHmac.uaHash(secret, header(request, "User-Agent")).map { hash =>
            Some(io._3ch.bbs.application.auth.ports.TokenProof(binding.tokenId, hash.filter(_.startsWith(binding.uaHash)).getOrElse(throw Refused("auth:required:login", 401))))
          }
        }
        proof.flatMap(repository.describe(session.aid, session.generation.toInt, time, _)).map { result =>
          session -> result.fold(reason => throw Refused(reason.code, 401), identity)
        }
    }

  /** 認証画面と本人向け設定画面を SSR する */
  def page(c: HonoContextFacade, accountPage: Boolean = false): js.Promise[Response] = {
    val request = c.req.raw
    Future.unit.flatMap { _ =>
      val (_, version) = config(c.env)
      val props = new RenderProps {}
      props.title = if (accountPage) "アカウント設定" else "認証・登録"
      if (accountPage) {
        val repository = new AccountRepository(c.env)
        current(request, c.env.AUTH_SECRET.getOrElse(""), repository, now).flatMap { case (_, account) =>
          repository.inspect(account.aid).flatMap { panel =>
            val tokens = panel.tokens.map(token => AccountTokenProps(token.id, token.label, token.issuedAt, token.expiresAt,
              token.boundAt, token.lastUsedAt, token.revokedAt, token.revokeReason, token.replacedTokenAt,
              token.recentPosts.map(post => AccountPostProps(post.t, post.bbs, post.key, post.title, post.resNum, post.excerpt, post.network)),
              token.warnings.map(warning => AccountTokenWarningProps(warning.t, warning.uaLabel))))
            c.renderPromise(AccountComponent.Component(AccountProps(account.uid, account.lv, tokens, panel.activeCount)).rawElement, props).toFuture
          }
        }
      } else c.renderPromise(AuthComponent.Component(AuthProps(c.env.TURNSTILE_SITE_KEY.getOrElse(""), safeReturn(c.req.query("return").getOrElse("/"), request.url),
        c.req.query("embed").contains("1"), c.req.query("mode").contains("login"), version)).rawElement, props).toFuture
    }.map(secure).recover { case error => failure(error) }.toJSPromise
  }

  /** 認証 API。変更要求は Origin の完全一致を必須にする */
  def handle(request: Request, env: WorkerEnv): js.Promise[Response] = Future.unit.flatMap { _ =>
    val (secret, version) = config(env)
    val repository = new AccountRepository(env)
    val path = new io._3ch.bbs.external.web.URL(request.url).pathname.stripSuffix("/")
    val time = now
    if (request.method == "GET" && path == "/auth/session") {
      current(request, secret, repository, time).map { case (_, account) => json(js.Dynamic.literal(uid = account.uid, lv = account.lv)) }
        .recover { case Refused(_, _) => json(null) }
    } else {
      if (request.method != "POST") throw Refused("auth:method", 405)
      if (header(request, "Origin") != origin(request.url)) throw Refused("auth:origin", 403)
      if (!header(request, "Content-Type").contains("application/x-www-form-urlencoded")) throw Refused("auth:invalid")
      fields(request).flatMap { form =>
        path match {
          case "/auth/verify" => verify(request, env, repository, form, secret, version, time)
          case "/auth/logout" => current(request, secret, repository, time).flatMap { case (_, account) =>
            repository.logout(account.aid, account.generation, time).map(_ => json(js.Dynamic.literal(ok = true), cookie = SessionCookies.clear(request.url)))
          }
          case "/auth/agree" => current(request, secret, repository, time).flatMap { case (session, account) =>
            if (!form.get("agreement").contains(version)) throw Refused("auth:agreement:required")
            repository.agree(account.aid, version, time).map(_ => json(js.Dynamic.literal(uid = account.uid, lv = account.lv)))
          }
          case "/auth/token/issue" => current(request, secret, repository, time).flatMap { case (_, account) =>
            if (account.agreementVersion != version) throw Refused("auth:agreement:required")
            issueToken(repository, account, secret, time)
          }
          case "/auth/token/revoke" => current(request, secret, repository, time).flatMap { case (_, account) =>
            val id = form.getOrElse("id", "")
            if (!DedicatedBrowserTokens.validId(id)) throw Refused("auth:invalid")
            repository.revokeToken(account.aid, id, time).map(_ => json(js.Dynamic.literal(uid = account.uid, lv = account.lv)))
          }
          case _ => Future.failed(Refused("auth:not-found", 404))
        }
      }
    }
  }.recover { case error => failure(error) }.toJSPromise

  private def verify(request: Request, env: WorkerEnv, repository: AccountRepository, form: Map[String, String], secret: String, version: String, time: Double): Future[Response] = {
    if (!form.get("agreement").contains(version)) return Future.failed(Refused("auth:agreement:required"))
    val serverKey = env.TURNSTILE_SECRET_KEY.getOrElse("")
    if (serverKey.isEmpty) return Future.failed(Refused("auth:unavailable", 503))
    val ip = header(request, "CF-Connecting-IP")
    val hints = ClientHints.fromHeaders(header(request, "Sec-CH-UA-Platform"), header(request, "Sec-CH-UA-Platform-Version"), header(request, "Sec-CH-UA-Model"), header(request, "Sec-CH-UA-Arch"), header(request, "Sec-CH-UA-Mobile"))
    for {
      verified <- TurnstileVerification.verify(serverKey, form.getOrElse("cf-turnstile-response", ""), ip, new io._3ch.bbs.external.web.URL(request.url).hostname)
      _ = if (!verified) throw Refused("auth:turnstile")
      profiles <- env.ch3www.get("ios-profiles.json").toFuture.flatMap(value =>
        if (value == null) Future.successful(Vector.empty[io._3ch.common.domain.device.IosDeviceProfile])
        else value.text().toFuture.map(DeviceEvidence.profilesFromJson)).recover { case NonFatal(_) => Vector.empty }
      evidence = DeviceEvidence.fromJson(form.getOrElse("device", ""), hints, header(request, "User-Agent"), profiles).fold(reason => {
        if (reason.code == "declaration_mismatch") {
          DeviceEvidence.declarationRuleFromJson(form.getOrElse("device", ""), hints, header(request, "User-Agent"))
            .foreach(rule => js.Dynamic.global.console.warn(rule))
        }
        throw Refused(reason.code)
      }, identity)
      keyIp <- AuthHmac.indexKey(secret, "key-ip", KeyIp.of(ip)).map(_.getOrElse(throw Refused("auth:unavailable", 503)))
      _ = if (ip.isEmpty) throw Refused("auth:unavailable", 503)
      existing <- current(request, secret, repository, time).map(value => Some(value)).recover {
        case Refused("auth:required:login" | "auth:session:invalid" | "auth:session:expired" | "auth:account:missing", _) => None
      }
      created <- new ClientAuthentication(repository, keys(secret), version).verify(evidence.deviceHash, keyIp, time, existing.map(_._2))
      cookie <- signed(created, evidence.deviceHash, request, secret, time, existing.flatMap(_._1.binding))
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
