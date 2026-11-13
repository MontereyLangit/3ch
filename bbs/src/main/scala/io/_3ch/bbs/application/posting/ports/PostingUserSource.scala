package io._3ch.bbs.application.posting.ports

import io._3ch.bbs.domain.posting.PostingUser

import scala.concurrent.Future

/** 投稿者の認証主体。認証を有効にしていない環境で使う */
trait PostingUserSource {
  /** 認証済みならその利用者 */
  def current(): Future[Option[PostingUser]]
}
