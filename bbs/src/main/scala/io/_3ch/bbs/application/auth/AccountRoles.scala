package io._3ch.bbs.application.auth

import io._3ch.bbs.application.auth.ports.{AccountRoleStore, AuthenticationKeys}
import io._3ch.common.domain.account.{AccountDescription, AccountIndexKind, Roles}
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.control.NonFatal

/** 役割の付け外しと初回の管理者の確保。admin を持つアカウントを role:admin 索引に載せる */
final class AccountRoles(accounts: AccountRoleStore, keys: AuthenticationKeys) {
  private val kind = AccountIndexKind.Admin.code

  private def hash(aid: String): Future[String] =
    keys.index(kind, aid).map(_.getOrElse(throw AuthServiceException(AuthServiceFailure.Unavailable)))

  /**
    * actor の今の役割で target の役割を置き換え、admin の付け外しを索引に反映する。権限の判定は Account DO が行う。
    * admin を足すときは索引が先、外すときは役割が先（索引に載らない admin を作らない）。最後の admin は外せない
    */
  def assign(actor: AccountDescription, target: String, next: Roles, now: Double): Future[Unit] = hash(target).flatMap { key =>
    if (next.admin) grant(key, actor, target, next, now) else strip(key, actor, target, next, now)
  }

  private def grant(key: String, actor: AccountDescription, target: String, next: Roles, now: Double): Future[Unit] =
    listed(key, target, now).flatMap(_ => accounts.setRoles(target, next, actor.aid, now)).recoverWith { case NonFatal(error) =>
      accounts.isAdmin(target, now).transformWith {
        case scala.util.Success(false) => accounts.remove(kind, key, target).recover { case NonFatal(_) => () }.flatMap(_ => Future.failed(error))
        case _ => Future.failed(error)
      }
    }

  private def strip(key: String, actor: AccountDescription, target: String, next: Roles, now: Double): Future[Unit] =
    accounts.isAdmin(target, now).flatMap {
      case false => accounts.setRoles(target, next, actor.aid, now).flatMap(_ => accounts.remove(kind, key, target).recover { case NonFatal(_) => () })
      case true => accounts.surrender(key, target, now).flatMap { _ =>
        accounts.setRoles(target, next, actor.aid, now).recoverWith { case NonFatal(error) =>
          accounts.isAdmin(target, now).flatMap(admin => if (admin) listed(key, target, now) else Future.unit).recover { case NonFatal(_) => () }
            .flatMap(_ => Future.failed(error))
        }
      }
    }

  /** 索引に確定済みで載せる。同じ所有者の予約・確定が既にあれば確定し直すだけ */
  private def listed(key: String, aid: String, now: Double): Future[Unit] =
    accounts.reserve(kind, key, aid, now).flatMap(_ => accounts.confirm(kind, key, aid, now))

  /**
    * 有効な admin が居ない間か非常用期間だけ、aid を初回の管理者にする。途中で止まったら admin の有無を読み直し、
    * admin なら確保を確定し、admin でなければ確保を外す。読み直せなければ確保を残す
    */
  def claimFirstAdmin(aid: String, now: Double, breakGlass: Boolean): Future[Unit] = hash(aid).flatMap { key =>
    accounts.claimFirstAdmin(key, aid, now, breakGlass).flatMap { _ =>
      (for {
        _ <- accounts.grantFirstAdmin(key, aid, now)
        _ <- accounts.confirm(kind, key, aid, now)
      } yield ()).recoverWith { case NonFatal(error) => reconcile(key, aid, now, error) }
    }
  }

  private def reconcile(key: String, aid: String, now: Double, error: Throwable): Future[Unit] =
    accounts.isAdmin(aid, now).transformWith {
      case scala.util.Success(true) => accounts.confirm(kind, key, aid, now).recoverWith { case NonFatal(_) => Future.failed(error) }
      case scala.util.Success(false) => accounts.remove(kind, key, aid).recover { case NonFatal(_) => () }.flatMap(_ => Future.failed(error))
      case scala.util.Failure(_) => Future.failed(error)
    }
}
