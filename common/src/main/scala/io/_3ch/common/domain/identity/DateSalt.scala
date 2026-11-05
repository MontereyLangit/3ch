package io._3ch.common.domain.identity

import io._3ch.common.text.JsNumber

/** 識別子の日付窓。時刻と周期は従来の JavaScript の数値解釈で読む。 */
object DateSalt {
  def window(unixtime: String, resetSeconds: String): Double = math.floor((JsNumber.parseInt(unixtime) + 32400) / JsNumber.parse(resetSeconds))
}
