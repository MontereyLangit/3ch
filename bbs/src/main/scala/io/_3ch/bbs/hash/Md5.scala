package io._3ch.bbs.hash

import io._3ch.bbs.external.web.TextEncoder

/**
 * `md5(str)`: 文字列を `TextEncoder` で UTF-8 化（孤立サロゲートは U+FFFD）し、MD5 を16進(小文字)で返す。
 * 計算は npm の `@noble/hashes` に委譲する（docs/v6s実装メモ.md §3.3）。
 */
object Md5 {
  def md5(str: String): String = NobleUtils.bytesToHex(NobleMd5(new TextEncoder().encode(str)))
}
