package io._3ch.bbs.application.auth

import io._3ch.bbs.application.auth.ports.{AccountStore, PostingCredentials}
import io._3ch.bbs.application.posting.PostingRequest
import io._3ch.bbs.application.posting.ports.{PostingAccount, PostingAccounts}
import io._3ch.bbs.domain.account.{AuthenticatedPoster, AuthenticationFailure}
import io._3ch.bbs.domain.posting.{KeyIp, PostingContext, PostingUser}
import io._3ch.common.domain.account.*
import io._3ch.common.domain.posting.MailCommands
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

/** 認証を有効にした環境の投稿者の認証と、成功した投稿のアカウントへの記録 */
final class PostingAccountService(accounts: AccountStore, credentials: PostingCredentials, agreementVersion: String,
  onRecordFailed: Throwable => Unit) extends PostingAccounts {
  import PostingAuthenticationResult.*

  private val authentication = new PostingAuthentication(accounts, credentials, agreementVersion)

  def authenticate(commands: MailCommands, capKey: Option[String], request: PostingRequest, now: Double): Future[Either[AuthenticationFailure, PostingAccount]] =
    authentication.resolve(PostingAuthenticationRequest(commands.token, request.cookie, request.userAgent, request.isMonazilla, request.secure, now, capKey)).flatMap {
      case Anonymous => Future.successful(Right(PostingAccount(None)))
      case LegacyCap => Future.successful(Right(PostingAccount(None, legacyCap = true)))
      case Rejected(reason) => Future.successful(Left(reason))
      case Accepted(poster) =>
        val cookie = poster.session.filter(_ => poster.refreshSession).fold(Future.successful(Option.empty[String]))(credentials.issue(_, now))
        cookie.map(value => Right(PostingAccount(Some(PostingUser(poster.account.aid, poster.account.uid)), poster.account.lv, poster.device,
          sessionCookie = value, authentication = Some(poster), roles = poster.account.roles)))
    }

  def posted(context: PostingContext, request: PostingRequest, account: PostingAccount): Future[Option[String]] = account.authentication match {
    case None => Future.successful(None)
    case Some(poster) =>
      val now = context.unixtime.toDouble
      val identity = context.identity
      val post = AccountPost(now, poster.via, poster.tokenId, poster.capId, bbs = context.form.bbs, key = context.form.key, title = context.title,
        resNum = context.number, excerpt = AccountPost.excerptOf(context.form.message), asn = identity.asn, country = identity.country, region = identity.region,
        uaLabel = if (poster.via == AccountVia.Token) DedicatedBrowserLabel.of(request.userAgent) else "",
        deviceHash = Option(poster.device).filter(_.nonEmpty), shikibetsu = context.shikibetsu,
        network = "AS" + identity.asn + " · " + (if (identity.country == "JP") identity.region else identity.country))
      for {
        keyIp <- credentials.index("key-ip", KeyIp.of(request.connectingIp.getOrElse(""))).map(_.getOrElse(throw AuthServiceException(AuthServiceFailure.Unavailable)))
        updated <- accounts.recordPost(poster.account.aid, post, AccountObservation(poster.device, keyIp))
        cookie <- refreshed(poster, updated, now)
      } yield cookie
  }

  def recordFailed(error: Throwable): Unit = onRecordFailed(error)

  /** 投稿で期限が1日以上延びたか Lv が変わったら、同じ端末とキーの紐付けで Cookie を発行し直す */
  private def refreshed(poster: AuthenticatedPoster, updated: AccountDescription, now: Double): Future[Option[String]] = poster.session match {
    case Some(session) =>
      val expiry = updated.expiresAt.fold(session.expiresAt)(_.toLong)
      if (math.abs(expiry.toDouble - session.expiresAt.toDouble) >= 86400 || updated.lv != session.lv)
        credentials.issue(SessionClaims(updated.aid, updated.uid, updated.generation.toLong, expiry, updated.lv, poster.device, session.binding), now)
      else Future.successful(None)
    case None => Future.successful(None)
  }
}
