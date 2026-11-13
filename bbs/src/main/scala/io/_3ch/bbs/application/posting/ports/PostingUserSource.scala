package io._3ch.bbs.application.posting.ports

import io._3ch.bbs.domain.posting.PostingUser

import scala.concurrent.Future

/** 投稿者の認証主体 */
trait PostingUserSource {
  /** 認証済みならその利用者 */
  def current(): Future[Option[PostingUser]]
}
