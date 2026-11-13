package io._3ch.bbs.application.auth

import io._3ch.bbs.application.auth.ports.{AccountWithdrawalStore, AuthenticationKeys, CapDirectory}
import io._3ch.common.domain.account.{AccountDescription, EmailAddress}
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.control.NonFatal

/**
  * 退会。Account を deleted にしてから login・email・google の索引とキャップの KV を外す。uid と cap の索引と書き込みは残す。
  * 退会済みで同じセッションの世代なら後始末をやり直す。本人の再認証は呼び出し元で済ませる
  */
final class AccountWithdrawal(accounts: AccountWithdrawalStore, directory: CapDirectory, keys: AuthenticationKeys) {
  private def removeIndex(kind: String, value: String, aid: String): Future[Unit] =
    keys.index(kind, value).flatMap(_.fold(Future.unit)(accounts.remove(kind, _, aid))).recover { case NonFatal(_) => () }

  def withdraw(account: AccountDescription, now: Double): Future[Unit] = for {
    withdrawn <- accounts.withdraw(account.aid, account.generation, now)
    _ <- withdrawn.loginId.fold(Future.unit)(removeIndex("login", _, account.aid))
    _ <- withdrawn.email.flatMap(EmailAddress.parse(_).toOption).fold(Future.unit)(email => removeIndex("email", email.canonical, account.aid))
    _ <- withdrawn.googleSubHash.fold(Future.unit)(accounts.remove("google", _, account.aid).recover { case NonFatal(_) => () })
    _ <- Future.traverse(withdrawn.caps)(key => directory.delete(key).recover { case NonFatal(_) => () })
  } yield ()
}
