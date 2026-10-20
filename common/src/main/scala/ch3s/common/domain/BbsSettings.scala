package ch3s.common.domain

import scala.collection.immutable.VectorMap

/**
 * 板やスレの設定
 * @param value 設定名と設定値のマップ。キーは SETTING.TXT の記載順を保つ（スレ初期設定の保存順になるため）。
 */
case class BbsSettings private (
  value: Map[String, String]
) {

  /**
    * SETTING.TXT形式の文字列に変換する
    *
    * @return `"BBS_TITLE=テスト板＠3ch掲示板\nBBS_THREAD_TATESUGI=16"` …
    */
  def convertToText(): String = {
    value.map { case (k, v) => s"$k=$v" }.mkString("\n")
  }
}

object BbsSettings {
  def create(value: Map[String, String]): BbsSettings = BbsSettings(value)

  /**
    * SETTING.TXT形式の文字列をパースして設定オブジェクトを生成
    * 空行と `=` を含まない行は無視し、値が空の行は空文字列とする。
    *
    * @param text `"BBS_TITLE=テスト板＠3ch掲示板\nBBS_THREAD_TATESUGI=16\n"` …
    * @param bbs 板ディレクトリ名 TODO: BoardIdオブジェクトを使うよう修正
    * @return 板・スレ設定
    */
  def create(text: String, bbs: String): BbsSettings = parse(text.replace("<DIR_NAME>", bbs), "\n")

  /**
    * `separator` 区切りの `キー=値` 列をパースする。行の扱いは [[create(text:String,bbs:String)]] と同じ
    *
    * @param text スレ設定なら `"BBS_TITLE=テスト板<>BBS_NONAME_NAME=名無し<>"` …
    * @param separator 区切り文字列（正規表現として解釈される）
    * @return 板・スレ設定
    */
  def parse(text: String, separator: String): BbsSettings = {
    val value = text
      .split(separator)
      .map(_.trim())
      .filter(_.nonEmpty)
      .foldLeft(VectorMap.empty[String, String]) { (acc, t) =>
        t.split("=", 2) match {
          case Array(k, v) => acc.updated(k, v.trim())
          case _ => acc
        }
      }

    create(value)
  }
}
