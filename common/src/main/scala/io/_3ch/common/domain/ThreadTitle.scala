package io._3ch.common.domain

/**
 * スレッドタイトル
 */
case class ThreadTitle private (
  value: String
)

object ThreadTitle {
  /** スレッドタイトルにする。今は検査せず常に Right */
  def create(value: String): Either[String, ThreadTitle] = {
    val isValid = true
    Either.cond(isValid, ThreadTitle(value), "不正な値")
  }
}
