package io._3ch.bbs.durable.codec

import io._3ch.bbs.domain.posting.ClientIdentity
import io._3ch.bbs.domain.posting.moderation.{ThreadCreationLimit, ThreadCreationRateLimit, ThreadCreationRecord, ThreadCreationRequest, ThreadCreationStatus}
import scala.scalajs.js

/** スレ立て履歴の保存形式と RPC 引数の変換。保存形式は docs/thread-creation-regulation.md を参照。 */
object ThreadCreationCodec {
  def decodeRecord(value: js.Dynamic): ThreadCreationRecord = ThreadCreationRecord(
    key = value.key.asInstanceOf[String],
    timestampSeconds = value.timestampSeconds.asInstanceOf[Double],
    title = value.title.asInstanceOf[String],
    identity = decodeIdentity(value),
    window = value.window.asInstanceOf[Int],
    expiresAt = value.expiresAt.asInstanceOf[Double],
    status = if value.pending.asInstanceOf[Boolean] then ThreadCreationStatus.Pending else ThreadCreationStatus.Completed)

  def encodeRecord(record: ThreadCreationRecord): js.Dynamic = js.Dynamic.literal(
    key = record.key, timestampSeconds = record.timestampSeconds, title = record.title,
    ip = record.identity.ip, userAgent = record.identity.userAgent, networkGroup = record.identity.networkGroup,
    pending = record.status == ThreadCreationStatus.Pending, window = record.window, expiresAt = record.expiresAt)

  /** RPC 引数 `limit` を規制設定と要求へ変換する。 */
  def decodeRequest(limit: js.Dynamic): ThreadCreationLimit = ThreadCreationLimit(
    ThreadCreationRateLimit.Settings(
      limit.tatesugi.asInstanceOf[Int], limit.tatesugiResetSeconds.asInstanceOf[Double],
      limit.junban.asInstanceOf[Int], limit.junbanResetSeconds.asInstanceOf[Double]),
    ThreadCreationRequest(decodeIdentity(limit), limit.title.asInstanceOf[String]))

  /** 規制設定と要求を RPC 引数 limit にする */
  def encodeRequest(limit: ThreadCreationLimit): js.Dynamic = js.Dynamic.literal(
    ip = limit.request.identity.ip, userAgent = limit.request.identity.userAgent, networkGroup = limit.request.identity.networkGroup,
    title = limit.request.title, tatesugi = limit.settings.threadTatesugi, tatesugiResetSeconds = limit.settings.threadTatesugiResetSeconds,
    junban = limit.settings.threadJunban, junbanResetSeconds = limit.settings.threadJunbanResetSeconds)

  private def decodeIdentity(value: js.Dynamic): ClientIdentity =
    ClientIdentity(value.ip.asInstanceOf[String], value.userAgent.asInstanceOf[String], value.networkGroup.asInstanceOf[String])
}
