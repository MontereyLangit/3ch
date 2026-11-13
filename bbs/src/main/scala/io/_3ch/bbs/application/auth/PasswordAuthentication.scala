package io._3ch.bbs.application.auth

import io._3ch.bbs.application.auth.ports.{AccountManagementStore, AccountSignInStore, AuthenticationKeys, PasswordHashing, SignInResult}
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
  agreementVersion: String) {
  private val registration = new AccountRegistration(accounts, keys)

  private def counted(keyIp: String, kind: String, now: Double)(allowed: AuthRateCounts => Boolean): Future[Unit] =
    accounts.rate(keyIp, kind, now).map(counts => if (!allowed(counts)) throw AuthServiceException(AuthServiceFailure.RateLimited))

  /** 接続元ごとに一日五件まで。ユーザー ID とログイン ID は同じ値で登録する */
  def register(loginId: LoginId, password: Password, deviceHash: String, keyIp: String, now: Double): Future[AccountDescription] =
    for {
      _ <- counted(keyIp, "register", now)(AbuseThresholds.registration)
      credential <- hashing.create(password, now).map(_.getOrElse(throw AuthServiceException(AuthServiceFailure.Unavailable)))
      loginHash <- keys.index("login", loginId.value).map(_.getOrElse(throw AuthServiceException(AuthServiceFailure.Unavailable)))
      aid = keys.aid()
      profile = AccountProfile(aid, loginId.value, AccountKind.Password, now, None, now, agreementVersion, loginId = Some(loginId.value))
      account <- registration.register(profile, "login" -> loginHash, Some(credential), now)
      _ <- bind(account, deviceHash, keyIp, now)
    } yield account

  /**
   * 接続元ごとに一時間三十回まで。存在しない ID も固定の Account("dummy") で同じ照合を行い、
   * 不一致・ロック中・未登録を区別しない。失敗の遅延は接続元の失敗数だけで決める
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
            .recoverWith { case AuthServiceException(AuthServiceFailure.Invalid) => failed(keyIp, now) }
        case (Some(_), SignInResult.Refused(reason)) => Future.successful(LoginOutcome.Refused(reason))
        case _ => failed(keyIp, now)
      }
    } yield outcome

  /** 失敗の遅延は ID の有無にかかわらず、接続元ごとの直近一時間の失敗数から決める */
  private def failed(keyIp: String, now: Double): Future[LoginOutcome] =
    accounts.rate(keyIp, "loginFail", now).map(counts => LoginOutcome.Failed(AbuseThresholds.loginFailureDelay(counts)))

  /** 端末の記録は観測のためで、失敗してもログイン・登録は成功のままにする */
  private def bind(account: AccountDescription, deviceHash: String, keyIp: String, now: Double): Future[Unit] =
    accounts.bindDevice(account.aid, deviceHash, keyIp, now).recover { case NonFatal(_) => () }
}

object PasswordAuthentication {
  /** 存在しないログイン ID の照合に使う Account DO の名前。通常の aid の書式に合わないので作成されない */
  val dummyAid: String = "dummy"
}
