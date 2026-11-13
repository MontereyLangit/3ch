package io._3ch.bbs.handler.auth

import io._3ch.bbs.application.auth.{AccountMail, AuthServiceFailure, AuthSettings, PasswordChange, PasswordChangeOutcome, StaleIndex}
import io._3ch.bbs.application.auth.ports.AccountStoreException
import io._3ch.bbs.external.cloudflare.WorkerEnv
import io._3ch.bbs.external.web.{Request, Response}
import io._3ch.bbs.repository.{AccountRepository, MailSenders}
import io._3ch.common.domain.account.*
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.util.control.NonFatal

/** メールアドレスの登録と確認、パスワードの変更と再設定の API */
object AccountMailHandler {
  import AuthHandler.{Refused, json, keys, hashing, managed, origin, signed}

  /** 送信先が設定されていれば使える。無ければメール関連の API は「準備中」 */
  def service(env: WorkerEnv, repository: AccountRepository, config: AuthSettings): Option[AccountMail] =
    MailSenders.from(env).map(new AccountMail(repository, keys(config.secret), _, hashing))

  private def required(env: WorkerEnv, repository: AccountRepository, config: AuthSettings): AccountMail =
    service(env, repository, config).getOrElse(throw Refused(AuthApiError.MailUnavailable))

  /** R2 の使い捨てメールのドメイン一覧。無ければ空、読めなければ利用不可 */
  private def disposable(env: WorkerEnv): Future[DisposableDomains] =
    env.ch3www.get("disposable-domains.txt").toFuture.flatMap { value =>
      if (value == null) Future.successful(DisposableDomains.empty) else value.text().toFuture.map(DisposableDomains.parse)
    }.recover { case NonFatal(_) => throw Refused(AuthApiError.Unavailable) }

  /** フォームのメール欄を読む。空なら None、送信先が無ければ準備中 */
  def parse(env: WorkerEnv, raw: Option[String]): Either[AuthApiError, Option[EmailAddress]] = raw.map(_.trim).filter(_.nonEmpty) match {
    case None => Right(None)
    case Some(_) if MailSenders.from(env).isEmpty => Left(AuthApiError.MailUnavailable)
    case Some(value) => EmailAddress.parse(value).left.map(AuthApiError.Email(_)).map(Some(_))
  }

  /** 使い捨てのドメインと、ほかのアカウントで認証済みのアドレスを拒否する。aid が空なら誰のアドレスでも使用済みとする */
  def check(env: WorkerEnv, repository: AccountRepository, config: AuthSettings, aid: String, email: EmailAddress): Future[Unit] = for {
    domains <- disposable(env)
    _ = if (domains.blocks(email)) throw Refused(AuthApiError.Email(EmailRejection.Disposable))
    hash <- keys(config.secret).index("email", email.canonical).map(_.getOrElse(throw Refused(AuthApiError.Unavailable)))
    owner <- repository.lookup("email", hash)
    freed <- owner.filter(_ != aid).fold(Future.successful(true))(other => new StaleIndex(repository).reclaim("email", email.canonical, hash, other))
  } yield if (!freed) throw Refused(AuthApiError.Service(AuthServiceFailure.EmailTaken))

  /** 登録直後に確認メールを送る。送れなくても登録は成功のままにし、結果だけを返す */
  def afterRegistration(env: WorkerEnv, repository: AccountRepository, config: AuthSettings, request: Request, account: AccountDescription,
    email: Option[EmailAddress], time: Double): Future[String] = email.fold(Future.successful("")) { address =>
    required(env, repository, config).request(account, address, origin(request.url), time).map(_ => "sent").recover { case NonFatal(_) => "failed" }
  }

  /**
    * ログイン中の ID・パスワードのアカウントでアドレスを登録・変更し、確認メールを送る。現在のパスワードを確かめる（パスワードの変更・退会と同じ照合）。
    * Google とかんたん登録のアカウントはメールを使えない
    */
  def email(request: Request, env: WorkerEnv, repository: AccountRepository, form: Map[String, String], config: AuthSettings, time: Double): Future[Response] = {
    val mail = required(env, repository, config)
    managed(request, config.secret, repository, time).flatMap { case (_, account) =>
      if (account.kind != AccountKind.Password) throw Refused(AuthApiError.Refused(AccountRejection.KindForbidden))
      val address = parse(env, form.get("email")).fold(error => throw Refused(error), _.getOrElse(throw Refused(AuthApiError.Email(EmailRejection.Invalid))))
      for {
        key <- AuthHandler.keyIp(request, config.secret)
        verified <- mail.reauthenticate(account, form.getOrElse("current", ""), key, time)
        _ = if (!verified) throw Refused(AuthApiError.LoginFailed)
        _ <- check(env, repository, config, account.aid, address)
        _ <- mail.request(account, address, origin(request.url), time)
      } yield json(js.Dynamic.literal(ok = true, email = address.address))
    }.recover {
      case AccountStoreException(reason) => throw Refused(AuthApiError.Refused(reason))
    }
  }

  /** メールのリンクのトークンでアドレスを確定させる。同じアカウントでログイン中なら Lv を写した Cookie を発行し直す */
  def verify(request: Request, env: WorkerEnv, repository: AccountRepository, form: Map[String, String], config: AuthSettings, time: Double): Future[Response] = {
    val mail = required(env, repository, config)
    val link = PendingLink.parse(form.getOrElse("token", "")).getOrElse(throw Refused(AuthApiError.LinkInvalid))
    mail.verify(link, time).recover(linkFailure).flatMap { account =>
      val session = managed(request, config.secret, repository, time).map(value => Some(value)).recover { case NonFatal(_) => None }
      session.flatMap {
        case Some((claims, _)) if claims.aid == account.aid =>
          signed(account, claims.device, request, config.secret, time, claims.binding).map(cookie => json(js.Dynamic.literal(uid = account.uid, lv = account.lv), cookie = Some(cookie)))
        case _ => Future.successful(json(js.Dynamic.literal(uid = account.uid, lv = account.lv)))
      }
    }
  }

  private def linkFailure[A]: PartialFunction[Throwable, A] = {
    case AccountStoreException(AccountRejection.PendingInvalid | AccountRejection.Missing | AccountRejection.Invalid) => throw Refused(AuthApiError.LinkInvalid)
    case AccountStoreException(reason) => throw Refused(AuthApiError.Refused(reason))
  }

  private def confirmed(form: Map[String, String]): String = {
    val password = form.getOrElse("password", "")
    if (!form.get("password2").contains(password)) throw Refused(AuthApiError.Confirmation)
    password
  }

  /** 現在のパスワードを確かめて変更し、この端末だけ新しい世代の Cookie を発行し直す（ほかの端末はログアウト） */
  def password(request: Request, repository: AccountRepository, form: Map[String, String], config: AuthSettings, time: Double): Future[Response] =
    managed(request, config.secret, repository, time).flatMap { case (claims, account) =>
      if (account.kind != AccountKind.Password) throw Refused(AuthApiError.Refused(AccountRejection.KindForbidden))
      val id = LoginId.parse(account.uid).getOrElse(throw Refused(AuthApiError.Invalid))
      val next = Password.create(confirmed(form), id, account.roles).fold(reason => throw Refused(AuthApiError.Password(reason)), identity)
      for {
        key <- AuthHandler.keyIp(request, config.secret)
        outcome <- new PasswordChange(repository, hashing).changePassword(account, form.getOrElse("current", ""), next, key, time)
        response <- outcome match {
          case PasswordChangeOutcome.Failed => Future.failed(Refused(AuthApiError.LoginFailed))
          case PasswordChangeOutcome.Changed(changed) => signed(changed, claims.device, request, config.secret, time, claims.binding)
            .map(cookie => json(js.Dynamic.literal(uid = changed.uid, lv = changed.lv), cookie = Some(cookie)))
        }
      } yield response
    }.recover {
      case AccountStoreException(reason) => throw Refused(AuthApiError.Refused(reason))
    }

  /** 再設定を申請する。ユーザー ID とメールの有無を漏らさないよう、結果を区別せずに返す */
  def resetRequest(request: Request, env: WorkerEnv, repository: AccountRepository, form: Map[String, String], config: AuthSettings, time: Double): Future[Response] = {
    val mail = required(env, repository, config)
    for {
      (key, _) <- AuthHandler.human(request, env, form, config)
      _ <- LoginId.parse(form.getOrElse("id", "")).fold(_ => Future.unit, mail.requestReset(_, origin(request.url), key, time))
    } yield json(js.Dynamic.literal(ok = true))
  }

  /** 再設定のリンクで新しいパスワードを設定する。Cookie は発行せず、ログインし直してもらう */
  def reset(env: WorkerEnv, repository: AccountRepository, form: Map[String, String], config: AuthSettings, time: Double): Future[Response] = {
    val mail = required(env, repository, config)
    val link = PendingLink.parse(form.getOrElse("token", "")).getOrElse(throw Refused(AuthApiError.LinkInvalid))
    val raw = confirmed(form)
    (for {
      uid <- mail.resetTarget(link, time)
      id = LoginId.parse(uid).getOrElse(throw Refused(AuthApiError.Invalid))
      password = Password.create(raw, id).fold(reason => throw Refused(AuthApiError.Password(reason)), identity)
      account <- mail.reset(link, password, time)
    } yield json(js.Dynamic.literal(ok = true, uid = account.uid))).recover(linkFailure)
  }
}
