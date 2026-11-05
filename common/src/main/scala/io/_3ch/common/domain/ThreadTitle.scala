package io._3ch.common.domain

/**
 * スレッドタイトル
 */
case class ThreadTitle private (
  value: String
)

object ThreadTitle {
  def create(value: String): Either[String, ThreadTitle] = {
    val isValid = true
    Either.cond(isValid, ThreadTitle(value), "不正な値")
  }
}
