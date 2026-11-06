package io._3ch.common.domain.account

import munit.FunSuite

class GoogleIdTokenSuite extends FunSuite {
  private val now = 2000000000d
  private val claims = GoogleIdClaims("https://accounts.google.com", "client.apps.googleusercontent.com", "1234567890", now + 3600, now, "nonce-value",
    Some("user@example.com"), emailVerified = true)
  private def check(c: GoogleIdClaims, nonce: String = "nonce-value") = GoogleIdToken.verifyClaims(c, now, nonce, "client.apps.googleusercontent.com")

  test("発行者・対象・期限・nonce・メールの確認が揃ったときだけ受け付ける") {
    assertEquals(check(claims), Right(GoogleIdentity("1234567890", Some("user@example.com"))))
    assertEquals(check(claims.copy(iss = "accounts.google.com")).map(_.sub), Right("1234567890"))
    assertEquals(check(claims.copy(iss = "https://evil.example")), Left(GoogleIdTokenRejection.Issuer))
    assertEquals(check(claims.copy(aud = "other")), Left(GoogleIdTokenRejection.Audience))
    assertEquals(GoogleIdToken.verifyClaims(claims, now, "nonce-value", ""), Left(GoogleIdTokenRejection.Audience))
    assertEquals(check(claims.copy(exp = now)), Left(GoogleIdTokenRejection.Expired))
    assertEquals(check(claims.copy(iat = now + 301)), Left(GoogleIdTokenRejection.IssuedAt))
    assert(check(claims.copy(iat = now + 300)).isRight)
    assertEquals(check(claims, "other-nonce"), Left(GoogleIdTokenRejection.Nonce))
    assertEquals(check(claims.copy(nonce = ""), ""), Left(GoogleIdTokenRejection.Nonce))
    assertEquals(check(claims.copy(emailVerified = false)), Left(GoogleIdTokenRejection.Email))
    assertEquals(check(claims.copy(sub = "")), Left(GoogleIdTokenRejection.Subject))
    assertEquals(check(claims.copy(sub = "a:b")), Left(GoogleIdTokenRejection.Subject))
  }
}
