package io._3ch.bbs.domain.posting

/** 投稿リクエストの識別情報。未検査の値はエラー画面用に保持し、検査後に host と UA を更新する。 */
final case class PostingIdentity(
  ip: Option[String], host: String, userAgent: String, browserInfo: String, deviceInfo: String,
  asn: String, country: String, region: String, city: String, salt: String
)
