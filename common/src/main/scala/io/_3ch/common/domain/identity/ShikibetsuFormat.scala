package io._3ch.common.domain.identity

/** 識別子の書式。checked はハッシュ済みの値を受け取る */
object ShikibetsuFormat {
  /** 国.県.市 AS-UA の形。各ハッシュは先頭の数文字だけ使う */
  def checked(country: String, regionHash: String, cityHash: String, asnHash: String, userAgentHash: String): String =
    s"$country.${regionHash.take(2)}.${cityHash.take(2)} ${asnHash.take(2)}-${userAgentHash.take(16)}"

  /** phoebe の書式 国.県 AS-端末5軸Lv。軸は欠損を 000 にした3文字ずつを並びの順で受け取る */
  def phoebe(country: String, regionHash: String, asnHash: String, axes: Seq[String], lv: Int): String =
    s"$country.${regionHash.take(2)} ${asnHash.take(2)}-${axes.mkString}${levelMark(lv)}"

  /** 識別子末尾の Lv。0〜9 はその数字、10 以上は * */
  def levelMark(lv: Int): String = if (lv >= 10) "*" else math.max(lv, 0).toString

  /** checked 以外の方式の書式。Phoebe と Hidden は空 */
  def plain(mode: ShikibetsuMode, country: String, region: String, city: String, asn: String): String = mode match {
    case ShikibetsuMode.Raw => s"$country.$region $asn"
    case ShikibetsuMode.Ken => s"$country.$region"
    case ShikibetsuMode.City => s"$country.$region.$city"
    case ShikibetsuMode.As => asn
    case _ => ""
  }
}
