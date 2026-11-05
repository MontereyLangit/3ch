package io._3ch.common.domain

/**
 * 板ID
 * @param value [a-zA-Z0-9]の文字列。1文字以上16文字以下。
 */
case class BoardId private (
  value: String
)

object BoardId {
  /** 英数字 1〜16 文字なら板 ID にする */
  def create(value: String): Either[String, BoardId] = {
    val isValidValue = value.matches("[a-zA-Z0-9]{1,16}")
    Either.cond(isValidValue, BoardId(value), "BoardId must be between 1 and 16 digits string[a-zA-Z0-9]")
  }
}
