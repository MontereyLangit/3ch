package io._3ch.bbs.application.auth

import io._3ch.bbs.application.auth.ports.{AccountWithdrawalStore, AuthenticationKeys, CapDirectory}
import io._3ch.common.domain.account.{AccountDescription, AccountIndexKind, EmailAddress}
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.control.NonFatal

/**
  * 退会。Account を deleted にしてから login・email・google の索引とキャップの KV・cap 索引を外す。uid の索引と書き込みは残す。
  * 本人の再認証は呼び出し元で済ませる
  */
final class AccountWithdrawal(accounts: AccountWithdrawalStore, directory: CapDirectory, keys: AuthenticationKeys) {
  private def removeIndex(kind: String, value: String, aid: String): Future[Unit] =
    keys.index(kind, value).flatMap(_.fold(Future.unit)(accounts.remove(kind, _, aid))).recover { case NonFatal(_) => () }

  /** キャップは KV から消せたときだけ索引からも外す。索引が残る間は退会したアカウントとして照合を拒否する */
  private def retire(capKey: String, aid: String): Future[Unit] =
    directory.delete(capKey).flatMap(_ => removeIndex(AccountIndexKind.Cap.code, capKey, aid)).recover { case NonFatal(_) => () }

  def withdraw(account: AccountDescription, now: Double): Future[Unit] = for {
    withdrawn <- accounts.withdraw(account.aid, account.generation, now)
    _ <- withdrawn.loginId.fold(Future.unit)(removeIndex("login", _, account.aid))
    _ <- withdrawn.email.flatMap(EmailAddress.parse(_).toOption).fold(Future.unit)(email => removeIndex("email", email.canonical, account.aid))
    _ <- withdrawn.googleSubHash.fold(Future.unit)(accounts.remove("google", _, account.aid).recover { case NonFatal(_) => () })
    _ <- Future.traverse(withdrawn.caps)(retire(_, account.aid))
  } yield ()
}
