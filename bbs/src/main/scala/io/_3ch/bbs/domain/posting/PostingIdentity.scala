package io._3ch.bbs.domain.posting

/** 投稿者の接続元情報。検査前の値もエラー画面用に持ち、ヘッダの検査後に host と UA を差し替える */
final case class PostingIdentity(
  ip: Option[String], host: String, userAgent: String, browserInfo: String, deviceInfo: String,
  asn: String, country: String, region: String, city: String, salt: String
)
