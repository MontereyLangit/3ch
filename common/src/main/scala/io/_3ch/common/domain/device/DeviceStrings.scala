package io._3ch.common.domain.device

/** 端末情報の前後空白を原本のKotlin 2.3.21と同じ範囲で取り除く */
private[device] object DeviceStrings {
  private def whitespace(c: Char): Boolean =
    (c >= '\t' && c <= '\r') || (c >= '\u001c' && c <= ' ') || c == '\u00a0' || c == '\u1680' ||
      (c >= '\u2000' && c <= '\u200a') || c == '\u2028' || c == '\u2029' || c == '\u202f' || c == '\u205f' || c == '\u3000'

  def trim(value: String): String = {
    val start = value.indexWhere(c => !whitespace(c))
    if (start < 0) "" else value.substring(start, value.lastIndexWhere(c => !whitespace(c)) + 1)
  }
}
