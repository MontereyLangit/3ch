package io._3ch.bbs.domain.posting.moderation

/** スレ立て設定を読み取れない理由 */
enum ThreadCreationSettingError {
  /** 0 以上の整数でない */
  case NonNegativeIntegerRequired(setting: String)
  /** 上限 maximum を超えている */
  case AboveMaximum(setting: String, maximum: Long)
  /** 件数/秒数 の形で読めない */
  case InvalidFormat(setting: String)
  /** BBS_THREAD_JUNBAN_AS_ONLY の ASN が 1 以上でない */
  case PositiveAsnRequired
}
