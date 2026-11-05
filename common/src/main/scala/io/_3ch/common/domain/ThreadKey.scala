package io._3ch.common.domain

/**
 * スレッド番号
 * @param value 9桁～10桁のUNIXタイムスタンプ（スレ立て時刻）
 */
case class ThreadKey private (
  value: Long
)

object ThreadKey {

  /** 十進の正規表記（先頭の 0 や + なし）だけを読み、桁数を create で検査する */
  def parse(text: String): Either[String, ThreadKey] = {
    text.toLongOption.filter(_.toString == text).toRight("ThreadKey must be a canonical decimal number").flatMap(create)
  }

  /** 9〜10 桁ならスレッド番号にする */
  def create(value: Long): Either[String, ThreadKey] = {
    val isValidValue = value >= 100000000L && value <= 9999999999L
    Either.cond(isValidValue, ThreadKey(value), "ThreadKey must be between 9 and 10 digits")
  }
}
