package ch3s.bbs.external.web

import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobal
import scala.scalajs.js.typedarray.{ArrayBuffer, Uint8Array}

/**
 * `TextEncoder` / `crypto.subtle` / `btoa` の手書きfacade。
 * `WebFacade.scala` と同じ方針（scalajs-dom不使用、必要なメンバだけを`@JSGlobal`で直接宣言）。
 * Cloudflare Workers / Node（テスト実行環境） の両方に実在するグローバルAPI。
 */
@js.native
@JSGlobal("TextEncoder")
class TextEncoder() extends js.Object {
  def encode(input: String): Uint8Array = js.native
}

@js.native
trait SubtleCrypto extends js.Object {
  def digest(algorithm: String, data: Uint8Array): js.Promise[ArrayBuffer] = js.native
}

@js.native
trait CryptoFacade extends js.Object {
  val subtle: SubtleCrypto = js.native
}

@js.native
@JSGlobal("crypto")
object GlobalCrypto extends CryptoFacade

@js.native
@JSGlobal("btoa")
object GlobalBtoa extends js.Object {
  def apply(data: String): String = js.native
}
