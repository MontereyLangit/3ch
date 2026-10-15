package ch3s.common.domain

/**
  * スレッド番号
  * @param value 9桁～10桁のUNIXタイムスタンプ（スレ立て時刻）。
  */
case class ThreadKey private (
    value: Long
)

object ThreadKey {
    def create(value: Long): Either[String, ThreadKey] = {
        val isValidValue = value.toString().length() >= 9 || value.toString().length() <= 10
        Either.cond(isValidValue, ThreadKey(value), "ThreadKey must be between 9 and 10 digits")
    }
}
