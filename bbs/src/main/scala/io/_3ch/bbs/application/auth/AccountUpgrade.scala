package io._3ch.bbs.application.auth

import io._3ch.bbs.application.auth.ports.{AccountConversionStore, AccountStoreException, AuthenticationKeys, PasswordHashing}
import io._3ch.common.domain.account.*
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.control.NonFatal

/** 方式の移行の結果 */
enum UpgradeOutcome {
  case Converted(account: AccountDescription)
  /** 指定したユーザー ID が使えない */
  case Taken
  /** Google アカウントが別のアカウントに連携済み */
  case LinkedElsewhere
}

/**
  * クライアント ID を ID・パスワードか Google に移す。uid とログイン用の索引を予約し、ログイン用・uid の順に確定してから Account を移す。
  * 旧 uid の索引は残し、確定した新しい uid は失敗しても外さない（同じ aid ならやり直せる）
  */
final class AccountUpgrade(accounts: AccountConversionStore, keys: AuthenticationKeys) {
  private def hash(kind: String, value: String): Future[String] =
    keys.index(kind, value).map(_.getOrElse(throw AuthServiceException(AuthServiceFailure.Unavailable)))

  /** 予約できたか、すでに自分のものなら真。取れたかどうかと、新しく取ったかを返す */
  private def claim(kind: String, hash: String, aid: String, now: Double): Future[(Boolean, Boolean)] =
    accounts.reserve(kind, hash, aid, now).flatMap {
      case true => Future.successful((true, true))
      case false => accounts.lookup(kind, hash).map(owner => (owner.contains(aid), false))
    }

  private def release(kind: String, hash: String, aid: String): Future[Unit] = accounts.remove(kind, hash, aid).recover { case NonFatal(_) => () }

  private def run(account: AccountDescription, uid: LoginId, signIn: (String, String), target: AccountConversion, deviceHash: String, keyIp: String,
    now: Double, taken: UpgradeOutcome): Future[UpgradeOutcome] = Future.unit.flatMap { _ =>
    if (account.kind != AccountKind.Client) throw AccountStoreException(AccountRejection.KindForbidden)
    hash("uid", uid.value).flatMap { uidHash =>
      claim("uid", uidHash, account.aid, now).flatMap {
        case (false, _) => Future.successful(UpgradeOutcome.Taken)
        case (true, uidFresh) =>
          def undo(error: Throwable): Future[UpgradeOutcome] =
            release(signIn._1, signIn._2, account.aid).flatMap(_ => if (uidFresh) release("uid", uidHash, account.aid) else Future.unit).flatMap(_ => Future.failed(error))
          claim(signIn._1, signIn._2, account.aid, now).recoverWith { case NonFatal(error) => undo(error).map(_ => (false, false)) }.flatMap {
            case (false, _) => (if (uidFresh) release("uid", uidHash, account.aid) else Future.unit).map(_ => taken)
            case (true, _) =>
              (for {
                _ <- accounts.confirm(signIn._1, signIn._2, account.aid, now)
                _ <- accounts.confirm("uid", uidHash, account.aid, now)
                converted <- accounts.convert(account.aid, target, uid.value, account.generation, now)
                _ <- accounts.bindDevice(account.aid, deviceHash, keyIp, now).recover { case NonFatal(_) => () }
              } yield UpgradeOutcome.Converted(converted)).recoverWith { case NonFatal(error) => undo(error) }
          }
      }
    }
  }

  /** ID・パスワードへ移す。ユーザー ID はログイン ID と同じ値 */
  def toPassword(account: AccountDescription, id: LoginId, password: Password, hashing: PasswordHashing, deviceHash: String, keyIp: String,
    now: Double): Future[UpgradeOutcome] = for {
    credential <- hashing.create(password, now).map(_.getOrElse(throw AuthServiceException(AuthServiceFailure.Unavailable)))
    loginHash <- hash("login", id.value)
    outcome <- run(account, id, "login" -> loginHash, AccountConversion.ToPassword(credential), deviceHash, keyIp, now, UpgradeOutcome.Taken)
  } yield outcome

  /** Google へ移す。Google アカウントが別のアカウントに連携済みなら移さない */
  def toGoogle(account: AccountDescription, id: LoginId, identity: GoogleIdentity, deviceHash: String, keyIp: String, now: Double): Future[UpgradeOutcome] =
    hash("google", identity.sub).flatMap { subHash =>
      accounts.lookup("google", subHash).flatMap {
        case Some(_) => Future.successful(UpgradeOutcome.LinkedElsewhere)
        case None => run(account, id, "google" -> subHash, AccountConversion.ToGoogle(subHash, identity.email), deviceHash, keyIp, now, UpgradeOutcome.LinkedElsewhere)
      }
    }
}
