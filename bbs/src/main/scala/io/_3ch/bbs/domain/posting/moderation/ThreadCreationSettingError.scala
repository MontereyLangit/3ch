package io._3ch.bbs.domain.posting.moderation

/** スレ立て設定を読み取れない理由。 */
enum ThreadCreationSettingError {
  case NonNegativeIntegerRequired(setting: String)
  case AboveMaximum(setting: String, maximum: Long)
  case InvalidFormat(setting: String)
  case PositiveAsnRequired
}
