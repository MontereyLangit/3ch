package io._3ch.common.domain.identity

/** ハッシュ前の県・市・AS・UA の種。日付窓は呼び出し元で文字列にして渡す */
final case class ShikibetsuSeeds(region: String, city: String, asn: String, userAgent: String, group: String, date: String, salt: String) {
  private def seed(value: String): String = value + group + date + salt
  def regionSeed: String = seed(region)
  def citySeed: String = seed(city + "c")
  def asnSeed: String = seed(asn)
  def userAgentSeed: String = seed(userAgent)
}
