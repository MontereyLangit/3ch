package io._3ch.bbs.domain.posting.moderation

import io._3ch.bbs.domain.posting.ClientIdentity

/**
  * スレ立て規制の判定。通常は同一IP・同一スレタイ、広域は同一UA・同一ネットワークグループを拒否する。
  * 詳細は docs/thread-creation-regulation.md「判定」。
  */
object ThreadCreationRateLimit {
  /** 狭域（tatesugi）と広域（junban）の、見る件数と秒数 */
  final case class Settings(
    threadTatesugi: Int,
    threadTatesugiResetSeconds: Double,
    threadJunban: Int,
    threadJunbanResetSeconds: Double
  )
  /** 判定に使うスレ立て履歴の 1 件。識別情報とスレタイはハッシュ済み */
  final case class Entry(
    timestampSeconds: Double,
    title: String,
    identity: ClientIdentity
  )

  /**
    * 規制理由（対象）
    */
  enum Reason {
    case Ip, Title, UserAgent, NetworkGroup
  }

  /**
    * 狭域スレ立て規制と広域スレ立て規制の判定
    *
    * @param settings スレ立て規制設定
    * @param history スレ立てエントリ履歴
    * @param identity 投稿者の識別情報、IPやASN・端末情報など
    * @param title スレタイ
    * @param nowSeconds スレ立てUNIXTIME
    * @return 問題ない場合None / 規制対象なら規制理由（対象）
    */
  def check(settings: Settings, history: Seq[Entry], identity: ClientIdentity, title: String, nowSeconds: Double): Option[Reason] = {
    def isSame(left: String, right: String): Boolean = left.nonEmpty && left == right
    def window(count: Int, resetSeconds: Double): Seq[Entry] =
      history.take(count.max(0)).filter(_.timestampSeconds >= nowSeconds - resetSeconds)

    val tatesugi = window(settings.threadTatesugi, settings.threadTatesugiResetSeconds)
    val junban = window(settings.threadJunban, settings.threadJunbanResetSeconds)
    val matched: Seq[Reason] = tatesugi.flatMap { entry =>
      Option.when(isSame(identity.ip, entry.identity.ip))(Reason.Ip) ++ Option.when(isSame(title, entry.title))(Reason.Title)
    } ++ junban.flatMap { entry =>
      Option.when(isSame(identity.userAgent, entry.identity.userAgent))(Reason.UserAgent) ++
        Option.when(isSame(identity.networkGroup, entry.identity.networkGroup))(Reason.NetworkGroup)
    }
    // 履歴の新旧によらず Reason の宣言順で最優先の理由を返す
    Reason.values.find(matched.contains)
  }

  /**
    * 確定済み履歴は件数窓ごと全体で、有効な予約は1件ずつ個別に判定し、最優先の規制理由を返す。
    * 優先順は [[Reason]] の宣言順。
    */
  def checkWithReservations(settings: Settings, records: Seq[ThreadCreationRecord], request: ThreadCreationRequest, nowSeconds: Double): Option[Reason] = {
    val (pending, completed) = records.partition(_.isActivePending(nowSeconds))
    def check(entries: Seq[Entry]): Option[Reason] = this.check(settings, entries, request.identity, request.title, nowSeconds)
    val reasons = check(completed.map(_.toEntry)).toSeq ++ pending.flatMap(record => check(Seq(record.toEntry)))
    Reason.values.find(reasons.contains)
  }
}
