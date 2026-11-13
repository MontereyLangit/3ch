package io._3ch.bbs.domain.posting.moderation

import io._3ch.bbs.domain.posting.moderation.PostRateLimit.{Entry, ThreadSettings}

/** スレの直近の投稿（新しい順）。スレ単位の連投判定に使う。照合値は短縮して持つ（[[PostRateIdentity.shortened]]） */
final case class ThreadRecentPosts(posts: Vector[Entry]) {
  /** スレ単位の連投判定。identity は全長でも短縮済みでもよい */
  def check(settings: ThreadSettings, identity: PostRateIdentity, now: Double): Option[PostRateRejection] =
    PostRateLimit.checkThread(settings, posts, identity.shortened, now)

  /** 新しい投稿を先頭に足し、RESET 秒より古いものと上限を超えた分を捨てる。足す照合値は短縮する */
  def appended(entry: Entry, settings: ThreadSettings, now: Double): ThreadRecentPosts =
    ThreadRecentPosts((entry.copy(identity = entry.identity.shortened) +: posts).filter(_.timestampSeconds >= now - settings.resetSeconds).take(ThreadRecentPosts.MaxPosts))

  /** 保存済みの全長の照合値も短縮して読む */
  def normalized: ThreadRecentPosts = ThreadRecentPosts(posts.map(entry => entry.copy(identity = entry.identity.shortened)))
}

object ThreadRecentPosts {
  /** 保存する件数の上限。1 件は最大約 85 バイトで、DO の値の上限 128 KiB に収まる */
  val MaxPosts: Int = 1000

  val empty: ThreadRecentPosts = ThreadRecentPosts(Vector.empty)
}
