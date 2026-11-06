package io._3ch.bbs.domain.posting.moderation

/** 広域スレ立て規制で同じ接続環境とみなすネットワークグループ */
object ThreadCreationNetwork {
  /** 海外は overseas、国内で ASN が asOnly に当たれば AS:番号、ASN と地域があれば AS:番号:JP:地域、どれでもなければ空 */
  def group(country: Option[String], asn: Option[String], region: Option[String], asOnly: String => Boolean): String = {
    val countryCode = country.getOrElse("").trim.toUpperCase
    val asNumber = asn.getOrElse("").trim.toLongOption.filter(n => n > 0 && n <= 4294967295L).map(_.toString).getOrElse("")
    val regionName = region.getOrElse("").trim
    if (countryCode.matches("[A-Z]{2}") && countryCode != "JP" && countryCode != "XX" && countryCode != "T1") "overseas"
    else if (countryCode == "JP" && asNumber.nonEmpty && asOnly(asNumber)) s"AS:$asNumber"
    else if (countryCode == "JP" && asNumber.nonEmpty && regionName.nonEmpty) s"AS:$asNumber:JP:$regionName"
    else ""
  }
}
