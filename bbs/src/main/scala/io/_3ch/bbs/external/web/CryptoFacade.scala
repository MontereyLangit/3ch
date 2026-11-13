package io._3ch.bbs.external.web

import scala.scalajs.js
import scala.scalajs.js.annotation.{JSGlobal, JSName}
import scala.scalajs.js.typedarray.{ArrayBuffer, Uint8Array}

/**
 * TextEncoder / TextDecoder / crypto.subtle / btoa の手書きfacade。
 * `WebFacade.scala` と同じ方針（scalajs-dom不使用、必要なメンバだけを`@JSGlobal`で直接宣言）。
 * Cloudflare Workers / Node（テスト実行環境） の両方に実在するグローバルAPI。
 */
@js.native
@JSGlobal("TextEncoder")
class TextEncoder() extends js.Object {
  def encode(input: String): Uint8Array = js.native
}

@js.native
@JSGlobal("TextDecoder")
class TextDecoder(label: String = js.native) extends js.Object {
  def decode(input: Uint8Array): String = js.native
}

@js.native
trait CryptoKey extends js.Object

@js.native
trait SubtleCrypto extends js.Object {
  def importKey(format: String, keyData: Uint8Array, algorithm: js.Object, extractable: Boolean, keyUsages: js.Array[String]): js.Promise[CryptoKey] = js.native
  /** JWK 形式の鍵を読み込む */
  @JSName("importKey")
  def importJwk(format: String, keyData: js.Object, algorithm: js.Object, extractable: Boolean, keyUsages: js.Array[String]): js.Promise[CryptoKey] = js.native
  def verify(algorithm: js.Object, key: CryptoKey, signature: Uint8Array, data: Uint8Array): js.Promise[Boolean] = js.native
  def sign(algorithm: String, key: CryptoKey, data: Uint8Array): js.Promise[ArrayBuffer] = js.native
  def digest(algorithm: String, data: Uint8Array): js.Promise[ArrayBuffer] = js.native
  def deriveBits(algorithm: js.Object, baseKey: CryptoKey, length: Int): js.Promise[ArrayBuffer] = js.native
}

@js.native
trait CryptoFacade extends js.Object {
  val subtle: SubtleCrypto = js.native
  def getRandomValues(array: Uint8Array): Uint8Array = js.native
}

@js.native
@JSGlobal("crypto")
object GlobalCrypto extends CryptoFacade

@js.native
@JSGlobal("atob")
object GlobalAtob extends js.Object {
  def apply(data: String): String = js.native
}

@js.native
@JSGlobal("btoa")
object GlobalBtoa extends js.Object {
  def apply(data: String): String = js.native
}
