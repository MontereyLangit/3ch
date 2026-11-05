package io._3ch.common.domain.identity

/** 識別子の書式。checked はハッシュ済みの値を受け取る */
object ShikibetsuFormat {
  def checked(country: String, regionHash: String, cityHash: String, asnHash: String, userAgentHash: String): String =
    s"$country.${regionHash.take(2)}.${cityHash.take(2)} ${asnHash.take(2)}-${userAgentHash.take(16)}"

  def plain(mode: ShikibetsuMode, country: String, region: String, city: String, asn: String): String = mode match {
    case ShikibetsuMode.Raw => s"$country.$region $asn"
    case ShikibetsuMode.Ken => s"$country.$region"
    case ShikibetsuMode.City => s"$country.$region.$city"
    case ShikibetsuMode.As => asn
    case _ => ""
  }
}
