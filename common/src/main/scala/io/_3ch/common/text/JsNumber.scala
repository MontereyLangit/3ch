package io._3ch.common.text

/** JavaScript の `Number(s)` と `parseInt(s)`（基数省略）の文字列解釈。 */
object JsNumber {
  private def isJsSpace(c: Char): Boolean =
    c == '\t' || c == '\n' || c == '\u000b' || c == '\f' || c == '\r' || c == ' ' || c == ' ' || c == ' ' ||
      (c >= ' ' && c <= ' ') || c == ' ' || c == ' ' || c == ' ' || c == ' ' || c == '　' || c == '﻿'

  private def trim(s: String): String = s.dropWhile(isJsSpace).reverse.dropWhile(isJsSpace).reverse

  private def isDigits(s: String, radix: Int): Boolean = s.nonEmpty && s.forall(c => Character.digit(c, radix) >= 0 && c < '\u0080')

  private def radixValue(digits: String, radix: Int): Double = BigInt(digits, radix).toDouble

  /** `Number(s)`。空白だけなら 0、解釈できなければ NaN。 */
  def parse(s: String): Double = {
    val t = trim(s)
    val decimal = "[+-]?(?:[0-9]+\\.?[0-9]*|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?"
    if (t.isEmpty) 0d
    else if (t == "Infinity" || t == "+Infinity") Double.PositiveInfinity
    else if (t == "-Infinity") Double.NegativeInfinity
    else if (t.matches(decimal)) java.lang.Double.parseDouble(t)
    else if (t.length > 2 && t(0) == '0') {
      val radix = t(1) match {
        case 'x' | 'X' => 16
        case 'o' | 'O' => 8
        case 'b' | 'B' => 2
        case _ => 0
      }
      if (radix != 0 && isDigits(t.drop(2), radix)) radixValue(t.drop(2), radix) else Double.NaN
    } else Double.NaN
  }

  /** `parseInt(s)`。先頭の整数部分だけを読み、`0x` 始まりは 16 進。数字が無ければ NaN。 */
  def parseInt(s: String): Double = {
    val t = trim(s)
    val negative = t.startsWith("-")
    val unsigned = if (t.startsWith("-") || t.startsWith("+")) t.drop(1) else t
    val (radix, body) = if (unsigned.startsWith("0x") || unsigned.startsWith("0X")) (16, unsigned.drop(2)) else (10, unsigned)
    val digits = body.takeWhile(c => Character.digit(c, radix) >= 0 && c < '\u0080')
    if (digits.isEmpty) Double.NaN
    else {
      val value = if (radix == 10) java.lang.Double.parseDouble(digits) else radixValue(digits, radix)
      if (negative) -value else value
    }
  }
}
