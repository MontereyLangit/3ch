package io._3ch.bbs.application.auth

import io._3ch.bbs.application.auth.ports.{AccountManagementStore, AuthenticationKeys}
import io._3ch.common.domain.account.*
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.control.NonFatal

/** 本人認証・キー発行を続けられない理由。文言と HTTP 状態はハンドラで決める */
enum AuthServiceFailure(val code: String) {
  case Invalid extends AuthServiceFailure("auth:invalid")
  case RateLimited extends AuthServiceFailure("auth:rate")
  case Unavailable extends AuthServiceFailure("auth:unavailable")
  /** ユーザー ID が使用済み・予約語・予約中 */
  case Taken extends AuthServiceFailure("auth:id:taken")
}

/** AuthServiceFailure を Future の失敗として運ぶ */
final case class AuthServiceException(failure: AuthServiceFailure) extends RuntimeException(failure.code)

/** 投稿同意・Turnstile・端末申告を確認した後の計数とアカウント登録 */
final class ClientAuthentication(accounts: AccountManagementStore, keys: AuthenticationKeys, agreementVersion: String) {
  /** 既存の本人なら端末と同意だけを更新し、未登録なら UID を予約して作成する */
  def verify(deviceHash: String, keyIp: String, now: Double, existing: Option[AccountDescription] = None): Future[AccountDescription] =
    Future.unit.flatMap { _ =>
      if (!AccountProfile.validTime(now) || !DedicatedBrowserTokens.validHash(keyIp) || agreementVersion.isEmpty ||
        (deviceHash.nonEmpty && !deviceHash.matches("[pgu](?:[0-9a-f]{18}|[0-9a-f]{16})"))) {
        throw AuthServiceException(AuthServiceFailure.Invalid)
      }
      val counted = if (deviceHash.isEmpty) Future.unit
      else accounts.rate(keyIp, "deviceHash:" + deviceHash, now).map(_ => ())
      for {
        _ <- counted
        counts <- accounts.rate(keyIp, "verify", now)
        _ = if (!AbuseThresholds.verify(counts)) throw AuthServiceException(AuthServiceFailure.RateLimited)
        account <- existing match {
          case None => createClient(deviceHash, keyIp, now, 8)
          case Some(account) => for {
            _ <- accounts.bindDevice(account.aid, deviceHash, keyIp, now)
            _ <- accounts.agree(account.aid, agreementVersion, now)
          } yield account.copy(agreedAt = now, agreementVersion = agreementVersion)
        }
      } yield account
    }

  private def createClient(deviceHash: String, keyIp: String, now: Double, attempts: Int): Future[AccountDescription] = {
    if (attempts <= 0) Future.failed(AuthServiceException(AuthServiceFailure.Unavailable))
    else Future.unit.flatMap { _ =>
      val aid = keys.aid()
      val uid = keys.uid()
      keys.index("uid", uid).flatMap {
        case None => Future.failed(AuthServiceException(AuthServiceFailure.Unavailable))
        case Some(hash) => accounts.reserve("uid", hash, aid, now).recoverWith { case NonFatal(error) =>
          accounts.remove("uid", hash, aid).recover { case NonFatal(_) => () }.flatMap(_ => Future.failed(error))
        }.flatMap {
          case false => createClient(deviceHash, keyIp, now, attempts - 1)
          case true =>
            (for {
              created <- accounts.create(AccountProfile(aid, uid, AccountKind.Client, now,
                Some(now + AccountProfile.clientLifetime), now, agreementVersion), now)
              _ <- accounts.bindDevice(aid, deviceHash, keyIp, now)
              _ <- accounts.confirm("uid", hash, aid, now)
            } yield created).recoverWith { case NonFatal(error) =>
              accounts.remove("uid", hash, aid).recover { case NonFatal(_) => () }.flatMap(_ => Future.failed(error))
            }
        }
      }
    }
  }
}
