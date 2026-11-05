package io._3ch.bbs.domain.posting.moderation

import io._3ch.bbs.domain.posting.ClientIdentity
import io._3ch.bbs.domain.posting.moderation.ThreadCreationRateLimit.{Entry, Reason, Settings}

/** スレ立て要求。識別情報とスレタイはソルト付きハッシュ値で、平文は持たない。 */
final case class ThreadCreationRequest(identity: ClientIdentity, title: String)

/** スレ立て規制の設定と、この投稿の照合値（ハッシュ済み） */
final case class ThreadCreationLimit(settings: ThreadCreationRateLimit.Settings, request: ThreadCreationRequest)

/** スレ立て予約の拒否理由。RPC では [[code]] の文字列で返す。 */
enum ThreadCreationRejection {
  /** 同一 IP・同一スレタイによる規制 */
  case Narrow(reason: Reason)
  /** 同一 UA・同一ネットワークグループによる規制 */
  case Broad(reason: Reason)
  /** 履歴の保存件数の上限に達した */
  case Capacity

  /** RPC とエラーコードに使う文字列 */
  def code: String = this match {
    case Narrow(reason) => s"thread-creation:narrow:${ThreadCreationRejection.reasonCode(reason)}"
    case Broad(reason) => s"thread-creation:broad:${ThreadCreationRejection.reasonCode(reason)}"
    case Capacity => "thread-creation:capacity"
  }
}

object ThreadCreationRejection {
  private def reasonCode(reason: Reason): String = reason match {
    case Reason.Ip => "ip"
    case Reason.Title => "title"
    case Reason.UserAgent => "user-agent"
    case Reason.NetworkGroup => "network-group"
  }

  private val all: Map[String, ThreadCreationRejection] =
    (Capacity +: Reason.values.toSeq.flatMap(reason => Seq(Narrow(reason), Broad(reason)))).map(value => value.code -> value).toMap

  /** code の文字列から拒否理由を戻す。知らない文字列なら None */
  def fromCode(code: String): Option[ThreadCreationRejection] = all.get(code)
}

/** 予約の状態。`Pending` は dat 書き込み前、`Completed` は確定済み。 */
enum ThreadCreationStatus {
  case Pending, Completed
}

/**
  * スレ立て履歴の1件。`expiresAt` は予約の有効期限（UNIX秒）で、確定後も保存形式のため保持する。
  * `window` は記録時点の件数窓（tatesugi / junban の大きい方）。
  */
final case class ThreadCreationRecord(
  key: String,
  timestampSeconds: Double,
  title: String,
  identity: ClientIdentity,
  window: Int,
  expiresAt: Double,
  status: ThreadCreationStatus
) {
  /** 期限内の予約か */
  def isActivePending(now: Double): Boolean = status == ThreadCreationStatus.Pending && now <= expiresAt

  /** 判定に使う履歴の 1 件にする */
  def toEntry: Entry = Entry(timestampSeconds, title, identity)
}

/**
  * 板ごとのスレ立て履歴（新しい順）。状態遷移は純粋関数で、現在時刻は呼び出し側が渡す。
  * 仕様は docs/thread-creation-regulation.md の「保存・同時実行」を参照。
  */
final case class ThreadCreationHistory(records: Vector[ThreadCreationRecord]) {
  import ThreadCreationStatus.*

  /** 有効な予約は常に残し、それ以外は最大 window 件まで残す。 */
  def retained(now: Double): ThreadCreationHistory = {
    val window = records.map(_.window).maxOption.getOrElse(0)
    var completed = 0
    ThreadCreationHistory(records.filter { record =>
      if record.isActivePending(now) then true else {
        completed += 1
        completed <= window
      }
    })
  }

  /** 判定に通れば新しい予約を先頭に加えた履歴を返す。容量超過は他の理由より優先する。 */
  def reserve(
    key: String,
    request: ThreadCreationRequest,
    settings: Settings,
    now: Double,
    capacity: Int
  ): Either[ThreadCreationRejection, ThreadCreationHistory] = {
    val current = retained(now)
    if current.records.size >= capacity then Left(ThreadCreationRejection.Capacity)
    else ThreadCreationRateLimit.checkWithReservations(settings, current.records, request, now) match {
      case Some(reason @ (Reason.Ip | Reason.Title)) => Left(ThreadCreationRejection.Narrow(reason))
      case Some(reason) => Left(ThreadCreationRejection.Broad(reason))
      case None =>
        val activeReset = math.max(
          if settings.threadTatesugi > 0 then settings.threadTatesugiResetSeconds else 0,
          if settings.threadJunban > 0 then settings.threadJunbanResetSeconds else 0)
        val record = ThreadCreationRecord(key, now, request.title, request.identity,
          math.max(settings.threadTatesugi, settings.threadJunban), now + math.min(120, activeReset), Pending)
        Right(ThreadCreationHistory(record +: current.records).retained(now))
    }
  }

  /** 同じ key の最初の記録を確定済みにする。 */
  def complete(key: String, now: Double): ThreadCreationHistory = {
    val index = records.indexWhere(_.key == key)
    val updated = if index < 0 then records else records.updated(index, records(index).copy(status = Completed))
    ThreadCreationHistory(updated).retained(now)
  }

  /** 同じ key の予約中の記録を取り消す。確定済みは消さない。 */
  def cancel(key: String, now: Double): ThreadCreationHistory =
    ThreadCreationHistory(records.filterNot(record => record.key == key && record.status == Pending)).retained(now)
}
