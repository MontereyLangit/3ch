package ch3s.common.text

import scala.collection.mutable

/**
 * WHATWG Encoding Standard 準拠の UTF-8 エンコーダを手書きでポートしたオブジェクト
 *
 * `src/common.js` の `md5()`（内部で `new TextEncoder().encode(s)` を使う）、および
 * `sha1Base64()` / `hash()`（同様に `TextEncoder` を使う）と**同一のバイト列**を
 * JVM/JS 双方で再現するために使う
 *
 * commonではJVMの`java.nio.charset`に依存できないので、この最小限のUTF-8エンコーダを利用
 *
 * 孤立サロゲート（ペアになっていない High/Low サロゲート）は、`TextEncoder` と同じく
 * 1つの `U+FFFD`（3バイト `EF BF BD`）に置き換える（Node の `TextEncoder` で確認済み）。
 * これは Shift_JIS 側（`ch3s.bbs.util.SjisEncoding`、`[...str]` 相当のサロゲートペア走査を行うが
 * 孤立サロゲートを代替しない）とは意図的に異なる実装なので、コードポイント走査ロジックは共有しない。
 */
object Utf8 {

  private val ReplacementCodePoint = 0xfffd

  /** 文字列をUTF-16コード単位から「WHATWG的コードポイント列」へ変換する（孤立サロゲートはU+FFFDに置換） */
  private def codePointsWithReplacement(s: String): Array[Int] = {
    val out = mutable.ArrayBuffer.empty[Int]
    var i = 0
    val n = s.length
    while (i < n) {
      val c1 = s.charAt(i).toInt
      if (c1 >= 0xd800 && c1 <= 0xdbff) {
        if (i + 1 < n) {
          val c2 = s.charAt(i + 1).toInt
          if (c2 >= 0xdc00 && c2 <= 0xdfff) {
            out += (((c1 - 0xd800) << 10) + (c2 - 0xdc00) + 0x10000)
            i += 2
          } else {
            out += ReplacementCodePoint
            i += 1
          }
        } else {
          out += ReplacementCodePoint
          i += 1
        }
      } else if (c1 >= 0xdc00 && c1 <= 0xdfff) {
        out += ReplacementCodePoint
        i += 1
      } else {
        out += c1
        i += 1
      }
    }
    out.toArray
  }

  /** 
   * 文字列を UTF-8 バイト列（各要素 0-255）へ変換する
   * `TextEncoder#encode`の挙動と同一
   */
  def encode(s: String): Array[Int] = {
    val out = mutable.ArrayBuffer.empty[Int]
    for (cp <- codePointsWithReplacement(s)) {
      if (cp <= 0x7f) {
        out += cp
      } else if (cp <= 0x7ff) {
        out += (0xc0 | (cp >> 6))
        out += (0x80 | (cp & 0x3f))
      } else if (cp <= 0xffff) {
        out += (0xe0 | (cp >> 12))
        out += (0x80 | ((cp >> 6) & 0x3f))
        out += (0x80 | (cp & 0x3f))
      } else {
        out += (0xf0 | (cp >> 18))
        out += (0x80 | ((cp >> 12) & 0x3f))
        out += (0x80 | ((cp >> 6) & 0x3f))
        out += (0x80 | (cp & 0x3f))
      }
    }
    out.toArray
  }
}
