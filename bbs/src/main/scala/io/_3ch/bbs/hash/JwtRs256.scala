package io._3ch.bbs.hash

import io._3ch.bbs.external.web.{GlobalAtob, GlobalBtoa, GlobalCrypto, TextDecoder, TextEncoder}
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.scalajs.js.typedarray.Uint8Array
import scala.util.Try
import scala.util.control.NonFatal

/** JWKS の RSA 公開鍵。n と e は base64url */
final case class RsaPublicKey(kid: String, n: String, e: String)

/** 署名を検証する前の JWT。header と payload は JSON の文字列 */
final case class JwtParts(header: String, payload: String, signingInput: String, signature: Vector[Byte])

/** RS256 の JWT の分解と署名検証、PKCE 用の base64url。暗号 API の失敗は検証失敗にする */
object JwtRs256 {
  /** base64url を復号する。不正な文字や長さは None */
  def decode(value: String): Option[Vector[Byte]] = {
    if (!value.matches("[A-Za-z0-9_-]*") || value.length % 4 == 1) None
    else Try {
      val binary = GlobalAtob(value.replace('-', '+').replace('_', '/') + "=" * ((4 - value.length % 4) % 4))
      Vector.tabulate(binary.length)(i => binary.charAt(i).toByte)
    }.toOption
  }

  /** パディングなしの base64url */
  def encode(bytes: Vector[Byte]): String =
    GlobalBtoa(bytes.map(b => (b.toInt & 0xff).toChar).mkString).replace('+', '-').replace('/', '_').reverse.dropWhile(_ == '=').reverse

  /** 3 つに分け、header と payload を UTF-8 の文字列にする */
  def parse(token: String): Option[JwtParts] = Option(token).filter(_.length <= 8192).flatMap { text =>
    text.split("\\.", -1).toList match {
      case header :: payload :: signature :: Nil => for {
        h <- decode(header).flatMap(utf8)
        p <- decode(payload).flatMap(utf8)
        s <- decode(signature)
      } yield JwtParts(h, p, header + "." + payload, s)
      case _ => None
    }
  }

  /** RSASSA-PKCS1-v1_5 と SHA-256 で署名を検証する */
  def verify(parts: JwtParts, key: RsaPublicKey): Future[Boolean] = Future.unit.flatMap { _ =>
    val algorithm = js.Dynamic.literal(name = "RSASSA-PKCS1-v1_5", hash = "SHA-256")
    val jwk = js.Dynamic.literal(kty = "RSA", n = key.n, e = key.e, alg = "RS256", ext = true)
    GlobalCrypto.subtle.importJwk("jwk", jwk, algorithm, false, js.Array("verify")).toFuture.flatMap { imported =>
      GlobalCrypto.subtle.verify(js.Dynamic.literal(name = "RSASSA-PKCS1-v1_5"), imported, bytes(parts.signature),
        new TextEncoder().encode(parts.signingInput)).toFuture
    }
  }.recover { case NonFatal(_) => false }

  /** PKCE の code_challenge（S256） */
  def challenge(verifier: String): Future[String] =
    GlobalCrypto.subtle.digest("SHA-256", new TextEncoder().encode(verifier)).toFuture.map { buffer =>
      val digest = new Uint8Array(buffer)
      encode(Vector.tabulate(digest.length)(i => digest(i).toByte))
    }

  private def utf8(value: Vector[Byte]): Option[String] = Try(new TextDecoder("utf-8").decode(bytes(value))).toOption

  private def bytes(value: Vector[Byte]): Uint8Array = {
    val result = new Uint8Array(value.size)
    value.zipWithIndex.foreach { case (byte, index) => result(index) = (byte.toInt & 0xff).toShort }
    result
  }
}
