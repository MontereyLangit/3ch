package io._3ch.bbs.hash

import io._3ch.bbs.external.web.{GlobalBtoa, GlobalCrypto, TextEncoder}
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import scala.scalajs.js.typedarray.Uint8Array
import scala.util.control.NonFatal

/** 認証用の HMAC と乱数。鍵が無い場合や暗号 API の失敗は None */
object AuthHmac {
  private val Base32 = "abcdefghijklmnopqrstuvwxyz234567"
  private val Alphanumeric = "abcdefghijklmnopqrstuvwxyz0123456789"
  private val UrlSafe = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

  /** HMAC-SHA256 の全 32 バイトを小文字の 16 進数にする */
  def fullHex(secret: String, text: String): Future[Option[String]] =
    hmac(secret, text).map(_.map(_.map(byte => Integer.toHexString(byte | 0x100).substring(1)).mkString))

  /** 種別ごとの索引キー。値の正規化は呼び出し側で行う */
  def indexKey(secret: String, kind: String, value: String): Future[Option[String]] =
    fullHex(secret, kind + ":" + value)

  /** 専ブラの UA を前後の空白だけ除いてハッシュする */
  def uaHash(secret: String, userAgent: String): Future[Option[String]] =
    fullHex(secret, "token-ua:" + userAgent.trim)

  /** セッション署名。HMAC の先頭 16 バイトをパディングなしの base64url にする */
  def sessionSignature(secret: String, payload: String): Future[Option[String]] =
    hmac(secret, payload).map(_.map { bytes =>
      GlobalBtoa(bytes.take(16).map(_.toChar).mkString).replace('+', '-').replace('/', '_').stripSuffix("==")
    })

  /** 切り詰めた署名を、同じ長さを最後まで比較して検証する */
  def verifySession(secret: String, payload: String, signature: String): Future[Boolean] = {
    if (signature == null || signature.length != 22) Future.successful(false)
    else sessionSignature(secret, payload).map(_.exists { expected =>
      expected.indices.foldLeft(0)((difference, index) => difference | (expected.charAt(index).toInt ^ signature.charAt(index).toInt)) == 0
    })
  }

  /** 専ブラ認証キーに使う a-z・2-7 の乱数 */
  def randomBase32(length: Int): String = randomString(Base32, length)

  /** OAuth の state・nonce・PKCE の verifier に使う base64url の文字の乱数 */
  def randomUrlSafe(length: Int): String = randomString(UrlSafe, length)

  /** 内部アカウント ID に使う 16 文字の乱数 */
  def randomAid(): String = randomString(Alphanumeric, 16)

  /** クライアント ID に使う 8 文字の乱数 */
  def randomUid(): String = randomString(Alphanumeric, 8)

  private def hmac(secret: String, text: String): Future[Option[Vector[Int]]] = {
    if (secret == null || secret.isEmpty) Future.successful(None)
    else Future.unit.flatMap { _ =>
      val encoder = new TextEncoder()
      val algorithm = js.Dynamic.literal(name = "HMAC", hash = "SHA-256")
      for {
        key <- GlobalCrypto.subtle.importKey("raw", encoder.encode(secret), algorithm, false, js.Array("sign")).toFuture
        buffer <- GlobalCrypto.subtle.sign("HMAC", key, encoder.encode(text)).toFuture
      } yield {
        val bytes = new Uint8Array(buffer)
        Option(Vector.tabulate(bytes.length)(index => bytes(index).toInt & 0xff))
      }
    }.recover { case NonFatal(_) => None }
  }

  private def randomString(alphabet: String, length: Int): String = {
    require(length > 0 && length <= 4096)
    val ceiling = 256 - 256 % alphabet.length
    def collect(remaining: Int): String = {
      val bytes = GlobalCrypto.getRandomValues(new Uint8Array(remaining))
      val chars = (0 until bytes.length).iterator.map(index => bytes(index).toInt & 0xff)
        .filter(_ < ceiling).map(byte => alphabet.charAt(byte % alphabet.length)).mkString
      if (chars.length == remaining) chars else chars + collect(remaining - chars.length)
    }
    collect(length)
  }
}
