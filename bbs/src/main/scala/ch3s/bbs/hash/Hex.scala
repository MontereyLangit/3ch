package ch3s.bbs.hash

import scala.scalajs.js.typedarray.Uint8Array

/** 16進文字列（偶数長、大文字小文字可）をバイト列にする。不正な入力は例外。npm `@noble/hashes` に委譲する。 */
object Hex {
  def toBytes(hex: String): Uint8Array = NobleUtils.hexToBytes(hex)
}
