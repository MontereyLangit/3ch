package io._3ch.common.domain.identity

/** ハッシュ前の県・市・AS・UA の種。日付窓は呼び出し元で文字列にして渡す */
final case class ShikibetsuSeeds(region: String, city: String, asn: String, userAgent: String, group: String, date: String, salt: String) {
  private def seed(value: String): String = value + group + date + salt
  /** 県の種 */
  def regionSeed: String = seed(region)
  /** 市の種。県の種と区別するため c を付ける */
  def citySeed: String = seed(city + "c")
  /** AS の種 */
  def asnSeed: String = seed(asn)
  /** UA の種 */
  def userAgentSeed: String = seed(userAgent)
  /** phoebe 書式の端末の軸の種。軸の名前を混ぜて、同じ値でも軸ごとに別のハッシュにする */
  def axisSeed(axis: IdentifierAxis, value: String): String = seed(value + "|" + axis.code)
}
