package io._3ch.bbs.application.auth

import io._3ch.bbs.application.auth.ports.{AccountManagementStore, AccountSignInStore, AccountStoreException, AuthenticationKeys}
import io._3ch.common.domain.account.*
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.control.NonFatal

/** ユーザー ID と方式別の索引を予約し、ログイン用の索引・uid の順に確定してから利用を開始する */
final class AccountRegistration(accounts: AccountManagementStore & AccountSignInStore, keys: AuthenticationKeys) {
  private def hash(kind: String, value: String): Future[String] =
    keys.index(kind, value).map(_.getOrElse(throw AuthServiceException(AuthServiceFailure.Unavailable)))

  private def release(kind: String, hash: String, aid: String): Future[Unit] =
    accounts.remove(kind, hash, aid).recover { case NonFatal(_) => () }

  private def releaseBoth(signIn: (String, String), uidHash: String, aid: String): Future[Unit] =
    release(signIn._1, signIn._2, aid).flatMap(_ => release("uid", uidHash, aid))

  /** 新しい aid でアカウントを作る。signIn は (索引の種別, ハッシュ)。ID が使われていれば Taken */
  def register(profile: AccountProfile, signIn: (String, String), credential: Option[PasswordCredential], now: Double): Future[AccountDescription] =
    hash("uid", profile.uid).flatMap { uidHash =>
      val aid = profile.aid
      def failed[A](error: Throwable): Future[A] = releaseBoth(signIn, uidHash, aid).flatMap(_ => Future.failed(error))
      def owned(kind: String, hash: String): Future[Unit] = reserve(kind, hash, aid, now).recoverWith {
        case error @ AuthServiceException(AuthServiceFailure.Taken) => Future.failed(error)
        case NonFatal(error) => release(kind, hash, aid).flatMap(_ => Future.failed(error))
      }
      owned("uid", uidHash).flatMap { _ =>
        owned(signIn._1, signIn._2).recoverWith { case NonFatal(error) => release("uid", uidHash, aid).flatMap(_ => Future.failed(error)) }
      }.flatMap { _ =>
        (for {
          _ <- accounts.create(profile, now, credential)
          _ <- accounts.confirm(signIn._1, signIn._2, aid, now)
        } yield ()).recoverWith { case NonFatal(error) => failed(error) }
      }.flatMap { _ =>
        accounts.confirm("uid", uidHash, aid, now).recoverWith {
          case error: AccountStoreException => failed(error)
          case NonFatal(error) => accounts.lookup("uid", uidHash).flatMap {
            case Some(owner) if owner == aid => Future.unit
            case _ => failed(error)
          }
        }
      }.flatMap(_ => accounts.activate(aid, now))
    }

  /** 確定が途中で止まったアカウントを、本人の確認後に再開する。uid の確定は同じ aid の予約に限る */
  def resume(aid: String, uid: String, now: Double): Future[AccountDescription] =
    hash("uid", uid).flatMap { uidHash =>
      accounts.lookup("uid", uidHash).flatMap {
        case Some(owner) if owner == aid => Future.unit
        case Some(_) => Future.failed(AuthServiceException(AuthServiceFailure.Invalid))
        case None => accounts.confirm("uid", uidHash, aid, now)
      }
    }.flatMap(_ => accounts.activate(aid, now))

  private def reserve(kind: String, hash: String, aid: String, now: Double): Future[Unit] =
    accounts.reserve(kind, hash, aid, now).map(reserved => if (!reserved) throw AuthServiceException(AuthServiceFailure.Taken))
}
