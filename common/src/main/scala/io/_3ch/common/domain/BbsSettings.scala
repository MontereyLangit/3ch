package io._3ch.common.domain

import io._3ch.common.text.JsNumber

import scala.collection.immutable.ListMap

/**
 * 板やスレの設定
 * @param value 設定名と設定値のマップ
 */
case class BbsSettings (
  value: ListMap[String, String]
) {

  /**
    * SETTING.TXT形式の文字列にする
    *
    * @return `"BBS_TITLE=テスト板＠3ch掲示板\nBBS_THREAD_TATESUGI=16"` …
    */
  override def toString: String = value.map { case (k, v) => s"$k=$v" }.mkString("", "\n", "\n")

  /** 設定値。無ければ None */
  def get(name: String): Option[String] = value.get(name)

  /** 値が空でなければその値、無いか空なら default */
  def orElse(name: String, default: String): String = get(name).filter(_.nonEmpty).getOrElse(default)

  /** 値がちょうど checked か */
  def checked(name: String): Boolean = get(name).contains("checked")

  /** 値を JavaScript の Number で読む。無ければ default（空文字列は 0、数でなければ NaN） */
  def number(name: String, default: Double): Double = get(name).fold(default)(JsNumber.parse)

  /** [[orElse]] の値を JavaScript の parseInt で読む */
  def int(name: String, default: String): Double = JsNumber.parseInt(orElse(name, default))

  /** name の値を差し替える。無ければ末尾に足す */
  def updated(name: String, v: String): BbsSettings = BbsSettings(value.updated(name, v))

  /** other の値で上書きする。既存のキーは位置を保ち、新しいキーは末尾に足す */
  def merged(other: BbsSettings): BbsSettings = BbsSettings(other.value.foldLeft(value) { case (acc, (k, v)) => acc.updated(k, v) })

  /** スレ設定の保存形式（キー=値<> の列）。キーは JavaScript のオブジェクトと同じく、配列の添字になるものを数の順で先に並べる */
  def toThreadSettings: String = {
    val (indices, names) = value.toSeq.partition { case (k, _) => BbsSettings.isArrayIndex(k) }
    (indices.sortBy { case (k, _) => k.toLong } ++ names).map { case (k, v) => s"$k=$v<>" }.mkString
  }
}

object BbsSettings {
  private def isArrayIndex(key: String): Boolean =
    key.nonEmpty && key.length <= 10 && key.forall(c => c >= '0' && c <= '9') && (key == "0" || key.head != '0') && key.toLong < 4294967295L

  /**
    * SETTING.TXT形式の文字列をパースして設定オブジェクトを生成
    *
    * @param text `"BBS_TITLE=テスト板＠3ch掲示板\nBBS_THREAD_TATESUGI=16\n"` …
    * @param board 板ID
    * @return 板・スレ設定
    */
  def create(text: String, board: BoardId): BbsSettings = parse(text.replace("<DIR_NAME>", board.value), "\n")

  /**
    * `separator` 区切りの `キー=値` 列をパースする
    * 空行と `=` を含まない行は無視し、値が空の行は空文字列とする
    * @param text スレ設定なら `"BBS_TITLE=テスト板<>BBS_NONAME_NAME=名無し<>"` …
    * @param separator 区切り文字列
    * @return 板・スレ設定
    */
  def parse(text: String, separator: String): BbsSettings = {
    val value = text
      .split(separator)
      .map(_.trim())
      .filter(_.nonEmpty)
      .foldLeft(ListMap.empty[String, String]) { (acc, t) =>
        t.split("=", 2) match {
          case Array(k, v) => acc.updated(k, v.trim())
          case _ => acc
        }
      }

    BbsSettings(value)
  }
}
