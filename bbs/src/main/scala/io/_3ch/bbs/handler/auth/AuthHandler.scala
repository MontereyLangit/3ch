package io._3ch.bbs.handler.auth

import io._3ch.bbs.application.auth.{AuthServiceException, AuthServiceFailure, AuthSettings, CapIssuing, CapNameException, ClientAuthentication, DeviceEvidence, LoginOutcome, PasswordAuthentication, TokenIssuing}
import io._3ch.bbs.application.auth.ports.{AccountStoreException, AuthenticationKeys, PasswordHashing, TokenProof}
import io._3ch.bbs.application.posting.CapKey
import io._3ch.bbs.components.{AccountCapProps, AccountComponent, AccountProps, AccountTokenProps, AccountPostProps, AccountTokenWarningProps, AuthComponent, AuthProps, AuthTokenComponent, AuthTokenProps}
import io._3ch.bbs.external.cloudflare.WorkerEnv
import io._3ch.bbs.external.hono.{HonoContextFacade, RenderProps}
import io._3ch.bbs.external.web.{Request, Response, TextDecoder, URL}
import io._3ch.bbs.handler.{PublicText, Responses}
import io._3ch.bbs.hash.{AuthHmac, PasswordHasher, WebHasher}
import io._3ch.bbs.repository.{AccountRepository, CapRepository, MailSenders}
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
  /** 不一致・ロック中・未登録を区別しないログインの失敗 */
  case LoginFailed
  case Refused(reason: AccountRejection)
  case LoginId(reason: LoginIdRejection)
  case Password(reason: PasswordRejection)
  case Confirmation
  /** キャップの発行・失効の拒否 */
  case Cap(reason: AccountRejection)
  case CapName(reason: CapNameRejection)
  case Email(reason: EmailRejection)
  /** メールの送信先が設定されていない */
  case MailUnavailable
  /** メールのリンクが無効か期限切れ */
  case LinkInvalid

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
    case LoginFailed => AccountRejection.LoginFailed.code
    case Refused(reason) => reason.code
    case LoginId(_) => "auth:id:invalid"
    case Password(_) => "auth:password:invalid"
    case Confirmation => "auth:password:confirmation"
    case Cap(reason) => reason.code
    case CapName(_) => "auth:cap:name"
    case Email(EmailRejection.Invalid) => "auth:email:invalid"
    case Email(EmailRejection.Disposable) => "auth:email:disposable"
    case MailUnavailable => "auth:mail:unavailable"
    case LinkInvalid => AccountRejection.PendingInvalid.code
  }

  def status: Int = this match {
    case NotFound => 404
    case Method => 405
    case Origin => 403
    case LoginRequired | Session(_) => 401
    case Service(AuthServiceFailure.RateLimited) => 429
    case Service(AuthServiceFailure.Taken | AuthServiceFailure.EmailTaken) => 409
    case Service(AuthServiceFailure.Invalid) => 400
    case Service(_) | Unavailable | MailUnavailable => 503
    case LoginFailed => 401
    case Refused(_) | Cap(_) => 403
    case Invalid | CapName(_) | Email(_) | LinkInvalid | AgreementRequired | Turnstile | Device(_) | LoginId(_) | Password(_) | Confirmation => 400
  }

  /** セッションが無い・切れた・ログアウト済みで、ログインし直せば解消するか */
  def signedOut: Boolean = this match {
    case LoginRequired | Session(AccountRejection.Generation | AccountRejection.Expired | AccountRejection.Missing) => true
    case _ => false
  }
}

/** クライアント認証と本人のセッション・専ブラキーを扱う */
object AuthHandler {
  private[auth] final case class Refused(error: AuthApiError) extends RuntimeException(error.code)
  private val AccountPath = "/auth/account/"
  private[auth] def now: Double = math.floor(js.Date.now() / 1000)
  private[auth] def header(request: Request, name: String): String = Option(request.headers.get(name)).getOrElse("")
  private[auth] def json(value: js.Any, status: Int = 200, cookie: Option[String] = None): Response = {
    val response = Responses.response(js.JSON.stringify(value), status, js.Dictionary("Content-Type" -> "application/json;charset=UTF-8"))
    secure(response)
    cookie.foreach(response.headers.append("Set-Cookie", _))
    response
  }
  private[auth] def secure(response: Response): Response = {
    response.headers.set("Cache-Control", "private, no-store")
    response.headers.set("Vary", "Cookie")
    response.headers.set("Referrer-Policy", "no-referrer")
    response.headers.set("Content-Security-Policy", "frame-ancestors 'self'; script-src 'self' https://challenges.cloudflare.com; frame-src 'self' https://challenges.cloudflare.com; form-action 'self' https://accounts.google.com")
    response
  }
  private[auth] def message(error: AuthApiError): String = error match {
    case AuthApiError.AgreementRequired => "利用規約と投稿の法的責任への同意が必要です。"
    case AuthApiError.Device(DeviceRejection.DeclarationMismatch) => "最新のSafari・Chrome・Edge・Firefoxでお試しください。"
    case AuthApiError.Device(DeviceRejection.Fingerprint) => "端末情報を確認できませんでした。別のブラウザでお試しください。"
    case AuthApiError.Turnstile => "確認を完了してから、もう一度お試しください。"
    case AuthApiError.Service(AuthServiceFailure.RateLimited) => "認証の回数が多すぎます。しばらく待ってからお試しください。"
    case AuthApiError.LoginRequired => "認証・登録が必要です。"
    case AuthApiError.Origin => "このページから送信してください。"
    case AuthApiError.Service(AuthServiceFailure.Unavailable) | AuthApiError.Unavailable => "認証を利用できません。しばらく待ってからお試しください。"
    case AuthApiError.LoginFailed => "ユーザーIDまたはパスワードが違います。"
    case AuthApiError.Refused(AccountRejection.KindForbidden) => "この登録方法では使えません。"
    case AuthApiError.Refused(_) => "このアカウントは利用できません。"
    case AuthApiError.Service(AuthServiceFailure.Taken) => "このユーザーIDは使えません。別のIDを入力してください。"
    case AuthApiError.LoginId(LoginIdRejection.Invalid) => "ユーザーIDは半角英小文字・数字・_の3〜16文字で入力してください。"
    case AuthApiError.Password(PasswordRejection.Length) => "パスワードは8文字以上、128バイト以内で入力してください。"
    case AuthApiError.Password(PasswordRejection.ContainsLoginId) => "パスワードにユーザーIDを含めないでください。"
    case AuthApiError.Password(PasswordRejection.InvalidUnicode) => "パスワードに使えない文字が含まれています。"
    case AuthApiError.Confirmation => "確認用のパスワードが一致しません。"
    case AuthApiError.Password(PasswordRejection.RoleLength) => s"権限のあるアカウントのパスワードは${Password.RoleMinimum}文字以上にしてください。"
    case AuthApiError.Email(EmailRejection.Invalid) => "メールアドレスを確認してください。"
    case AuthApiError.Email(EmailRejection.Disposable) => "このメールアドレスは使えません。普段お使いのアドレスを入力してください。"
    case AuthApiError.Service(AuthServiceFailure.EmailTaken) => "このメールアドレスはほかのアカウントで使われています。"
    case AuthApiError.Service(AuthServiceFailure.MailFailed) => "確認メールを送れませんでした。しばらく待ってから送り直してください。"
    case AuthApiError.MailUnavailable => "メールによる認証は準備中です。"
    case AuthApiError.LinkInvalid => "リンクが無効か、期限が切れています。"
    case AuthApiError.Cap(AccountRejection.RolesForbidden | AccountRejection.Forbidden) =>
      "キャップを発行できません。削除・規制などの権限を持つアカウントが、自分用に1個まで発行できます。"
    case AuthApiError.Cap(AccountRejection.CapRevoked) => "このキャップはすでに無効です。"
    case AuthApiError.Cap(_) => "キャップを操作できませんでした。"
    case AuthApiError.CapName(CapNameRejection.Length) => s"キャップの名前は1〜${CapName.MaxLength}文字で入力してください。"
    case AuthApiError.CapName(CapNameRejection.Character) => "キャップの名前に使えない文字が含まれています。"
    case _ => "認証を完了できませんでした。"
  }
  private def reason(error: Throwable): AuthApiError = error match {
    case Refused(value) => value
    case AuthServiceException(value) => AuthApiError.Service(value)
    case NonFatal(_) => AuthApiError.Unavailable
  }
  private[auth] def failure(error: Throwable, cookie: Option[String] = None): Response = {
    val value = reason(error)
    json(js.Dynamic.literal(error = message(value), code = value.code), value.status, cookie)
  }
  /** 認証を設定していない環境では、認証が無かったときと同じ 404 を返す */
  private[auth] def notFound(request: Request): Response = {
    val path = Try(new URL(request.url).pathname).getOrElse("/auth/")
    val escaped = path.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    Responses.response(PublicText.respond404(escaped), 404, js.Dictionary("content-type" -> "text/html;charset=UTF-8"))
  }
  private[auth] def origin(url: String): String = new URL(url).origin

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
  private[auth] def signed(account: AccountDescription, device: String, request: Request, secret: String, time: Double, binding: Option[SessionBinding] = None): Future[String] =
    SessionCookies.issue(claims(account, time, device, binding), secret, request.url, time).map(_.getOrElse(throw Refused(AuthApiError.Unavailable)))
  private[auth] def current(request: Request, secret: String, repository: AccountRepository, time: Double): Future[(SessionClaims, AccountDescription)] =
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

  /** Google ログインから戻したときの理由。知らない値は出さない */
  private def notice(code: String): Option[String] = code match {
    case "google_denied" => Some("Googleでのログインを中止しました。")
    case "google" | "oauth_state" => Some("Googleでのログインを確認できませんでした。もう一度お試しください。")
    case "google_register" => Some("このGoogleアカウントはまだ登録されていません。ユーザーIDを決めて「Googleで登録する」を押してください。")
    case "uid" => Some(message(AuthApiError.LoginId(LoginIdRejection.Invalid)))
    case "uid_taken" => Some(message(AuthApiError.Service(AuthServiceFailure.Taken)))
    case "agreement" => Some(message(AuthApiError.AgreementRequired))
    case "device" => Some(message(AuthApiError.Device(DeviceRejection.Fingerprint)))
    case "account" => Some(message(AuthApiError.Refused(AccountRejection.Frozen)))
    case "unavailable" => Some(message(AuthApiError.Unavailable))
    case _ => None
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
            c.req.query("embed").contains("1"), c.req.query("mode").contains("login"), config.agreementVersion,
            AuthEnv.google(c.env).isDefined, notice(c.req.query("error").getOrElse("")).getOrElse(""), MailSenders.from(c.env).isDefined)).rawElement, props).toFuture
        }
        rendered.map(secure).recover {
          case Refused(error) if accountPage && error.signedOut =>
            secure(Responses.response(null, 302, js.Dictionary("Location" -> s"/auth/?return=$AccountPath")))
          case error => failure(error)
        }.toJSPromise
    }
  }

  /** メールのリンクの画面。トークンの消費は画面のボタンからの POST で行う */
  def tokenPage(c: HonoContextFacade, reset: Boolean): js.Promise[Response] = {
    val request = c.req.raw
    AuthEnv.settings(c.env) match {
      case None => js.Promise.resolve[Response](notFound(request))
      case Some(_) =>
        val props = new RenderProps {}
        props.title = if (reset) "パスワードの再設定" else "メールアドレスの確認"
        val token = c.req.query("token").filter(value => PendingLink.parse(value).isDefined).getOrElse("")
        c.renderPromise(AuthTokenComponent.Component(AuthTokenProps(reset, token, MailSenders.from(c.env).isDefined)).rawElement, props)
          .toFuture.map(secure).recover { case error => failure(error) }.toJSPromise
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
        val caps = panel.caps.map(cap => AccountCapProps(cap.id, cap.name, cap.strong, cap.issuedAt, cap.revokedAt,
          cap.recentPosts.map(post => AccountPostProps(post.t, post.bbs, post.key, post.title, post.resNum, post.excerpt, post.network))))
        c.renderPromise(AccountComponent.Component(AccountProps(account.uid, account.lv, tokens, panel.activeCount,
          kind = account.kind, expiresAt = account.expiresAt, device = device, deviceCount = panel.deviceCount, caps = caps, roles = account.roles,
          email = panel.email, pendingEmail = panel.pendingEmail, mailEnabled = MailSenders.from(c.env).isDefined)).rawElement, props).toFuture
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
      if (request.method == "GET" && path == "/auth/google/callback") {
        GoogleHandler.callback(request, env, repository, config, time)
      } else if (request.method == "GET" && path == "/auth/session") {
        current(request, config.secret, repository, time).map { case (_, account) => json(js.Dynamic.literal(uid = account.uid, lv = account.lv)) }
          .recover { case Refused(_) => json(null) }
      } else {
        if (request.method != "POST") throw Refused(AuthApiError.Method)
        if (header(request, "Origin") != origin(request.url)) throw Refused(AuthApiError.Origin)
        if (!header(request, "Content-Type").contains("application/x-www-form-urlencoded")) throw Refused(AuthApiError.Invalid)
        fields(request).flatMap { form =>
          path match {
            case "/auth/verify" => verify(request, env, repository, form, config, time)
            case "/auth/register" => register(request, env, repository, form, config, time)
            case "/auth/login" => login(request, env, repository, form, config, time)
            case "/auth/google/start" => GoogleHandler.start(request, env, form, config, time)
            case "/auth/logout" => logout(request, config, repository, time)
            case "/auth/agree" => current(request, config.secret, repository, time).flatMap { case (_, account) =>
              if (!form.get("agreement").contains(config.agreementVersion)) throw Refused(AuthApiError.AgreementRequired)
              repository.agree(account.aid, config.agreementVersion, time).map(_ => json(js.Dynamic.literal(uid = account.uid, lv = account.lv)))
            }
            case "/auth/token/issue" => current(request, config.secret, repository, time).flatMap { case (_, account) =>
              if (account.agreementVersion != config.agreementVersion) throw Refused(AuthApiError.AgreementRequired)
              issueToken(repository, account, config.secret, time)
            }
            case "/auth/cap/issue" | "/auth/cap/revoke" | "/auth/cap/reissue" => current(request, config.secret, repository, time).flatMap { case (_, account) =>
              if (account.agreementVersion != config.agreementVersion) throw Refused(AuthApiError.AgreementRequired)
              cap(path, form, env, repository, account, config.secret, time)
            }
            case "/auth/email" => AccountMailHandler.email(request, env, repository, form, config, time)
            case "/auth/email/verify" => AccountMailHandler.verify(request, env, repository, form, config, time)
            case "/auth/password" => AccountMailHandler.password(request, repository, form, config, time)
            case "/auth/reset/request" => AccountMailHandler.resetRequest(request, env, repository, form, config, time)
            case "/auth/reset" => AccountMailHandler.reset(env, repository, form, config, time)
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

  /** 受信 IP の keyIp の HMAC。IP が無い要求や鍵の障害は利用不可 */
  private[auth] def keyIp(request: Request, secret: String): Future[String] = {
    val ip = header(request, "CF-Connecting-IP")
    if (ip.isEmpty) Future.failed(Refused(AuthApiError.Unavailable))
    else AuthHmac.indexKey(secret, "key-ip", KeyIp.of(ip)).map(_.getOrElse(throw Refused(AuthApiError.Unavailable)))
  }

  /** 端末申告を受信ヘッダと突き合わせる。R2 の既知プロファイルが読めなければ表なしで照合する */
  private[auth] def device(request: Request, env: WorkerEnv, raw: String): Future[DeviceEvidence] = {
    val hints = ClientHints.fromHeaders(header(request, "Sec-CH-UA-Platform"), header(request, "Sec-CH-UA-Platform-Version"), header(request, "Sec-CH-UA-Model"), header(request, "Sec-CH-UA-Arch"), header(request, "Sec-CH-UA-Mobile"))
    env.ch3www.get("ios-profiles.json").toFuture.flatMap(value =>
      if (value == null) Future.successful(Vector.empty[IosDeviceProfile])
      else value.text().toFuture.map(DeviceEvidence.profilesFromJson)).recover { case NonFatal(_) => Vector.empty }.map { profiles =>
      DeviceEvidence.fromJson(raw, hints, header(request, "User-Agent"), profiles).fold(reason => {
        if (reason == DeviceRejection.DeclarationMismatch) {
          DeviceEvidence.declarationRuleFromJson(raw, hints, header(request, "User-Agent")).foreach(rule => js.Dynamic.global.console.warn(rule))
        }
        throw Refused(AuthApiError.Device(reason))
      }, identity)
    }
  }

  /** keyIp を求めてから Turnstile と端末申告を確認する */
  private[auth] def human(request: Request, env: WorkerEnv, form: Map[String, String], config: AuthSettings): Future[(String, DeviceEvidence)] = for {
    key <- keyIp(request, config.secret)
    verified <- TurnstileVerification.verify(config.turnstileSecretKey, form.getOrElse("cf-turnstile-response", ""), header(request, "CF-Connecting-IP"), new URL(request.url).hostname)
    _ = if (!verified) throw Refused(AuthApiError.Turnstile)
    evidence <- device(request, env, form.getOrElse("device", ""))
  } yield key -> evidence

  private def signedIn(request: Request, form: Map[String, String], config: AuthSettings, account: AccountDescription, evidence: DeviceEvidence, time: Double,
    mail: String = ""): Future[Response] =
    signed(account, evidence.deviceHash, request, config.secret, time).map { cookie =>
      val body = js.Dynamic.literal(uid = account.uid, lv = account.lv, returnPath = safeReturn(form.getOrElse("return", "/"), request.url))
      if (mail.nonEmpty) body.mail = mail
      json(body, cookie = Some(cookie))
    }

  private def verify(request: Request, env: WorkerEnv, repository: AccountRepository, form: Map[String, String], config: AuthSettings, time: Double): Future[Response] = {
    if (!form.get("agreement").contains(config.agreementVersion)) Future.failed(Refused(AuthApiError.AgreementRequired))
    else for {
      (key, evidence) <- human(request, env, form, config)
      existing <- current(request, config.secret, repository, time).map(value => Some(value)).recover {
        case Refused(error) if error.signedOut => None
      }
      created <- new ClientAuthentication(repository, keys(config.secret), config.agreementVersion).verify(evidence.deviceHash, key, time, existing.map(_._2))
      cookie <- signed(created, evidence.deviceHash, request, config.secret, time, existing.flatMap(_._1.binding))
    } yield json(js.Dynamic.literal(uid = created.uid, lv = created.lv, returnPath = safeReturn(form.getOrElse("return", "/"), request.url)), cookie = Some(cookie))
  }

  /** 登録に使えないユーザー ID。固定の予約語に R2 の reserved-ids.txt と既存の板名を加える。R2 の障害では登録しない */
  private[auth] def available(env: WorkerEnv, raw: String): Future[Either[LoginIdRejection, LoginId]] =
    LoginId.parse(raw) match {
      case Left(reason) => Future.successful(Left(reason))
      case Right(id) => for {
        reserved <- env.ch3www.get("reserved-ids.txt").toFuture.flatMap(value =>
          if (value == null) Future.successful(Set.empty[String])
          else value.text().toFuture.map(_.split("\\r?\\n").iterator.map(_.trim).filter(line => line.nonEmpty && !line.startsWith("#")).toSet))
        board <- env.ch3www.get(id.value + "/SETTING.TXT").toFuture.map(value => value != null)
      } yield LoginId.create(id.value, reserved, if (board) Set(id.value) else Set.empty)
    }

  private[auth] def hashing: PasswordHashing = new PasswordHashing {
    def create(password: Password, now: Double): Future[Option[PasswordCredential]] = PasswordHasher.create(password, now).map(_.toOption)
  }

  /** 入力規則は Turnstile より前に確かめ、使えない ID は予約語と板名も含めて同じ文言にする */
  private def register(request: Request, env: WorkerEnv, repository: AccountRepository, form: Map[String, String], config: AuthSettings, time: Double): Future[Response] = {
    val raw = form.getOrElse("id", "")
    val validated = for {
      id <- LoginId.parse(raw).left.map(AuthApiError.LoginId(_))
      password <- Password.create(form.getOrElse("password", ""), id).left.map(AuthApiError.Password(_))
      _ <- Either.cond(form.get("password2").contains(form.getOrElse("password", "")), (), AuthApiError.Confirmation)
      email <- AccountMailHandler.parse(env, form.get("email"))
    } yield (password, email)
    if (!form.get("agreement").contains(config.agreementVersion)) Future.failed(Refused(AuthApiError.AgreementRequired))
    else validated match {
      case Left(error) => Future.failed(Refused(error))
      case Right((password, email)) => for {
        (key, evidence) <- human(request, env, form, config)
        id <- available(env, raw).map(_.fold({
          case LoginIdRejection.Reserved => throw Refused(AuthApiError.Service(AuthServiceFailure.Taken))
          case reason => throw Refused(AuthApiError.LoginId(reason))
        }, identity))
        _ <- email.fold(Future.unit)(AccountMailHandler.check(env, repository, config, "", _))
        account <- new PasswordAuthentication(repository, keys(config.secret), hashing, config.agreementVersion).register(id, password, evidence.deviceHash, key, time)
        mail <- AccountMailHandler.afterRegistration(env, repository, config, request, account, email, time)
        response <- signedIn(request, form, config, account, evidence, time, mail)
      } yield response
    }
  }

  /** 失敗は理由を区別せず同じ文言・状態にし、アカウント全体の失敗数に応じて応答を遅らせる */
  private def login(request: Request, env: WorkerEnv, repository: AccountRepository, form: Map[String, String], config: AuthSettings, time: Double): Future[Response] = for {
    (key, evidence) <- human(request, env, form, config)
    id = LoginId.parse(form.getOrElse("id", "")).toOption
    outcome <- new PasswordAuthentication(repository, keys(config.secret), hashing, config.agreementVersion).login(id, form.getOrElse("password", ""), evidence.deviceHash, key, time)
    response <- outcome match {
      case LoginOutcome.Success(account) => signedIn(request, form, config, account, evidence, time)
      case LoginOutcome.Refused(reason) => Future.failed(Refused(AuthApiError.Refused(reason)))
      case LoginOutcome.Failed(delay) => pause(delay).flatMap(_ => Future.failed(Refused(AuthApiError.LoginFailed)))
    }
  } yield response

  private[auth] def pause(seconds: Int): Future[Unit] = {
    if (seconds <= 0) Future.unit
    else {
      val promise = scala.concurrent.Promise[Unit]()
      js.timers.setTimeout(seconds * 1000d)(promise.success(()))
      promise.future
    }
  }

  private[auth] def keys(secret: String): AuthenticationKeys = new AuthenticationKeys {
    def aid(): String = AuthHmac.randomAid()
    def uid(): String = AuthHmac.randomUid()
    def token(): String = AuthHmac.randomBase32(20)
    def tokenId(): String = AuthHmac.randomBase32(16)
    def linkSecret(): String = AuthHmac.randomUrlSafe(43)
    def index(kind: String, value: String): Future[Option[String]] = AuthHmac.indexKey(secret, kind, value)
  }
  private def capIssuing(env: WorkerEnv, repository: AccountRepository, secret: String): CapIssuing = {
    val salt = WebHasher.md5(String.valueOf(env.salt))
    new CapIssuing(repository, CapRepository.directory(env), keys(secret), password => CapKey.of(WebHasher, password, salt))
  }

  /** 本人のキャップを発行・失効・再発行する。パスワードは発行した応答でだけ返す。強キャップは admin だけが選べる */
  private def cap(path: String, form: Map[String, String], env: WorkerEnv, repository: AccountRepository, account: AccountDescription,
    secret: String, time: Double): Future[Response] = {
    val service = capIssuing(env, repository, secret)
    def id: String = {
      val value = form.getOrElse("id", "")
      if (!DedicatedBrowserTokens.validId(value)) throw Refused(AuthApiError.Invalid)
      value
    }
    val result: Future[js.Dynamic] = path match {
      case "/auth/cap/issue" => service.issue(account, account.aid, form.getOrElse("name", ""), form.get("strong").contains("1"), time)
        .map(issued => js.Dynamic.literal(id = issued.capId, password = "#" + issued.password))
      case "/auth/cap/reissue" => service.reissue(account, account.aid, id, time).map(issued => js.Dynamic.literal(id = issued.capId, password = "#" + issued.password))
      case _ => service.revoke(account, account.aid, id, time).map(_ => js.Dynamic.literal(ok = true))
    }
    result.map(value => json(value)).recoverWith {
      case AccountStoreException(reason) => Future.failed(Refused(AuthApiError.Cap(reason)))
      case CapNameException(reason) => Future.failed(Refused(AuthApiError.CapName(reason)))
    }
  }

  private def issueToken(repository: AccountRepository, account: AccountDescription, secret: String, time: Double): Future[Response] =
    new TokenIssuing(repository, keys(secret)).issue(account, time).map(value =>
      json(js.Dynamic.literal(uid = account.uid, lv = account.lv, token = ("!#" + value.token))))
}
