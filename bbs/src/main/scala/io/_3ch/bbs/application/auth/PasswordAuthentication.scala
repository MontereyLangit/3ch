package io._3ch.bbs.application.auth

import io._3ch.bbs.application.auth.ports.{AbuseReviewLog, AccountManagementStore, AccountSignInStore, AuthenticationKeys, PasswordHashing, SignInResult}
import io._3ch.common.domain.account.*
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.control.NonFatal

/** ログインの結果。照合の失敗は理由を区別せず、応答を遅らせる秒数だけを持つ */
enum LoginOutcome {
  case Success(account: AccountDescription)
  case Failed(delaySeconds: Int)
  case Refused(reason: AccountRejection)
}

/** ID/Password の登録とログイン。Turnstile と端末の確認は呼び出し元で済ませる */
final class PasswordAuthentication(accounts: AccountManagementStore & AccountSignInStore, keys: AuthenticationKeys, hashing: PasswordHashing,
  agreementVersion: String, review: AbuseReviewLog = AbuseReviewLog.none) {
  private val registration = new AccountRegistration(accounts, keys)

  private def counted(keyIp: String, kind: String, now: Double)(allowed: AuthRateCounts => Boolean): Future[Unit] =
    accounts.rate(keyIp, kind, now).map(counts => if (!allowed(counts)) throw AuthServiceException(AuthServiceFailure.RateLimited))

  /** 接続元ごとに一日五件まで（strict なら半分）。ユーザー ID とログイン ID は同じ値で登録する */
  def register(loginId: LoginId, password: Password, deviceHash: String, keyIp: String, now: Double, strict: Boolean = false): Future[AccountDescription] =
    for {
      counts <- accounts.rate(keyIp, "register", now)
      _ <- if (AbuseThresholds.registration(counts, strict)) Future.unit
        else review.flag(keyIp, AbuseReason.RegisterBurst, counts, now).recover { case NonFatal(_) => () }
          .flatMap(_ => Future.failed(AuthServiceException(AuthServiceFailure.RateLimited)))
      credential <- hashing.create(password, now).map(_.getOrElse(throw AuthServiceException(AuthServiceFailure.Unavailable)))
      loginHash <- keys.index("login", loginId.value).map(_.getOrElse(throw AuthServiceException(AuthServiceFailure.Unavailable)))
      aid = keys.aid()
      profile = AccountProfile(aid, loginId.value, AccountKind.Password, now, None, now, agreementVersion, loginId = Some(loginId.value))
      account <- registration.register(profile, "login" -> loginHash, Some(credential), now)
      _ <- bind(account, deviceHash, keyIp, now)
    } yield account

  /**
   * 接続元ごとに一時間三十回まで。存在しない ID も固定の Account("dummy") で同じ照合を行い、
   * 不一致・ロック中・未登録を区別しない
   */
  def login(loginId: Option[LoginId], password: String, deviceHash: String, keyIp: String, now: Double): Future[LoginOutcome] =
    for {
      _ <- counted(keyIp, "login", now)(AbuseThresholds.login)
      owner <- loginId.fold(Future.successful(Option.empty[String])) { id =>
        keys.index("login", id.value).flatMap {
          case None => Future.failed(AuthServiceException(AuthServiceFailure.Unavailable))
          case Some(hash) => accounts.lookup("login", hash)
        }
      }
      result <- accounts.verifyPassword(owner.getOrElse(PasswordAuthentication.dummyAid), password, keyIp, now)
      outcome <- (owner, result) match {
        case (Some(_), SignInResult.Verified(account)) => bind(account, deviceHash, keyIp, now).map(_ => LoginOutcome.Success(account))
        case (Some(aid), SignInResult.Unfinished(uid)) if loginId.exists(_.value == uid) =>
          registration.resume(aid, uid, now).flatMap(account => bind(account, deviceHash, keyIp, now).map(_ => LoginOutcome.Success(account)))
            .recover { case AuthServiceException(AuthServiceFailure.Invalid) => LoginOutcome.Failed(0) }
        case (Some(_), SignInResult.Refused(reason)) => Future.successful(LoginOutcome.Refused(reason))
        case (_, SignInResult.Failed(delay)) => Future.successful(LoginOutcome.Failed(delay))
        case _ => Future.successful(LoginOutcome.Failed(0))
      }
    } yield outcome

  /** 端末の記録は観測のためで、失敗してもログイン・登録は成功のままにする */
  private def bind(account: AccountDescription, deviceHash: String, keyIp: String, now: Double): Future[Unit] =
    accounts.bindDevice(account.aid, deviceHash, keyIp, now).recover { case NonFatal(_) => () }
}

object PasswordAuthentication {
  /** 存在しないログイン ID の照合に使う Account DO の名前。通常の aid の書式に合わないので作成されない */
  val dummyAid: String = "dummy"
}
