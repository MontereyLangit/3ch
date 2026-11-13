package io._3ch.common.domain.posting

import io._3ch.common.text.JsText

/** 過去ログ目次（スレ帯の subject.txt と板のスレ帯一覧）と headline.txt の本文 */
object ArchiveIndexText {
  /** スレ帯の目次の先頭に新しいスレを足す。current は今の本文で、1 行目の更新時刻は置き換える */
  def threadIndex(unixtime: String, key: String, subject: String, name: String, shikibetsu: String, message: String, current: Option[String]): String =
    s"$unixtime\n$key<>$subject<>$name<>$shikibetsu<><>$message\n${body(current)}"

  /** 板のスレ帯一覧の先頭に key の帯（上 4 桁）を足す */
  def bandList(unixtime: String, key: String, current: Option[String]): String =
    s"$unixtime\n${key.take(4)}<>$key\n${body(current)}"

  /**
    * headline.txt の先頭に投稿を足し、今の 2〜301 行目のうち板名が正しく題名があり別の板のものを前後の空白を除いて残す
    * 板の題名が無ければ undefined と書く
    */
  def headline(unixtime: String, bbs: String, title: Option[String], key: String, message: String, current: Option[String]): String = {
    val head = s"$unixtime\n$bbs<>${title.getOrElse("undefined")}<>$unixtime<>$key<>${message.take(30)}\n"
    val kept = current.toSeq
      .flatMap(_.split("\n", -1).slice(1, 301))
      .map(line => JsText.trim(line).split("<>", -1))
      .filter(f => f.length >= 5 && isBoardName(f(0)) && f(1).nonEmpty && f(0) != bbs)
      .map(_.take(5).mkString("", "<>", "\n"))
    head + kept.mkString
  }

  private def body(current: Option[String]): String = current.fold("")(_.split("\n", -1).drop(1).mkString("\n"))

  private def isBoardName(s: String): Boolean =
    s.nonEmpty && s.forall(c => (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_')
}
