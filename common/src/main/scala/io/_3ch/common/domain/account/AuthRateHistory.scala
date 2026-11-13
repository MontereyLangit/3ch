package io._3ch.common.domain.account

/** 認証要求の計数イベント。kind は verify・register・login・loginFail・reset・token・cap・email、端末の相異数を数える deviceHash:<端末ハッシュ>、端末申告が無い anonymousDevice */
final case class AuthRateEvent(kind: String, t: Double)
final case class AuthRateCounts(hour: Int, day: Int, distinctDevices24h: Int)

/** 認証要求の一時間・一日窓。同じ端末ハッシュは一件にまとめ、申告の無い端末は毎回別の端末として数える */
final case class AuthRateHistory(events: Vector[AuthRateEvent] = Vector.empty) {
  /** 一件を加えて窓ごとの件数を返す。24時間前の境界に達した記録は捨てる。上限に達した種類は記録を増やさず、閾値を超えた件数を返す */
  def hit(kind: String, now: Double): Either[AccountRejection, (AuthRateHistory, AuthRateCounts)] = {
    if (!AccountProfile.validTime(now) || !AuthRateHistory.validKind(kind)) Left(AccountRejection.Invalid)
    else {
      val recent = events.filter(e => e.t > now - 86400 && e.t <= now)
      if (AuthRateHistory.isDevice(kind)) Right(device(recent, kind, now)) else Right(counted(recent, kind, now))
    }
  }

  private def counted(recent: Vector[AuthRateEvent], kind: String, now: Double): (AuthRateHistory, AuthRateCounts) = {
    val same = recent.filter(_.kind == kind)
    val devices = AuthRateHistory.distinctDevices(recent)
    if (same.size >= AuthRateHistory.kindLimit) (AuthRateHistory(recent), AuthRateCounts(AuthRateHistory.kindLimit + 1, AuthRateHistory.kindLimit + 1, devices))
    else {
      val all = same :+ AuthRateEvent(kind, now)
      (AuthRateHistory(recent :+ AuthRateEvent(kind, now)), AuthRateCounts(all.count(_.t > now - 3600), all.size, devices))
    }
  }

  private def device(recent: Vector[AuthRateEvent], kind: String, now: Double): (AuthRateHistory, AuthRateCounts) = {
    val known = kind != AuthRateHistory.anonymousDevice && recent.exists(_.kind == kind)
    if (!known && AuthRateHistory.deviceEvents(recent) >= AuthRateHistory.deviceLimit) {
      (AuthRateHistory(recent), AuthRateCounts(1, 1, AuthRateHistory.deviceLimit + 1))
    } else {
      val kept = if (kind == AuthRateHistory.anonymousDevice) recent else recent.filterNot(_.kind == kind)
      val next = kept :+ AuthRateEvent(kind, now)
      (AuthRateHistory(next), AuthRateCounts(1, 1, AuthRateHistory.distinctDevices(next)))
    }
  }
}
object AuthRateHistory {
  /** 一種類につき24時間に記録する最大件数 */
  val kindLimit: Int = 120
  /** 24時間に記録する端末の最大数 */
  val deviceLimit: Int = 20
  val anonymousDevice: String = "anonymousDevice"

  def isDevice(kind: String): Boolean = kind == anonymousDevice || kind.startsWith("deviceHash:")
  def deviceEvents(events: Vector[AuthRateEvent]): Int = events.count(e => isDevice(e.kind))
  def distinctDevices(events: Vector[AuthRateEvent]): Int =
    events.iterator.filter(e => e.kind.startsWith("deviceHash:")).map(_.kind).toSet.size + events.count(_.kind == anonymousDevice)

  def validKind(kind: String): Boolean = Set("verify", "register", "login", "loginFail", "reset", "token", "cap", "email", anonymousDevice).contains(kind) ||
    (kind.startsWith("deviceHash:") && kind.drop(11).matches("[pgu](?:[0-9a-f]{18}|[0-9a-f]{16})"))
}

/** 認証の乱造検知の閾値。計数に失敗した場合は呼び出し元が認証を拒否する */
object AbuseThresholds {
  /** strict は国内の遠い接続元（エッジ防御の RTT 判定）で、閾値を半分にする */
  def verify(counts: AuthRateCounts, strict: Boolean = false): Boolean =
    counts.hour <= (if (strict) 5 else 10) && counts.distinctDevices24h <= (if (strict) 2 else 5)
  def registration(counts: AuthRateCounts, strict: Boolean = false): Boolean = counts.day <= (if (strict) 2 else 5)
  def login(counts: AuthRateCounts): Boolean = counts.hour <= 30
  /** 同じ接続元の五回目の失敗から一秒ずつ増やし、八秒で止める。ログイン ID の有無には依存しない */
  def loginFailureDelay(counts: AuthRateCounts): Int = math.min(8, math.max(0, counts.hour - 4))
  def reset(counts: AuthRateCounts): Boolean = counts.day <= 3
  /** 専ブラ用キーの発行は一アカウント一日十回まで */
  def tokenIssue(counts: AuthRateCounts): Boolean = counts.day <= 10
  /** 確認メールの送信は一アカウント一日三回まで */
  def emailSend(counts: AuthRateCounts): Boolean = counts.day <= 3
  /** キャップの発行・再発行は一アカウント一日十回まで */
  def capIssue(counts: AuthRateCounts): Boolean = counts.day <= 10
}

/** 乱造検知で閾値を超えた理由 */
enum AbuseReason(val code: String) {
  /** 一時間の認証回数 */
  case VerifyBurst extends AbuseReason("verify")
  /** 24時間に相異なる端末ハッシュ */
  case DeviceSpread extends AbuseReason("devices")
  /** 一日の登録数 */
  case RegisterBurst extends AbuseReason("register")
}

object AbuseReason {
  def parse(code: String): Option[AbuseReason] = AbuseReason.values.find(_.code == code)

  /** 認証の計数が閾値を超えた理由。超えていなければ空 */
  def ofVerify(counts: AuthRateCounts, strict: Boolean): Vector[AbuseReason] =
    Vector(Option.when(counts.hour > (if (strict) 5 else 10))(VerifyBurst), Option.when(counts.distinctDevices24h > (if (strict) 2 else 5))(DeviceSpread)).flatten
}

/** 「要確認」の一件。keyIpHash は接続元の HMAC、firstSeen・lastSeen はその理由で最初と最後に超えた時刻、count は超えた回数 */
final case class AbuseFlag(keyIpHash: String, reason: AbuseReason, firstSeen: Double, lastSeen: Double, count: Int, hour: Int, day: Int, devices: Int) {
  def valid: Boolean = DedicatedBrowserTokens.validHash(keyIpHash) && AccountProfile.validTime(firstSeen) && AccountProfile.validTime(lastSeen) &&
    lastSeen >= firstSeen && count >= 1
}

/** 乱造検知の「要確認」一覧。自動で規制せず、管理画面で確かめるために残す。同じ接続元と理由は 24 時間以内なら一件にまとめる */
final case class AbuseReview(flags: Vector[AbuseFlag] = Vector.empty) {
  def flag(keyIpHash: String, reason: AbuseReason, counts: AuthRateCounts, now: Double): Either[AccountRejection, AbuseReview] =
    if (!DedicatedBrowserTokens.validHash(keyIpHash) || !AccountProfile.validTime(now)) Left(AccountRejection.Invalid)
    else {
      val (same, others) = flags.partition(f => f.keyIpHash == keyIpHash && f.reason == reason && f.lastSeen > now - 86400)
      val entry = same.headOption.fold(AbuseFlag(keyIpHash, reason, now, now, 1, counts.hour, counts.day, counts.distinctDevices24h)) { f =>
        f.copy(lastSeen = math.max(f.lastSeen, now), count = f.count + 1, hour = counts.hour, day = counts.day, devices = counts.distinctDevices24h)
      }
      Right(AbuseReview((entry +: others).sortBy(f => -f.lastSeen).take(AbuseReview.limit)))
    }
}

object AbuseReview {
  /** 残す件数。古いものから捨てる */
  val limit: Int = 500
  /** 一覧を持つ AuthRate の名前。接続元の名前（64 桁の 16 進数）とは重ならない */
  val name: String = "review"
}
