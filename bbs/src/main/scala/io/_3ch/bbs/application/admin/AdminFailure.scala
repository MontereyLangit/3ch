package io._3ch.bbs.application.admin

import io._3ch.bbs.domain.admin.SettingProblem
import io._3ch.bbs.domain.posting.moderation.{ModerationRuleError, ThreadCreationSettingError}

/** 管理画面で競合を調べるファイル */
enum AdminFile {
  case Settings, Head, Rules, Menu
}

/** 管理操作が拒否された理由。文言への変換は handler で行う */
enum AdminFailure {
  /** 板名が不正。field は入力の項目名 */
  case InvalidBoard(field: String)
  /** key が 10 桁の数字でない */
  case InvalidKey(field: String)
  /** スレ立て規制フォームの入力に改行がある */
  case CreationLineBreak
  case CreationSettings(reason: ThreadCreationSettingError)
  case Settings(problems: Map[String, SettingProblem])
  case Rules(error: ModerationRuleError)
  /** 読込後に他の操作で保存された */
  case Conflict(file: AdminFile)
  case ThreadMissing
  /** Thread DO が拒否した。message は DO の文言 */
  case ThreadRefused(message: String)
}
