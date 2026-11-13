package io._3ch.common.text

/** JavaScript の String.prototype.trim・trimEnd と同じ空白の扱い */
object JsText {
  /** JavaScript の WhiteSpace と LineTerminator に当たるか */
  def isWhitespace(c: Char): Boolean =
    c == '\t' || c == '\n' || c == '\u000b' || c == '\f' || c == '\r' || c == ' ' || c == ' ' || c == ' ' ||
      (c >= ' ' && c <= ' ') || c == ' ' || c == ' ' || c == ' ' || c == ' ' || c == '　' || c == '﻿'

  /** 前後の空白を除く */
  def trim(s: String): String = trimEnd(s.dropWhile(isWhitespace))

  /** 末尾の空白を除く */
  def trimEnd(s: String): String = {
    val end = s.lastIndexWhere(c => !isWhitespace(c))
    s.substring(0, end + 1)
  }
}
