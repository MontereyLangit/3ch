package io._3ch.bbs.domain.posting

/**
  * 投稿者の識別情報
  */
final case class ClientIdentity(
  ip: String = "",
  userAgent: String = "",
  networkGroup: String = ""
)
