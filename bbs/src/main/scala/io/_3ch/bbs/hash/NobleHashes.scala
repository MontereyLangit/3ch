package io._3ch.bbs.hash

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport
import scala.scalajs.js.typedarray.Uint8Array

/** npm `@noble/hashes` の facade（docs/v6s実装メモ.md §3.3）。 */
@js.native
@JSImport("@noble/hashes/legacy.js", "md5")
private[hash] object NobleMd5 extends js.Function1[Uint8Array, Uint8Array] {
  def apply(data: Uint8Array): Uint8Array = js.native
}

@js.native
@JSImport("@noble/hashes/utils.js", JSImport.Namespace)
private[hash] object NobleUtils extends js.Object {
  def bytesToHex(bytes: Uint8Array): String = js.native
}
