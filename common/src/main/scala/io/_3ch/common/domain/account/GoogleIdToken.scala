package io._3ch.common.domain.account

/** Google の ID トークンから読み出したクレーム。署名は別に検証する */
final case class GoogleIdClaims(iss: String, aud: String, sub: String, exp: Double, iat: Double, nonce: String,
  email: Option[String], emailVerified: Boolean)

/** ID トークンを受け付けない理由 */
enum GoogleIdTokenRejection(val code: String) {
  case Issuer extends GoogleIdTokenRejection("google:iss")
  case Audience extends GoogleIdTokenRejection("google:aud")
  case Expired extends GoogleIdTokenRejection("google:exp")
  case IssuedAt extends GoogleIdTokenRejection("google:iat")
  case Nonce extends GoogleIdTokenRejection("google:nonce")
  case Email extends GoogleIdTokenRejection("google:email")
  case Subject extends GoogleIdTokenRejection("google:sub")
}

/** クレームの検証を通った Google アカウント */
final case class GoogleIdentity(sub: String, email: Option[String])

object GoogleIdToken {
  val issuers: Set[String] = Set("https://accounts.google.com", "accounts.google.com")
  /** 発行時刻に許す時計のずれ（秒） */
  val clockSkew: Double = 300

  /** 発行者・対象・期限・発行時刻・nonce・メールの確認を検査する。nonce は定数時間で比べる */
  def verifyClaims(claims: GoogleIdClaims, now: Double, nonce: String, clientId: String): Either[GoogleIdTokenRejection, GoogleIdentity] = {
    if (!issuers.contains(claims.iss)) Left(GoogleIdTokenRejection.Issuer)
    else if (clientId.isEmpty || claims.aud != clientId) Left(GoogleIdTokenRejection.Audience)
    else if (!(claims.exp > now)) Left(GoogleIdTokenRejection.Expired)
    else if (!(claims.iat <= now + clockSkew)) Left(GoogleIdTokenRejection.IssuedAt)
    else if (nonce.isEmpty || !sameText(claims.nonce, nonce)) Left(GoogleIdTokenRejection.Nonce)
    else if (!claims.emailVerified) Left(GoogleIdTokenRejection.Email)
    else if (!claims.sub.matches("[0-9A-Za-z_\\-]{1,255}")) Left(GoogleIdTokenRejection.Subject)
    else Right(GoogleIdentity(claims.sub, claims.email))
  }

  private def sameText(a: String, b: String): Boolean =
    a.length == b.length && a.indices.foldLeft(0)((difference, i) => difference | (a.charAt(i) ^ b.charAt(i))) == 0
}
