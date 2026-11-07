package io._3ch.bbs.domain.admin

import io._3ch.bbs.domain.posting.moderation.{ThreadCreationSettingError, ThreadCreationSettings}
import io._3ch.common.admin.{BoardSettingsForm, BoardSettingsValues, SettingKind}
import io._3ch.common.domain.account.Phoebe

/** 板設定の項目が保存できない理由 */
enum SettingProblem {
  case LineBreak
  /** 0 以上の整数か空でない */
  case Number
  /** 候補にない値 */
  case Choice
  /** BBS_FR_LEVEL が off・log・0 以上の整数・空のどれでもない */
  case Level
  /** BBS_PHOEBE が 0〜10 か空でない */
  case Phoebe
  /** その他の行にフォームの項目と同じキーがある */
  case Duplicate(keys: List[String])
  case Creation(reason: ThreadCreationSettingError)
}

/** 板設定画面の値を検証する。誤りは項目名（fields.キー・extra）ごとに返す */
object BoardSettingsCheck {
  private def lineBreak(value: String): Boolean = value.contains('\n') || value.contains('\r')

  private def field(key: String, value: String, kind: SettingKind): Option[SettingProblem] = {
    val v = value.trim
    if (lineBreak(value)) Some(SettingProblem.LineBreak)
    else key match {
      case "BBS_FR_LEVEL" => Option.unless(v == "off" || v == "log" || v.matches("[0-9]*"))(SettingProblem.Level)
      case "BBS_PHOEBE" => Option.unless(v.isEmpty || (v.matches("[0-9]{1,2}") && v.toInt <= Phoebe.MaxLevel))(SettingProblem.Phoebe)
      case _ => kind match {
        case SettingKind.Text => None
        case SettingKind.Number => Option.unless(v.matches("[0-9]*"))(SettingProblem.Number)
        case SettingKind.Choice(values) => Option.unless(values.contains(v))(SettingProblem.Choice)
        case SettingKind.Check => Option.unless(v.isEmpty || v == "checked")(SettingProblem.Choice)
      }
    }
  }

  private def creationField(reason: ThreadCreationSettingError): String = reason match {
    case ThreadCreationSettingError.NonNegativeIntegerRequired(setting) => setting
    case ThreadCreationSettingError.AboveMaximum(setting, _) => setting
    case ThreadCreationSettingError.InvalidFormat(setting) => setting
    case ThreadCreationSettingError.PositiveAsnRequired => "BBS_THREAD_JUNBAN_AS_ONLY"
  }

  /** rendered は保存する SETTING.TXT。スレ立て規制はその本文全体で検証する */
  def check(values: BoardSettingsValues, rendered: String): Map[String, SettingProblem] = {
    val fields = BoardSettingsForm.fields.flatMap { f =>
      values.fields.get(f.key).filter(_.explicit).flatMap(v => field(f.key, v.value, f.kind)).map(s"fields.${f.key}" -> _)
    }
    val duplicated = BoardSettingsForm.duplicatedKeys(values.extra)
    val extra =
      if (values.extra.exists(lineBreak)) Some("extra" -> SettingProblem.LineBreak)
      else Option.when(duplicated.nonEmpty)("extra" -> SettingProblem.Duplicate(duplicated))
    val creation = if (fields.nonEmpty || extra.nonEmpty) None
      else ThreadCreationSettings.parseText(rendered).left.toOption.map(reason => s"fields.${creationField(reason)}" -> SettingProblem.Creation(reason))
    (fields ++ extra ++ creation).toMap
  }
}
