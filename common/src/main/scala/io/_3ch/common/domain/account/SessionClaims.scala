package io._3ch.common.domain.account

/** 専ブラ認証キーに紐づくセッション。uaHash は HMAC の先頭 16 桁 */
final case class SessionBinding(tokenId: String, uaHash: String)

/** セッション Cookie の内容。署名と期限を確認してから使う */
final case class SessionClaims(
  aid: String,
  uid: String,
  generation: Long,
  expiresAt: Long,
  lv: Int,
  device: String = "",
  binding: Option[SessionBinding] = None
) {
  /** 失効時刻ちょうどは期限切れ */
  def isCurrent(nowSeconds: Double): Boolean = nowSeconds.isFinite && expiresAt.toDouble > nowSeconds
}
