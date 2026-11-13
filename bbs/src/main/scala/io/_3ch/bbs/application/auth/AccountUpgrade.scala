package io._3ch.bbs.application.auth

import io._3ch.bbs.application.auth.ports.{AbuseReviewLog, AccountConversionStore, AccountStoreException, AuthenticationKeys, PasswordHashing}
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
  * 失敗したときは、この試行で新しく取った確定前の予約だけを取り消す。確定した索引（ほかの試行や移行済みのアカウントのものを含む）は外さず、
  * 旧 uid の索引も残す。同じ aid ならやり直せる
  */
final class AccountUpgrade(accounts: AccountConversionStore, keys: AuthenticationKeys, review: AbuseReviewLog = AbuseReviewLog.none) {
  private val stale = new StaleIndex(accounts)

  private def hash(kind: String, value: String): Future[String] =
    keys.index(kind, value).map(_.getOrElse(throw AuthServiceException(AuthServiceFailure.Unavailable)))

  /** 予約できたか、すでに自分のものなら真。取れたかどうかと、新しく取ったかを返す。持ち主が値を持たない確定済みの索引は解放して取り直す */
  private def claim(kind: String, value: String, hash: String, aid: String, now: Double): Future[(Boolean, Boolean)] =
    accounts.reserve(kind, hash, aid, now).flatMap {
      case true => Future.successful((true, true))
      case false => accounts.lookup(kind, hash).flatMap {
        case Some(owner) if owner != aid && kind != "uid" =>
          stale.reclaim(kind, value, hash, owner).flatMap(freed => if (freed) accounts.reserve(kind, hash, aid, now).map(ok => (ok, ok)) else Future.successful((false, false)))
        case owner => Future.successful((owner.contains(aid), false))
      }
    }

  private def release(kind: String, hash: String, aid: String): Future[Unit] = accounts.releaseReservation(kind, hash, aid).recover { case NonFatal(_) => () }

  private def run(account: AccountDescription, uid: LoginId, signIn: (String, String, String), target: AccountConversion, deviceHash: String, keyIp: String,
    now: Double, taken: UpgradeOutcome): Future[UpgradeOutcome] = Future.unit.flatMap { _ =>
    if (account.kind != AccountKind.Client) throw AccountStoreException(AccountRejection.KindForbidden)
    val (signKind, signValue, signHash) = signIn
    hash("uid", uid.value).flatMap { uidHash =>
      claim("uid", uid.value, uidHash, account.aid, now).flatMap {
        case (false, _) => Future.successful(UpgradeOutcome.Taken)
        case (true, uidFresh) =>
          def releaseUid(): Future[Unit] = if (uidFresh) release("uid", uidHash, account.aid) else Future.unit
          def undo(error: Throwable, signFresh: Boolean): Future[UpgradeOutcome] =
            (if (signFresh) release(signKind, signHash, account.aid) else Future.unit).flatMap(_ => releaseUid()).flatMap(_ => Future.failed(error))
          claim(signKind, signValue, signHash, account.aid, now).recoverWith { case NonFatal(error) => undo(error, signFresh = true).map(_ => (false, false)) }.flatMap {
            case (false, _) => releaseUid().map(_ => taken)
            case (true, signFresh) =>
              (for {
                _ <- accounts.confirm(signKind, signHash, account.aid, now)
                _ <- accounts.confirm("uid", uidHash, account.aid, now)
                converted <- accounts.convert(account.aid, target, uid.value, account.generation, now)
                _ <- accounts.bindDevice(account.aid, deviceHash, keyIp, now).recover { case NonFatal(_) => () }
              } yield UpgradeOutcome.Converted(converted)).recoverWith { case NonFatal(error) => undo(error, signFresh) }
          }
      }
    }
  }

  /** ID・パスワードへ移す。ユーザー ID はログイン ID と同じ値。登録と同じ接続元ごとの上限（strict なら半分）を数える */
  def toPassword(account: AccountDescription, id: LoginId, password: Password, hashing: PasswordHashing, deviceHash: String, keyIp: String,
    now: Double, strict: Boolean = false): Future[UpgradeOutcome] = for {
    _ <- RegistrationRate.check(accounts.rate, review, keyIp, now, strict)
    credential <- hashing.create(password, now).map(_.getOrElse(throw AuthServiceException(AuthServiceFailure.Unavailable)))
    loginHash <- hash("login", id.value)
    outcome <- run(account, id, ("login", id.value, loginHash), AccountConversion.ToPassword(credential), deviceHash, keyIp, now, UpgradeOutcome.Taken)
  } yield outcome

  /** Google へ移す。Google アカウントが別のアカウントに連携済みなら移さない */
  def toGoogle(account: AccountDescription, id: LoginId, identity: GoogleIdentity, deviceHash: String, keyIp: String, now: Double,
    strict: Boolean = false): Future[UpgradeOutcome] =
    RegistrationRate.check(accounts.rate, review, keyIp, now, strict).flatMap(_ => hash("google", identity.sub)).flatMap { subHash =>
      accounts.lookup("google", subHash).flatMap {
        case Some(owner) => stale.reclaim("google", subHash, subHash, owner).flatMap(freed =>
          if (freed) run(account, id, ("google", subHash, subHash), AccountConversion.ToGoogle(subHash, identity.email), deviceHash, keyIp, now, UpgradeOutcome.LinkedElsewhere)
          else Future.successful(UpgradeOutcome.LinkedElsewhere))
        case None => run(account, id, ("google", subHash, subHash), AccountConversion.ToGoogle(subHash, identity.email), deviceHash, keyIp, now, UpgradeOutcome.LinkedElsewhere)
      }
    }
}
