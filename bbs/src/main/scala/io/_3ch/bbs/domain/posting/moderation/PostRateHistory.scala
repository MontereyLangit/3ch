package io._3ch.bbs.domain.posting.moderation

import io._3ch.bbs.domain.posting.moderation.PostRateLimit.{BoardSettings, Entry}

/** 板の連投履歴の予約の状態。Pending は dat 書き込み前、Completed は確定済み */
enum PostRateStatus {
  case Pending, Completed
}

/** 板の連投履歴の 1 件。key は予約 ID（スレキーとランダム値） */
final case class PostRateRecord(key: String, timestampSeconds: Double, identity: PostRateIdentity, status: PostRateStatus) {
  /** 期限内の予約か */
  def isActivePending(now: Double): Boolean =
    status == PostRateStatus.Pending && now <= timestampSeconds + PostRateHistory.ReservationSeconds

  /** 判定に使う 1 件にする */
  def toEntry: Entry = Entry(timestampSeconds, identity)
}

/** 履歴を保存しているページの範囲。newest・oldest はページの通し番号 */
final case class PostRateMeta(newest: Int, oldest: Int)

/** 履歴の 1 ページ。records は古い順 */
final case class PostRatePage(number: Int, records: Vector[PostRateRecord])

/** 予約の結果。saved は保存する履歴で、拒否して記録しないときは None */
final case class PostRateReservation(rejection: Option[PostRateRejection], saved: Option[PostRateHistory])

/**
  * 読み込んだ範囲の板の連投履歴。pages は最新ページから古い方へ連続して並び、少なくとも最新ページを含む。
  * 書き込むのは最新ページとメタだけで、範囲から外れた古いページは消さずに上書きを待つ（docs/post-rate-limit.md）
  */
final case class PostRateHistory(meta: PostRateMeta, pages: Vector[PostRatePage]) {
  import PostRateHistory.*

  /** 読み込んだ記録（新しい順） */
  def records: Vector[PostRateRecord] = pages.flatMap(_.records.reverseIterator)

  /** まだ読んでいない次に古いページの番号。範囲を全部読んでいれば None */
  def older: Option[Int] = {
    val next = pages.lastOption.fold(meta.newest)(_.number - 1)
    Option.when(next >= meta.oldest)(next)
  }

  /** 読み込んだページを古い側に足す */
  def withOlder(page: PostRatePage): PostRateHistory = copy(pages = pages :+ page)

  /** 板単位の判定のために次に読むページ。直近 timecount 件と Samba24・広域の秒数の範囲を読み終えていれば None */
  def nextPage(settings: BoardSettings, now: Double): Option[Int] = older.filter { _ =>
    val loaded = records
    pages.isEmpty || loaded.size < math.max(settings.timecount, 1) ||
      (settings.windowSeconds > 0 && loaded.lastOption.exists(_.timestampSeconds >= now - settings.windowSeconds))
  }

  /**
    * 判定して予約を最新ページに追記する。enforce が偽（試行モード）なら拒否理由があっても記録する。
    * 容量では拒否せず、上限を超えたら古いページから範囲を外す
    */
  def reserve(key: String, identity: PostRateIdentity, settings: BoardSettings, enforce: Boolean, now: Double): PostRateReservation = {
    val rejection = PostRateLimit.checkBoard(settings, records.map(_.toEntry), identity, now)
    if (rejection.isDefined && enforce) PostRateReservation(rejection, None)
    else PostRateReservation(rejection, Some(appended(PostRateRecord(key, now, identity, PostRateStatus.Pending)).retained(settings, now)))
  }

  private def appended(record: PostRateRecord): PostRateHistory = pages.headOption.filter(_.records.size < PageSize) match {
    case Some(page) => copy(pages = page.copy(records = page.records :+ record) +: pages.tail)
    case None =>
      val number = meta.newest + 1
      PostRateHistory(PostRateMeta(number, math.max(meta.oldest, number - MaxPages + 1)), PostRatePage(number, Vector(record)) +: pages)
  }

  /** 直近 max(timecount, 1) 件・Samba24 か広域の秒数以内・期限内の予約のどれかを含むページまでを残し、それより古いページを範囲から外す */
  def retained(settings: BoardSettings, now: Double): PostRateHistory = {
    val count = math.max(settings.timecount, 1)
    val kept = pages.flatMap(page => page.records.reverseIterator.map(page.number -> _)).zipWithIndex.collect {
      case ((number, record), index)
        if index < count || (settings.windowSeconds > 0 && record.timestampSeconds >= now - settings.windowSeconds) || record.isActivePending(now) => number
    }
    val oldest = math.max(meta.oldest, kept.minOption.getOrElse(meta.newest))
    PostRateHistory(meta.copy(oldest = oldest), pages.filter(_.number >= oldest))
  }

  /** key の記録を含む読み込み済みのページ */
  def pageOf(key: String): Option[PostRatePage] = pages.find(_.records.exists(_.key == key))

  /** key の記録を確定済みにしたページ。読み込んだ範囲に無ければ None */
  def complete(key: String): Option[PostRatePage] = pageOf(key).map { page =>
    page.copy(records = page.records.map(record => if (record.key == key) record.copy(status = PostRateStatus.Completed) else record))
  }

  /** key の予約中の記録を除いたページ。確定済みは消さない。予約中の記録が無ければ None */
  def cancel(key: String): Option[PostRatePage] = {
    def target(record: PostRateRecord): Boolean = record.key == key && record.status == PostRateStatus.Pending
    pages.find(_.records.exists(target)).map(page => page.copy(records = page.records.filterNot(target)))
  }
}

object PostRateHistory {
  /** 1 ページの件数 */
  val PageSize: Int = 64
  /** 保存するページ数の上限（1,024 件） */
  val MaxPages: Int = 16
  /** 予約の有効期限（秒） */
  val ReservationSeconds: Double = 120

  /** 何も保存していない板のメタ */
  val InitialMeta: PostRateMeta = PostRateMeta(0, 0)
}
