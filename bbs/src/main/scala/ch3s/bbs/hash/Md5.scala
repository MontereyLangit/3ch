package ch3s.bbs.hash

import ch3s.bbs.external.web.TextEncoder

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport
import scala.scalajs.js.typedarray.Uint8Array

@js.native
@JSImport("@noble/hashes/legacy.js", "md5")
private object NobleMd5 extends js.Function1[Uint8Array, Uint8Array] {
  def apply(data: Uint8Array): Uint8Array = js.native
}

@js.native
@JSImport("@noble/hashes/utils.js", "bytesToHex")
private object NobleBytesToHex extends js.Function1[Uint8Array, String] {
  def apply(bytes: Uint8Array): String = js.native
}

/**
 * `md5(str)`: 文字列を `TextEncoder` で UTF-8 化（孤立サロゲートは U+FFFD）し、MD5 を16進(小文字)で返す。
 * 計算は npm の `@noble/hashes` に委譲する（docs/v6s実装メモ.md §3.3）。
 */
object Md5 {
  def md5(str: String): String = NobleBytesToHex(NobleMd5(new TextEncoder().encode(str)))
}
