package io._3ch.bbs.durable.codec

import io._3ch.common.domain.BbsSettings

/** スレ設定の保存形式（キー=値<> の列）の一項目の読み書き */
object ThreadSettingsCodec {
  /** 項目の値。同じキーが複数あれば後の値 */
  def value(settings: String, name: String): Option[String] = BbsSettings.parse(settings, "<>").get(name)

  /** 項目を末尾に置き直す。ほかの項目は書かれたまま残す */
  def updated(settings: String, name: String, value: String): String =
    (settings.split("<>", -1).filter(entry => entry.nonEmpty && !entry.startsWith(name + "=")) :+ s"$name=$value").map(_ + "<>").mkString
}
