package io._3ch.common.domain.identity

import io._3ch.common.domain.device.DeviceHashRef

/** phoebe 書式の端末の5軸。識別子での並び順に定義し、code を軸の種に混ぜる */
enum IdentifierAxis(val code: String) {
  case E extends IdentifierAxis("E")
  case F extends IdentifierAxis("F")
  case G extends IdentifierAxis("G")
  case Z extends IdentifierAxis("Z")
  case X extends IdentifierAxis("X")
}

/** 解析した UA の OS・ブラウザ・端末種別。解析できなかった項目は空 */
final case class UserAgentProfile(os: String = "", browser: String = "", device: String = "")

/** 5軸の値。空の軸はハッシュせず 000 にする */
final case class IdentifierAxes(values: Map[IdentifierAxis, String]) {
  def value(axis: IdentifierAxis): String = values.getOrElse(axis, "")
}

object IdentifierAxes {
  /** 欠損した軸の表示 */
  val Missing: String = "000"

  /** 認証済の端末ハッシュを第1〜2・3〜5・6〜7・8・9軸に分けて割り当てる。第9軸の無い旧16桁は X が欠損 */
  def fromDevice(stored: String): Option[IdentifierAxes] = DeviceHashRef.parse(stored).map { ref =>
    val id = ref.identifier
    def part(from: Int, until: Int): String = if (id.length >= until) id.substring(from, until) else ""
    IdentifierAxes(Map(IdentifierAxis.E -> part(0, 4), IdentifierAxis.F -> part(4, 10), IdentifierAxis.G -> part(10, 14),
      IdentifierAxis.Z -> part(14, 16), IdentifierAxis.X -> part(16, 18)))
  }

  /** 未ログインと端末ハッシュの無い投稿の軸。hints は Client Hints の sec-ch-ua 系の値 */
  def fromAgent(profile: UserAgentProfile, userAgent: String, hints: String, acceptLanguage: String): IdentifierAxes =
    IdentifierAxes(Map(IdentifierAxis.E -> profile.os, IdentifierAxis.F -> profile.browser, IdentifierAxis.G -> profile.device,
      IdentifierAxis.Z -> userAgent, IdentifierAxis.X -> (if (hints.isEmpty && acceptLanguage.isEmpty) "" else hints + "|" + acceptLanguage)))
}
