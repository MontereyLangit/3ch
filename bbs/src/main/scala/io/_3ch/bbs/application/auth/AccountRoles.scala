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

  /** actor の役割で target の役割を置き換え、admin の付け外しを索引に反映する。権限の判定は Account DO が行う */
  def assign(actor: AccountDescription, target: String, next: Roles, now: Double): Future[Unit] = for {
    key <- hash(target)
    _ <- accounts.setRoles(target, next, actor.roles, now)
    _ <- if (next.admin) listed(key, target, now) else accounts.remove(kind, key, target)
  } yield ()

  /** 索引に確定済みで載せる。同じ所有者の予約・確定が既にあれば確定し直すだけ */
  private def listed(key: String, aid: String, now: Double): Future[Unit] =
    accounts.reserve(kind, key, aid, now).flatMap(_ => accounts.confirm(kind, key, aid, now))

  /** 有効な admin が居ない間か非常用期間だけ、aid を初回の管理者にする。途中で失敗したら予約を外す */
  def claimFirstAdmin(aid: String, now: Double, breakGlass: Boolean): Future[Unit] = hash(aid).flatMap { key =>
    accounts.claimFirstAdmin(key, aid, now, breakGlass).flatMap { _ =>
      (for {
        _ <- accounts.grantFirstAdmin(aid, now)
        _ <- accounts.confirm(kind, key, aid, now)
      } yield ()).recoverWith { case NonFatal(error) =>
        accounts.remove(kind, key, aid).recover { case NonFatal(_) => () }.flatMap(_ => Future.failed(error))
      }
    }
  }
}
