package ch3s.common.text

import scala.collection.mutable

/**
 * WHATWG Encoding Standard 準拠の UTF-8 エンコーダを手書きでポートしたもの。
 *
 * `src/common.js` の `md5()`（内部で `new TextEncoder().encode(s)` を使う）、および
 * `sha1Base64()` / `hash()`（同様に `TextEncoder` を使う）と**同一のバイト列**を
 * JVM/JS 双方で再現するために用意した。
 *
 * `md5` は `common`（cross project, 純粋関数のみ）に置くため、JVM の `java.nio.charset`
 * に依存できない（Scala.js 側には無いAPIであり、かつ孤立サロゲートの扱いが
 * `TextEncoder` と厳密に一致するとは限らない）。そのため、この最小限の UTF-8
 * エンコーダを cross project 内に自前で持つ。
 *
 * 孤立サロゲート（ペアになっていない High/Low サロゲート）は、`TextEncoder` と同じく
 * 1つの `U+FFFD`（3バイト `EF BF BD`）に置き換える（実測: Node の `TextEncoder` で確認済み）。
 * これは `sjis.encode` が使う `[...str]` の走査（孤立サロゲートを代替しない）とは
 * 意図的に異なる実装なので、[[ch3s.common.sjis.Sjis]] 側のコードポイント走査とは共有しない。
 */
object Utf8 {

  private val ReplacementCodePoint = 0xfffd

  /** 文字列をUTF-16コード単位から「WHATWG的コードポイント列」へ変換する（孤立サロゲートはU+FFFDに置換）。 */
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

  /** 文字列を UTF-8 バイト列（各要素 0-255）へ変換する。`TextEncoder#encode` の挙動と同一。 */
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
