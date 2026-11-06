package io._3ch.bbs.domain.posting.moderation

import io._3ch.bbs.domain.posting.moderation.PostRateLimit.{Entry, ThreadSettings}

/** スレの直近の投稿（新しい順）。スレ単位の連投判定に使う */
final case class ThreadRecentPosts(posts: Vector[Entry]) {
  /** スレ単位の連投判定 */
  def check(settings: ThreadSettings, identity: PostRateIdentity, now: Double): Option[PostRateRejection] =
    PostRateLimit.checkThread(settings, posts, identity, now)

  /** 新しい投稿を先頭に足し、RESET 秒より古いものと上限を超えた分を捨てる */
  def appended(entry: Entry, settings: ThreadSettings, now: Double): ThreadRecentPosts =
    ThreadRecentPosts((entry +: posts).filter(_.timestampSeconds >= now - settings.resetSeconds).take(ThreadRecentPosts.MaxPosts))
}

object ThreadRecentPosts {
  /** 保存する件数の上限 */
  val MaxPosts: Int = 1000

  val empty: ThreadRecentPosts = ThreadRecentPosts(Vector.empty)
}
