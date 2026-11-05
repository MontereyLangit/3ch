package io._3ch.common.domain.posting

/** log 行と規制の対象欄に出す投稿者情報。uid はまだ常に空 */
final case class UserInfo(uid: String, host: String, asn: String, country: String, region: String, city: String, device: String, userAgent: String) {
  /** log 行に書く文字列 */
  def text: String = s"UID:$uid IP:$host AS$asn-$country,$region,${city}Device:$device UA:$userAgent"
}
