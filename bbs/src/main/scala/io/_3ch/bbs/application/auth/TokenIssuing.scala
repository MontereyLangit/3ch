package io._3ch.bbs.application.auth

import io._3ch.bbs.application.auth.ports.{AccountManagementStore, AuthenticationKeys}
import io._3ch.common.domain.account.{AccountDescription, AccountProfile, DedicatedBrowserTokens}
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.control.NonFatal

/** 発行直後に一度だけ返す専ブラキー。永続化やログ出力に使わない */
final case class IssuedToken(tokenId: String, token: String)

/** 専ブラキーを予約・発行・確定し、途中で失敗したキーを失効させる */
final class TokenIssuing(accounts: AccountManagementStore, keys: AuthenticationKeys) {
  def issue(account: AccountDescription, now: Double): Future[IssuedToken] = Future.unit.flatMap { _ =>
    val token = keys.token()
    val id = keys.tokenId()
    if (!AccountProfile.validTime(now) || !token.matches("[a-z2-7]{20}") || !DedicatedBrowserTokens.validId(id)) {
      throw AuthServiceException("auth:invalid")
    }
    keys.index("token", token).flatMap {
      case None => Future.failed(AuthServiceException("auth:unavailable"))
      case Some(hash) => accounts.reserve("token", hash, account.aid, now, Some(id)).recoverWith { case NonFatal(error) =>
        accounts.remove("token", hash, account.aid).recover { case NonFatal(_) => () }.flatMap(_ => Future.failed(error))
      }.flatMap {
        case false => Future.failed(AuthServiceException("auth:unavailable"))
        case true =>
          (for {
            _ <- accounts.issueToken(account.aid, id, hash, now)
            _ <- accounts.confirm("token", hash, account.aid, now)
          } yield IssuedToken(id, token)).recoverWith { case NonFatal(error) =>
            for {
              _ <- accounts.revokeToken(account.aid, id, now).recover { case NonFatal(_) => () }
              _ <- accounts.remove("token", hash, account.aid).recover { case NonFatal(_) => () }
              result <- Future.failed[IssuedToken](error)
            } yield result
          }
      }
    }
  }
}
