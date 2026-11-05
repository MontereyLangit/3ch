package io._3ch.common.domain.posting

/** dat 行と log 行の書式 */
object DatLineFormatter {
  /** 本文の最初の <br> だけ前後に空白を入れる。JavaScript の文字列 replace と同じく 1 か所だけ */
  def spaceFirstBreak(message: String): String = {
    val index = message.indexOf("<br>")
    if (index < 0) message else message.take(index) + " <br> " + message.drop(index + 4)
  }

  /** dat 行 名前<><>日付<> 本文 <>題名。題名はスレ立てのときだけ渡し、返信は空にする */
  def datLine(name: String, date: String, message: String, title: String): String = s"$name<><>$date<> $message <>$title"

  /** log 行 レス番号<>投稿者情報。レス番号は Thread DO が確定した番号に置き換える */
  def logLine(number: String, userInfo: String): String = s"$number<>$userInfo"
}
