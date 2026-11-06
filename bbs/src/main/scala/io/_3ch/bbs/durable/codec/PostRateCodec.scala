package io._3ch.bbs.durable.codec

import io._3ch.bbs.domain.posting.moderation.{PostRateCheck, PostRateIdentity, PostRateKey, PostRateLevel, PostRateMeta, PostRateRecord, PostRateSettings, PostRateStatus, ThreadRecentPosts}
import io._3ch.bbs.domain.posting.moderation.PostRateLimit.{BoardSettings, Entry, ThreadSettings}
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*

/** 連投規制の保存形式と RPC 引数の変換。保存形式は docs/post-rate-limit.md を参照 */
object PostRateCodec {
  def decodeRecord(value: js.Dynamic): PostRateRecord = PostRateRecord(
    key = text(value.key),
    timestampSeconds = value.t.asInstanceOf[Double],
    identity = decodeIdentity(value),
    status = if (text(value.status) == "pending") PostRateStatus.Pending else PostRateStatus.Completed)

  def encodeRecord(record: PostRateRecord): js.Dynamic = js.Dynamic.literal(
    key = record.key, t = record.timestampSeconds, ip = record.identity.ip, shiki = record.identity.shiki, aid = record.identity.aid,
    status = if (record.status == PostRateStatus.Pending) "pending" else "completed")

  def decodeMeta(value: js.Dynamic): PostRateMeta = PostRateMeta(value.newest.asInstanceOf[Int], value.oldest.asInstanceOf[Int])

  def encodeMeta(meta: PostRateMeta): js.Dynamic = js.Dynamic.literal(newest = meta.newest, oldest = meta.oldest)

  /** 照合値。無い項目は空 */
  def decodeIdentity(value: js.Dynamic): PostRateIdentity = PostRateIdentity(text(value.ip), text(value.shiki), text(value.aid))

  def encodeIdentity(identity: PostRateIdentity): js.Dynamic = js.Dynamic.literal(ip = identity.ip, shiki = identity.shiki, aid = identity.aid)

  /** Board.reservePost の引数 settings。enforce は拒否するときに真 */
  def encodeBoardSettings(settings: BoardSettings, enforce: Boolean): js.Dynamic = js.Dynamic.literal(
    timecount = settings.timecount, timeclose = settings.timeclose, samba24 = settings.samba24, fr = settings.fr, keys = encodeKeys(settings.keys), enforce = enforce)

  /** fr の無い旧形式は既定の秒数で読む */
  def decodeBoardSettings(value: js.Dynamic): (BoardSettings, Boolean) = (decodeBoard(value, PostRateKey.parse(text(value.keys))), value.enforce.asInstanceOf[Boolean])

  /** Thread.addRes の posting.rateLimit */
  def encodeCheck(check: PostRateCheck): js.Dynamic = js.Dynamic.literal(
    level = check.level.value, ip = check.identity.ip, shiki = check.identity.shiki, aid = check.identity.aid,
    timecount = check.board.timecount, timeclose = check.board.timeclose, samba24 = check.board.samba24, fr = check.board.fr,
    threadRentou = check.thread.count, threadRentouReset = check.thread.resetSeconds, keys = encodeKeys(check.board.keys))

  def decodeCheck(value: js.Dynamic): PostRateCheck = {
    val keys = PostRateKey.parse(text(value.keys))
    PostRateCheck(
      PostRateLevel.parse(Some(text(value.level))),
      decodeIdentity(value),
      decodeBoard(value, keys),
      ThreadSettings(value.threadRentou.asInstanceOf[Int], value.threadRentouReset.asInstanceOf[Double], keys))
  }

  /** Thread DO の recentPosts。無ければ空 */
  def decodeRecent(value: js.Any): ThreadRecentPosts =
    if (value == null || js.isUndefined(value)) ThreadRecentPosts.empty
    else ThreadRecentPosts(value.asInstanceOf[js.Array[js.Dynamic]].toVector.map(v => Entry(v.t.asInstanceOf[Double], decodeIdentity(v))))

  def encodeRecent(recent: ThreadRecentPosts): js.Array[js.Dynamic] = recent.posts.map { entry =>
    js.Dynamic.literal(t = entry.timestampSeconds, ip = entry.identity.ip, shiki = entry.identity.shiki, aid = entry.identity.aid)
  }.toJSArray

  private def decodeBoard(value: js.Dynamic, keys: Set[PostRateKey]): BoardSettings = {
    val fr = if (js.isUndefined(value.fr) || value.fr == null) PostRateSettings.DefaultFrSeconds else value.fr.asInstanceOf[Int]
    BoardSettings(value.timecount.asInstanceOf[Int], value.timeclose.asInstanceOf[Int], value.samba24.asInstanceOf[Int], fr, keys)
  }

  private def encodeKeys(keys: Set[PostRateKey]): String = PostRateKey.values.filter(keys.contains).map(_.name).mkString(",")

  private def text(value: js.Any): String =
    if (value == null || js.isUndefined(value)) "" else js.Dynamic.global.String(value).asInstanceOf[String]
}
