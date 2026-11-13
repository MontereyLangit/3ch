package io._3ch.bbs.application.auth

import io._3ch.bbs.application.auth.ports.{AccountManagementStore, AccountSignInStore, AuthenticationKeys, SignInResult}
import io._3ch.common.domain.account.*
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.control.NonFatal

/** Google でのログイン・登録の結果 */
enum GoogleOutcome {
  case Success(account: AccountDescription)
  /** 未登録の Google アカウントで、ユーザー ID か同意が無い */
  case Registration
  /** 指定したユーザー ID が使えない */
  case Taken
  case Refused(reason: AccountRejection)
}

/** 検証済みの Google アカウントでログインし、未登録なら入力されたユーザー ID で登録する */
final class GoogleAuthentication(accounts: AccountManagementStore & AccountSignInStore, keys: AuthenticationKeys, agreementVersion: String) {
  private val registration = new AccountRegistration(accounts, keys)
  private val stale = new StaleIndex(accounts)

  /** requestedUid は登録用に検証済みの ID。agreed は開始時に同意を確認したか */
  def signIn(identity: GoogleIdentity, requestedUid: Option[LoginId], agreed: Boolean, deviceHash: String, keyIp: String, now: Double): Future[GoogleOutcome] =
    keys.index("google", identity.sub).map(_.getOrElse(throw AuthServiceException(AuthServiceFailure.Unavailable))).flatMap { subHash =>
      accounts.lookup("google", subHash).flatMap {
        case Some(aid) => accounts.describeGoogle(aid, subHash, now).flatMap {
          case SignInResult.Verified(account) => success(account, deviceHash, keyIp, now)
          case SignInResult.Unfinished(uid) => registration.resume(aid, uid, now).flatMap(success(_, deviceHash, keyIp, now))
          case SignInResult.Refused(AccountRejection.Deleted) => stale.reclaim("google", subHash, subHash, aid).flatMap(_ => unregistered(identity, subHash, requestedUid, agreed, deviceHash, keyIp, now))
          case SignInResult.Refused(reason) => Future.successful(GoogleOutcome.Refused(reason))
          case SignInResult.Failed => Future.failed(AuthServiceException(AuthServiceFailure.Unavailable))
        }
        case None => unregistered(identity, subHash, requestedUid, agreed, deviceHash, keyIp, now)
      }
    }

  /** 退会したアカウントの索引は解放済みなので、未登録と同じに扱う */
  private def unregistered(identity: GoogleIdentity, subHash: String, requestedUid: Option[LoginId], agreed: Boolean, deviceHash: String, keyIp: String,
    now: Double): Future[GoogleOutcome] = requestedUid.filter(_ => agreed) match {
    case None => Future.successful(GoogleOutcome.Registration)
    case Some(uid) =>
      val profile = AccountProfile(keys.aid(), uid.value, AccountKind.Google, now, None, now, agreementVersion,
        email = identity.email, googleSubHash = Some(subHash), emailVerifiedAt = Some(now))
      registration.register(profile, "google" -> subHash, None, now).flatMap(success(_, deviceHash, keyIp, now))
        .recover { case AuthServiceException(AuthServiceFailure.Taken) => GoogleOutcome.Taken }
  }

  private def success(account: AccountDescription, deviceHash: String, keyIp: String, now: Double): Future[GoogleOutcome] =
    accounts.bindDevice(account.aid, deviceHash, keyIp, now).recover { case NonFatal(_) => () }.map(_ => GoogleOutcome.Success(account))
}
