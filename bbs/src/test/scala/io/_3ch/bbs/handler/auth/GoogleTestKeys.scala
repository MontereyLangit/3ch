package io._3ch.bbs.handler.auth

import io._3ch.bbs.external.web.TextEncoder
import io._3ch.bbs.hash.{JwtRs256, RsaPublicKey}
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.scalajs.js.typedarray.{ArrayBuffer, Uint8Array}

/** テスト専用の RSA 鍵で Google の ID トークンに見立てた JWT を作る */
final class GoogleTestKeys(val kid: String, privateKey: js.Any, val jwk: js.Dynamic) {
  def publicKey: RsaPublicKey = RsaPublicKey(kid, jwk.n.asInstanceOf[String], jwk.e.asInstanceOf[String])

  private def segment(value: js.Any): String = {
    val bytes = new TextEncoder().encode(js.JSON.stringify(value))
    JwtRs256.encode(Vector.tabulate(bytes.length)(i => bytes(i).toByte))
  }

  /** header の kid を差し替えたいときは headerKid を渡す */
  def token(claims: js.Any, headerKid: String = kid): Future[String] = {
    val input = segment(js.Dynamic.literal(alg = "RS256", kid = headerKid, typ = "JWT")) + "." + segment(claims)
    GoogleTestKeys.subtle.sign(js.Dynamic.literal(name = "RSASSA-PKCS1-v1_5"), privateKey, new TextEncoder().encode(input))
      .asInstanceOf[js.Promise[ArrayBuffer]].toFuture.map { buffer =>
        val bytes = new Uint8Array(buffer)
        input + "." + JwtRs256.encode(Vector.tabulate(bytes.length)(i => bytes(i).toByte))
      }
  }
}

object GoogleTestKeys {
  private def subtle: js.Dynamic = js.Dynamic.global.crypto.subtle

  def generate(kid: String): Future[GoogleTestKeys] = {
    val algorithm = js.Dynamic.literal(name = "RSASSA-PKCS1-v1_5", modulusLength = 2048,
      publicExponent = new Uint8Array(js.Array[Short](1, 0, 1)), hash = "SHA-256")
    subtle.generateKey(algorithm, true, js.Array("sign", "verify")).asInstanceOf[js.Promise[js.Dynamic]].toFuture.flatMap { pair =>
      subtle.exportKey("jwk", pair.publicKey).asInstanceOf[js.Promise[js.Dynamic]].toFuture.map { jwk =>
        jwk.kid = kid
        new GoogleTestKeys(kid, pair.privateKey, jwk)
      }
    }
  }

  /** Google の ID トークンと同じ形のクレーム */
  def claims(sub: String, nonce: String, now: Double, aud: String = "test-client.apps.googleusercontent.com"): js.Dynamic =
    js.Dynamic.literal(iss = "https://accounts.google.com", aud = aud, sub = sub, exp = now + 3600, iat = now, nonce = nonce,
      email = "user@example.com", email_verified = true)
}
