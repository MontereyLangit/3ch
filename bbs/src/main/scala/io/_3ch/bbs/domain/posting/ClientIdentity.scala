package io._3ch.bbs.domain.posting

/**
  * 投稿者の識別情報。スレ立て規制では ip・userAgent・networkGroup をソルト付きハッシュにして渡す
  */
final case class ClientIdentity(
  ip: String = "",
  userAgent: String = "",
  networkGroup: String = ""
)
