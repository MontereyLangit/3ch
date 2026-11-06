package io._3ch.bbs.application.posting.ports

import io._3ch.bbs.application.posting.PostingRequest
import io._3ch.bbs.domain.account.AuthenticationFailure
import io._3ch.bbs.domain.posting.{PostingContext, PostingUser}
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

final case class PostingAccount(user: Option[PostingUser], lv: Int = 0, device: String = "", legacyCap: Boolean = false,
  headers: Seq[(String, String)] = Nil, authentication: Option[io._3ch.bbs.application.auth.PostingAuthenticationResult.Accepted] = None)

/** 投稿者の認証主体と、成功した投稿の記録 */
trait PostingUserSource {
  def current(): Future[Option[PostingUser]]
  def configured: Boolean = false
  def authenticate(mail: String, capKey: Option[String], request: PostingRequest, now: Double): Future[Either[AuthenticationFailure, PostingAccount]] =
    current().map(user => Right(PostingAccount(user)))
  def recordFailed(error: Throwable): Unit = ()
  def posted(context: PostingContext, request: PostingRequest, account: PostingAccount): Future[Seq[(String, String)]] = Future.successful(Nil)
}
