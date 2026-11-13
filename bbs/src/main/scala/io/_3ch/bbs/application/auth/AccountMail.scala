package io._3ch.bbs.application.auth

import io._3ch.bbs.application.auth.ports.{AccountEmailStore, AccountStoreException, AuthenticationKeys, MailSender, OutgoingMail, PasswordHashing, SignInResult}
import io._3ch.common.domain.account.*
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.control.NonFatal

/** 確認メールと再設定メールの固定の文面 */
object AuthMail {
  def verification(to: String, link: String): OutgoingMail = OutgoingMail(to, "【3ch】メールアドレスの確認",
    s"3chのアカウントにこのメールアドレスが登録されました。\n次のリンクを開いて、確認を完了してください（24時間有効）。\n\n$link\n\n心当たりが無い場合は、このメールを破棄してください。\n")

  def reset(to: String, link: String): OutgoingMail = OutgoingMail(to, "【3ch】パスワードの再設定",
    s"パスワードの再設定を受け付けました。\n次のリンクを開いて、新しいパスワードを設定してください（1時間有効）。\n\n$link\n\n心当たりが無い場合は、このメールを破棄してください。パスワードは変わりません。\n")
}

/** パスワードの変更の結果 */
enum PasswordChangeOutcome {
  case Changed(account: AccountDescription)
  case Failed
}

/** メールアドレスの登録・変更と確認、パスワードの変更と再設定 */
final class AccountMail(accounts: AccountEmailStore, keys: AuthenticationKeys, mail: MailSender, hashing: PasswordHashing) {
  private def unavailable: Nothing = throw AuthServiceException(AuthServiceFailure.Unavailable)
  private def hmac(kind: String, value: String): Future[String] = keys.index(kind, value).map(_.getOrElse(unavailable))
  private def pendingHash(kind: PendingKind, link: PendingLink): Future[String] = hmac("pending:" + kind.code, link.secret)
  private def counted(key: String, kind: String, now: Double)(allowed: AuthRateCounts => Boolean): Future[Boolean] =
    accounts.rate(key, kind, now).map(allowed)

  /** 他のアカウントが認証済みのアドレスなら EmailTaken。自分のアドレスはそのまま受け付ける */
  private def available(aid: String, email: EmailAddress): Future[String] =
    hmac("email", email.canonical).flatMap(hash => accounts.lookup("email", hash).map {
      case Some(owner) if owner != aid => throw AuthServiceException(AuthServiceFailure.EmailTaken)
      case _ => hash
    })

  /** ID・パスワードのアカウントにアドレスを登録・変更し、確認メールを送る。送信は一アカウント一日三回まで */
  def request(account: AccountDescription, email: EmailAddress, origin: String, now: Double): Future[Unit] =
    Future.unit.flatMap { _ =>
      if (account.kind != AccountKind.Password) throw AccountStoreException(AccountRejection.KindForbidden)
      for {
        _ <- available(account.aid, email)
        counter <- hmac("email-send", account.aid)
        allowed <- counted(counter, "email", now)(AbuseThresholds.emailSend)
        _ = if (!allowed) throw AuthServiceException(AuthServiceFailure.RateLimited)
        link = PendingLink(account.aid, keys.linkSecret())
        hash <- pendingHash(PendingKind.Email, link)
        _ <- accounts.issuePending(account.aid, PendingKind.Email, hash, Some(email.address), now)
        _ <- mail.send(AuthMail.verification(email.address, origin + "/auth/email/verify/?token=" + link.text))
          .recover { case NonFatal(_) => throw AuthServiceException(AuthServiceFailure.MailFailed) }
      } yield ()
    }

  /**
    * リンクのトークンを確かめ、email 索引を確定してからアドレスを確定させる（Lv3 から）。
    * 置き換えた前のアドレスは索引から外す。確定に失敗したら新しく取った索引を外す
    */
  def verify(link: PendingLink, now: Double): Future[AccountDescription] = for {
    hash <- pendingHash(PendingKind.Email, link)
    check <- accounts.checkPending(link.aid, PendingKind.Email, hash, now)
    email = check.email.flatMap(EmailAddress.parse(_).toOption).getOrElse(throw AccountStoreException(AccountRejection.Invalid))
    indexHash <- hmac("email", email.canonical)
    owner <- accounts.lookup("email", indexHash)
    reserved <- owner match {
      case Some(aid) if aid == link.aid => Future.successful(false)
      case Some(_) => Future.failed(AuthServiceException(AuthServiceFailure.EmailTaken))
      case None => accounts.reserve("email", indexHash, link.aid, now).flatMap { ok =>
        if (!ok) Future.failed(AuthServiceException(AuthServiceFailure.EmailTaken))
        else accounts.confirm("email", indexHash, link.aid, now).map(_ => true)
      }
    }
    verified <- accounts.verifyEmail(link.aid, hash, now).recoverWith { case NonFatal(error) =>
      (if (reserved) accounts.remove("email", indexHash, link.aid).recover { case NonFatal(_) => () } else Future.unit).flatMap(_ => Future.failed(error))
    }
    _ <- verified.previous.flatMap(EmailAddress.parse(_).toOption).filter(_.canonical != email.canonical).fold(Future.unit) { previous =>
      hmac("email", previous.canonical).flatMap(accounts.remove("email", _, link.aid)).recover { case NonFatal(_) => () }
    }
  } yield verified.account

  /**
    * 再設定を申請する。結果は区別せずに返し、メール認証済みで役割の無いアカウントにだけ送る。
    * 接続元ごと・アカウントごとに一日三回まで
    */
  def requestReset(loginId: LoginId, origin: String, keyIp: String, now: Double): Future[Unit] = for {
    allowed <- counted(keyIp, "reset", now)(AbuseThresholds.reset)
    _ = if (!allowed) throw AuthServiceException(AuthServiceFailure.RateLimited)
    loginHash <- hmac("login", loginId.value)
    owner <- accounts.lookup("login", loginHash)
    _ <- owner.fold(Future.unit) { aid =>
      (for {
        counter <- hmac("reset-send", aid)
        within <- counted(counter, "reset", now)(AbuseThresholds.reset)
        link = PendingLink(aid, keys.linkSecret())
        hash <- pendingHash(PendingKind.Reset, link)
        to <- if (within) accounts.issuePending(aid, PendingKind.Reset, hash, None, now) else Future.successful(None)
        _ <- to.fold(Future.unit)(address => mail.send(AuthMail.reset(address, origin + "/auth/reset/?token=" + link.text)))
      } yield ()).recover { case NonFatal(_) => () }
    }
  } yield ()

  /** 再設定のリンクのユーザー ID。新しいパスワードの検証に使う */
  def resetTarget(link: PendingLink, now: Double): Future[String] =
    pendingHash(PendingKind.Reset, link).flatMap(accounts.checkPending(link.aid, PendingKind.Reset, _, now)).map(_.uid)

  /** 再設定のトークンを消費してパスワードを置き換える。全端末がログアウトする */
  def reset(link: PendingLink, password: Password, now: Double): Future[AccountDescription] = for {
    hash <- pendingHash(PendingKind.Reset, link)
    credential <- hashing.create(password, now).map(_.getOrElse(unavailable))
    account <- accounts.resetPassword(link.aid, hash, credential, now)
  } yield account
}

/** ログイン中のパスワードの変更 */
final class PasswordChange(accounts: AccountEmailStore, hashing: PasswordHashing) {
  /** 現在のパスワードを確かめてから置き換え、世代を進める。照合の失敗は失敗計数に含める */
  def changePassword(account: AccountDescription, current: String, next: Password, keyIp: String, now: Double): Future[PasswordChangeOutcome] =
    Future.unit.flatMap { _ =>
      if (account.kind != AccountKind.Password) throw AccountStoreException(AccountRejection.KindForbidden)
      accounts.verifyPassword(account.aid, current, keyIp, now).flatMap {
        case SignInResult.Verified(_) => for {
          credential <- hashing.create(next, now).map(_.getOrElse(throw AuthServiceException(AuthServiceFailure.Unavailable)))
          changed <- accounts.setPassword(account.aid, credential, now)
        } yield PasswordChangeOutcome.Changed(changed)
        case SignInResult.Failed => Future.successful(PasswordChangeOutcome.Failed)
        case SignInResult.Refused(reason) => Future.failed(AccountStoreException(reason))
        case SignInResult.Unfinished(_) => Future.failed(AccountStoreException(AccountRejection.Pending))
      }
    }
}
