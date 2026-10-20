package ch3s.common.domain

/**
 * 板やスレの設定
 * @param value 設定名と設定値のマップ。
 */
case class BbsSettings private (
  value: Map[String, String]
) {

  /**
    * SETTING.TXT形式の文字列に変換する
    *
    * @return `"BBS_TITLE=テスト板＠3ch掲示板\nBBS_THREAD_TATESUGI=16\n"` …
    */
  def convertToText(): String = {
    value.map { case (k, v) => s"$k=$v" }.mkString("\n")
  }
}

object BbsSettings {
  def create(value: Map[String, String]): BbsSettings = BbsSettings(value)

  /**
    * SETTING.TXT形式の文字列をパースして設定オブジェクトを生成
    *
    * @param text `"BBS_TITLE=テスト板＠3ch掲示板\nBBS_THREAD_TATESUGI=16\n"` …
    * @param bbs 板ディレクトリ名 TODO: BoardIdオブジェクトを使うよう修正
    * @return 板・スレ設定
    */
  def create(text: String, bbs: String): BbsSettings = {
    val value = text
    .replace("<DIR_NAME>", bbs)
    .split(", ")
    .map { t =>
      val Array(k, v) = t.trim().split("=")
      k -> v
    }
    .toMap

    create(value)
  }
}
