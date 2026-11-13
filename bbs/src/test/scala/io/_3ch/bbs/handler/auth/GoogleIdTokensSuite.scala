package io._3ch.bbs.handler.auth

import io._3ch.bbs.external.web.TextEncoder
import io._3ch.bbs.hash.{JwtRs256, RsaPublicKey}
import io._3ch.common.domain.account.GoogleIdentity
import munit.FunSuite
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

class GoogleIdTokensSuite extends FunSuite {
  private val now = 2000000000d
  private val client = "test-client.apps.googleusercontent.com"

  private def segment(value: js.Any): String = {
    val bytes = new TextEncoder().encode(js.JSON.stringify(value))
    JwtRs256.encode(Vector.tabulate(bytes.length)(i => bytes(i).toByte))
  }

  private final class Endpoints(stale: Vector[RsaPublicKey], fresh: Vector[RsaPublicKey]) extends GoogleEndpoints {
    var refreshed = 0
    def exchange(code: String, verifier: String, redirectUri: String): Future[Option[String]] = Future.successful(None)
    def keys(refresh: Boolean): Future[Vector[RsaPublicKey]] = {
      if (refresh) refreshed += 1
      Future.successful(if (refresh) fresh else stale)
    }
  }

  test("JWKS の鍵で RS256 の署名を検証してからクレームを確かめる") {
    for {
      keys <- GoogleTestKeys.generate("kid-1")
      other <- GoogleTestKeys.generate("kid-1")
      token <- keys.token(GoogleTestKeys.claims("1234567890", "nonce-a", now))
      endpoints = new Endpoints(Vector(keys.publicKey), Vector(keys.publicKey))
      verified <- GoogleIdTokens.verify(token, endpoints, now, "nonce-a", client)
      _ = assertEquals(verified, Some(GoogleIdentity("1234567890", Some("user@example.com"))))
      wrongNonce <- GoogleIdTokens.verify(token, endpoints, now, "nonce-b", client)
      _ = assertEquals(wrongNonce, None)
      forged <- other.token(GoogleTestKeys.claims("1234567890", "nonce-a", now))
      rejected <- GoogleIdTokens.verify(forged, endpoints, now, "nonce-a", client)
      _ = assertEquals(rejected, None)
      parts = token.split('.')
      tampered = Seq(parts(0), segment(GoogleTestKeys.claims("999", "nonce-a", now)), parts(2)).mkString(".")
      changed <- GoogleIdTokens.verify(tampered, endpoints, now, "nonce-a", client)
      _ = assertEquals(changed, None)
      unsigned = Seq("eyJhbGciOiJub25lIn0", parts(1), "").mkString(".")
      none <- GoogleIdTokens.verify(unsigned, endpoints, now, "nonce-a", client)
    } yield assertEquals(none, None)
  }

  test("鍵の ID が無ければ一度だけ取り直し、それでも無ければ拒否する") {
    for {
      oldKey <- GoogleTestKeys.generate("kid-old")
      newKey <- GoogleTestKeys.generate("kid-new")
      token <- newKey.token(GoogleTestKeys.claims("1234567890", "nonce-a", now))
      rotated = new Endpoints(Vector(oldKey.publicKey), Vector(newKey.publicKey))
      verified <- GoogleIdTokens.verify(token, rotated, now, "nonce-a", client)
      _ = assertEquals(verified.map(_.sub), Some("1234567890"))
      _ = assertEquals(rotated.refreshed, 1)
      missing = new Endpoints(Vector(oldKey.publicKey), Vector(oldKey.publicKey))
      none <- GoogleIdTokens.verify(token, missing, now, "nonce-a", client)
      _ = assertEquals(none, None)
    } yield assertEquals(missing.refreshed, 1)
  }

  test("JWKS は RSA の署名用の鍵だけを読む") {
    val raw = """{"keys":[{"kid":"a","kty":"RSA","alg":"RS256","use":"sig","n":"nn","e":"AQAB"},{"kid":"b","kty":"EC","n":"x","e":"y"},{"kid":"c","kty":"RSA","alg":"RS512","n":"x","e":"y"},{"kty":"RSA","n":"x","e":"y"}]}"""
    assertEquals(GoogleIdTokens.parseKeys(raw), Vector(RsaPublicKey("a", "nn", "AQAB")))
    assertEquals(GoogleIdTokens.parseKeys("[]"), Vector.empty)
    assertEquals(GoogleIdTokens.claims("""{"iss":"accounts.google.com","aud":["a"],"sub":"1","exp":1,"iat":1,"nonce":"n"}"""), None)
  }
}
