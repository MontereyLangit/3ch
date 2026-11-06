package io._3ch.bbs.domain.posting.moderation

/** 連投規制の照合キー。name は BBS_RENTOU_KEYS に書く値 */
enum PostRateKey(val name: String) {
  case Ip extends PostRateKey("ip")
  case Shiki extends PostRateKey("shiki")
  case Account extends PostRateKey("aid")
}

object PostRateKey {
  /** 既定の照合キー（ip,shiki,aid） */
  val defaults: Set[PostRateKey] = values.toSet

  /** Samba24 の照合キー。BBS_RENTOU_KEYS によらず固定 */
  val samba24: Set[PostRateKey] = Set(Ip, Account)

  /** カンマ区切りの値を読む。知らない値（廃止した id など）は無視し、1 つも残らなければ既定 */
  def parse(text: String): Set[PostRateKey] = {
    val keys = text.split(",", -1).map(_.trim).flatMap(name => values.find(_.name == name)).toSet
    if (keys.isEmpty) defaults else keys
  }
}

/** 投稿者の照合値。どれもソルト付きハッシュで、空の値は照合しない */
final case class PostRateIdentity(ip: String = "", shiki: String = "", aid: String = "") {
  /** キーに対応する値 */
  def value(key: PostRateKey): String = key match {
    case PostRateKey.Ip => ip
    case PostRateKey.Shiki => shiki
    case PostRateKey.Account => aid
  }

  /** keys のどれか 1 つでも空でなく一致すれば同一の投稿者 */
  def sameAs(other: PostRateIdentity, keys: Set[PostRateKey]): Boolean =
    keys.exists(key => value(key).nonEmpty && value(key) == other.value(key))
}

/** 連投規制の拒否理由。RPC では code の文字列で返す */
enum PostRateRejection {
  /** ごんたくれ（timecount / timeclose） */
  case Gontakure
  /** Samba24。remainingSeconds は文言に出す残り秒数 */
  case Samba24(remainingSeconds: Int)
  /** 識別子による広域連投規制（BBS_FR_SECOND）。remainingSeconds は文言に出す残り秒数 */
  case Fr(remainingSeconds: Int)
  /** スレ単位の連投（BBS_THREAD_RENTOU） */
  case ThreadRentou

  /** RPC とエラーコードに使う文字列。Samba24 と Fr は末尾に残り秒数を付ける */
  def code: String = this match {
    case Gontakure => PostRateRejection.GontakureCode
    case Samba24(seconds) => s"${PostRateRejection.Samba24Prefix}$seconds"
    case Fr(seconds) => s"${PostRateRejection.FrPrefix}$seconds"
    case ThreadRentou => PostRateRejection.ThreadRentouCode
  }
}

object PostRateRejection {
  private val GontakureCode = "post-rate:board:gontakure"
  private val Samba24Prefix = "post-rate:board:samba24:"
  private val FrPrefix = "post-rate:board:fr:"
  private val ThreadRentouCode = "post-rate:thread:rentou"

  /** code の文字列から戻す。知らない文字列なら None */
  def fromCode(code: String): Option[PostRateRejection] = code match {
    case GontakureCode => Some(Gontakure)
    case ThreadRentouCode => Some(ThreadRentou)
    case _ if code.startsWith(Samba24Prefix) => seconds(code.stripPrefix(Samba24Prefix)).map(Samba24(_))
    case _ if code.startsWith(FrPrefix) => seconds(code.stripPrefix(FrPrefix)).map(Fr(_))
    case _ => None
  }

  private def seconds(text: String): Option[Int] = text.toIntOption.filter(_ > 0)
}

/** 試行モード（BBS_FR_LEVEL=log）で拒否せずに記録した判定 */
enum PostRateFinding {
  case Rejected(rejection: PostRateRejection)
  /** コピペ吸い込みの対象 */
  case Copipe

  /** RPC とログに使う文字列 */
  def code: String = this match {
    case Rejected(rejection) => rejection.code
    case Copipe => PostRateFinding.CopipeCode
  }
}

object PostRateFinding {
  private val CopipeCode = "post-rate:thread:copipe"

  /** code の文字列から戻す。知らない文字列なら None */
  def fromCode(code: String): Option[PostRateFinding] =
    if (code == CopipeCode) Some(Copipe) else PostRateRejection.fromCode(code).map(Rejected(_))
}

/** 連投規制の純粋な判定。仕様は docs/post-rate-limit.md */
object PostRateLimit {
  /** ごんたくれで見る期間（秒） */
  val DaySeconds: Double = 86400

  /** 板単位の設定。timecount・timeclose・samba24・fr は 0 で無効。keys はごんたくれの照合キー */
  final case class BoardSettings(timecount: Int, timeclose: Int, samba24: Int, fr: Int, keys: Set[PostRateKey]) {
    /** 秒数で見る規制（Samba24・広域）が見る期間（秒）。どちらも無効なら 0 */
    def windowSeconds: Int = math.max(samba24, fr)
  }

  /** スレ単位の設定。count は 0 で無効 */
  final case class ThreadSettings(count: Int, resetSeconds: Double, keys: Set[PostRateKey])

  /** 判定に使う投稿の 1 件 */
  final case class Entry(timestampSeconds: Double, identity: PostRateIdentity)

  /**
    * 板単位の判定。history は新しい順で、予約中も 1 件に数える。ごんたくれ、Samba24、広域の順に見る。
    * Samba24 は ip・aid、広域は識別子だけで照合する
    */
  def checkBoard(settings: BoardSettings, history: Seq[Entry], identity: PostRateIdentity, nowSeconds: Double): Option[PostRateRejection] = {
    val gontakure = settings.timecount > 0 && settings.timeclose > 0 &&
      history.take(settings.timecount).count(entry => entry.timestampSeconds >= nowSeconds - DaySeconds && entry.identity.sameAs(identity, settings.keys)) >= settings.timeclose
    def within(seconds: Int, keys: Set[PostRateKey]): Option[Int] =
      if (seconds <= 0) None
      else history.filter(entry => entry.timestampSeconds >= nowSeconds - seconds && entry.identity.sameAs(identity, keys))
        .map(entry => entry.timestampSeconds + seconds - nowSeconds).maxOption
        .map(remaining => math.max(1, math.ceil(remaining).toInt))
    if (gontakure) Some(PostRateRejection.Gontakure)
    else within(settings.samba24, PostRateKey.samba24).map(PostRateRejection.Samba24(_))
      .orElse(within(settings.fr, Set(PostRateKey.Shiki)).map(PostRateRejection.Fr(_)))
  }

  /** スレ単位の判定。recent はスレの直近の投稿で、RESET 秒以内の同一投稿者が count 件以上なら拒否 */
  def checkThread(settings: ThreadSettings, recent: Seq[Entry], identity: PostRateIdentity, nowSeconds: Double): Option[PostRateRejection] = {
    val matched = recent.count(entry => entry.timestampSeconds >= nowSeconds - settings.resetSeconds && entry.identity.sameAs(identity, settings.keys))
    Option.when(settings.count > 0 && matched >= settings.count)(PostRateRejection.ThreadRentou)
  }

  /** dat の末尾 2 行のうちあぼーん行でない行に、新しい dat 行と本文フィールドが完全に一致する行があるか */
  def copipe(dat: String, datLine: String): Boolean = {
    def fields(line: String): Array[String] = line.split("<>", -1)
    def abone(line: String): Boolean = fields(line).take(4).toSeq == Seq.fill(4)("あぼーん")
    fields(datLine).lift(3).exists { body =>
      dat.split("\n", -1).filter(_.nonEmpty).takeRight(2).filterNot(abone).exists(line => fields(line).lift(3).contains(body))
    }
  }
}
