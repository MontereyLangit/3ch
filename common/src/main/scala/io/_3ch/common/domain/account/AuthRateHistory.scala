package io._3ch.common.domain.account

/** 認証要求の計数イベント。kind は verify・register・login・reset・token・cap・email、または端末の相異数を数える deviceHash:<端末ハッシュ> */
final case class AuthRateEvent(kind: String, t: Double)
final case class AuthRateCounts(hour: Int, day: Int, distinctDevices24h: Int)

/** 認証要求の一時間・一日窓。端末数は同じハッシュを重複して数えない */
final case class AuthRateHistory(events: Vector[AuthRateEvent] = Vector.empty) {
  /** 一件を加えて窓ごとの件数を返す。24時間前の境界に達した記録は捨てる */
  def hit(kind: String, now: Double): Either[AccountRejection, (AuthRateHistory, AuthRateCounts)] = {
    if (!AccountProfile.validTime(now) || !AuthRateHistory.validKind(kind)) Left(AccountRejection.Invalid)
    else {
      val recent = events.filter(e => e.t > now - 86400 && e.t <= now) :+ AuthRateEvent(kind, now)
      val same = recent.filter(_.kind == kind)
      val counts = AuthRateCounts(same.count(_.t > now - 3600), same.size,
        recent.iterator.filter(_.kind.startsWith("deviceHash:")).map(_.kind).toSet.size)
      Right((AuthRateHistory(recent), counts))
    }
  }
}
object AuthRateHistory {
  def validKind(kind: String): Boolean = Set("verify", "register", "login", "reset", "token", "cap", "email").contains(kind) ||
    (kind.startsWith("deviceHash:") && kind.drop(11).matches("[pgu](?:[0-9a-f]{18}|[0-9a-f]{16})"))
}

/** 認証の乱造検知の閾値。計数に失敗した場合は呼び出し元が認証を拒否する */
object AbuseThresholds {
  /** strict は国内の遠い接続元（エッジ防御の RTT 判定）で、閾値を半分にする */
  def verify(counts: AuthRateCounts, strict: Boolean = false): Boolean =
    counts.hour <= (if (strict) 5 else 10) && counts.distinctDevices24h <= (if (strict) 2 else 5)
  def registration(counts: AuthRateCounts, strict: Boolean = false): Boolean = counts.day <= (if (strict) 2 else 5)
  def login(counts: AuthRateCounts): Boolean = counts.hour <= 30
  def reset(counts: AuthRateCounts): Boolean = counts.day <= 3
  /** 専ブラ用キーの発行は一アカウント一日十回まで */
  def tokenIssue(counts: AuthRateCounts): Boolean = counts.day <= 10
  /** 確認メールの送信は一アカウント一日三回まで */
  def emailSend(counts: AuthRateCounts): Boolean = counts.day <= 3
  /** キャップの発行・再発行は一アカウント一日十回まで */
  def capIssue(counts: AuthRateCounts): Boolean = counts.day <= 10
}
