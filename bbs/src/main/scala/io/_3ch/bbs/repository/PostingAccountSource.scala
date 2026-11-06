package io._3ch.bbs.repository

import io._3ch.bbs.application.auth.{PostingAuthentication, PostingAuthenticationRequest, PostingAuthenticationResult}
import io._3ch.bbs.application.auth.ports.PostingCredentials
import io._3ch.bbs.application.posting.PostingRequest
import io._3ch.bbs.application.posting.ports.{PostingAccount, PostingUserSource}
import io._3ch.bbs.domain.account.AuthenticationFailure
import io._3ch.bbs.domain.posting.{KeyIp, PostingContext, PostingUser}
import io._3ch.bbs.external.cloudflare.WorkerEnv
import io._3ch.bbs.external.web.Request
import io._3ch.bbs.handler.auth.SessionCookies
import io._3ch.bbs.hash.AuthHmac
import io._3ch.common.domain.account.*
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

/** 一回の投稿で認証したアカウントを成功記録と Cookie 更新に使う */
final class PostingAccountSource(request: Request, env: WorkerEnv) extends PostingUserSource {
  private val secret = env.AUTH_SECRET.getOrElse("")
  private val repository = new AccountRepository(env)
  override val configured: Boolean = secret.nonEmpty
  def current(): Future[Option[PostingUser]] = Future.successful(None)
  private val credentials = new PostingCredentials {
    def session(cookieHeader: String, now: Double): Future[Option[SessionClaims]] = SessionCookies.verify(cookieHeader, secret, now)
    def index(kind: String, value: String): Future[Option[String]] = AuthHmac.indexKey(secret, kind, value)
    def ua(userAgent: String): Future[Option[String]] = AuthHmac.uaHash(secret, userAgent)
  }
  override def authenticate(mail: String, capKey: Option[String], posting: PostingRequest, now: Double): Future[Either[AuthenticationFailure, PostingAccount]] = {
    new PostingAuthentication(repository, credentials, env.AGREEMENT_VERSION.getOrElse("")).resolve(
      PostingAuthenticationRequest(mail, posting.cookie, posting.userAgent, posting.isMonazilla, posting.secure, now, capKey)).flatMap {
      case PostingAuthenticationResult.Anonymous => Future.successful(Right(PostingAccount(None)))
      case PostingAuthenticationResult.LegacyCap => Future.successful(Right(PostingAccount(None, legacyCap = true)))
      case PostingAuthenticationResult.Rejected(reason) => Future.successful(Left(reason))
      case accepted: PostingAuthenticationResult.Accepted =>
        val cookie = accepted.session.filter(_ => accepted.refreshSession).fold(Future.successful(Option.empty[String]))(claims => SessionCookies.issue(claims, secret, request.url, now))
        cookie.map(value => Right(PostingAccount(Some(PostingUser(accepted.account.aid, accepted.account.uid)), accepted.account.lv, accepted.device,
          headers = value.map("Set-Cookie" -> _).toSeq, authentication = Some(accepted))))
    }
  }
  override def recordFailed(error: Throwable): Unit = scala.scalajs.js.Dynamic.global.console.warn("account post recording failed")
  override def posted(context: PostingContext, posting: PostingRequest, authenticated: PostingAccount): Future[Seq[(String, String)]] = authenticated.authentication match {
    case None => Future.successful(Nil)
    case Some(value) =>
      val now = context.unixtime.toDouble
      AuthHmac.indexKey(secret, "key-ip", KeyIp.of(posting.connectingIp.getOrElse(""))).flatMap { keyIp =>
        val post = AccountPost(now, value.via, value.tokenId, value.capId, bbs = context.form.bbs, key = context.form.key, title = context.title,
          resNum = context.number, excerpt = context.form.message.replace("<br>", " ").take(40), asn = context.identity.asn,
          country = context.identity.country, region = context.identity.region,
          uaLabel = if (value.via == AccountVia.Token) DedicatedBrowserLabel.of(posting.userAgent) else "",
          deviceHash = Option(value.device).filter(_.nonEmpty), shikibetsu = context.shikibetsu,
          network = "AS" + context.identity.asn + " · " + (if (context.identity.country == "JP") context.identity.region else context.identity.country))
        repository.recordPost(value.account.aid, post, AccountObservation(value.device, keyIp.getOrElse(throw new IllegalStateException("auth:unavailable")))).flatMap { account =>
          val previous = value.session.map(_.expiresAt.toDouble).getOrElse(value.account.expiresAt.getOrElse(now + AccountProfile.clientLifetime))
          if (account.expiresAt.exists(expiry => math.abs(expiry - previous) >= 86400)) {
            val binding = value.session.flatMap(_.binding)
            val claims = SessionClaims(account.aid, account.uid, account.generation.toLong, account.expiresAt.get.toLong, account.lv, value.device, binding)
            SessionCookies.issue(claims, secret, request.url, now).map(_.map("Set-Cookie" -> _).toSeq)
          } else Future.successful(Nil)
        }
      }
  }
}
