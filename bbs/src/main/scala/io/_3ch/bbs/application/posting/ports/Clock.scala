package io._3ch.bbs.application.posting.ports

import scala.scalajs.js

/** 現在時刻と dat の日付欄 */
trait Clock {
  def nowMillis(): Double

  /** dat の日付欄。日本時間で 2023/11/15(水) 07:13:20 の形 */
  def datDate(unixtime: Double): String
}

object Clock {
  /** 実行環境の Date と Intl.DateTimeFormat を使う */
  val system: Clock = new Clock {
    private val options = js.Dynamic.literal(year = "numeric", month = "2-digit", day = "2-digit", weekday = "short",
      hour = "2-digit", minute = "2-digit", second = "2-digit", timeZone = "Asia/Tokyo")

    def nowMillis(): Double = js.Date.now()

    def datDate(unixtime: Double): String =
      js.Dynamic.newInstance(js.Dynamic.global.Intl.DateTimeFormat)("ja-JP", options).format(new js.Date(unixtime * 1000)).asInstanceOf[String]
  }
}
