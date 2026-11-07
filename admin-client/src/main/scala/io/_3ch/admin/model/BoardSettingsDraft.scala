package io._3ch.admin.model

import io._3ch.common.admin.{BoardSettingsForm, BoardSettingsUpdate, BoardSettingsValues, BoardSettingsView, SettingValue}

/** 板設定画面の編集中の値。original は読込時（または保存後）の値 */
final case class BoardSettingsDraft(original: BoardSettingsView, fields: Map[String, SettingValue], extra: String, head: String) {
  /** 項目の値。フォームに無いキーは未設定の空 */
  def value(key: String): SettingValue = fields.getOrElse(key, SettingValue("", explicit = false))

  /** 値を入れる。未設定の項目に空を入れたときは未設定のまま */
  def set(key: String, text: String): BoardSettingsDraft = {
    val current = value(key)
    if (!current.explicit && text.isEmpty) this else copy(fields = fields.updated(key, SettingValue(text, explicit = true)))
  }

  /** 既定に戻す（キーを書かない） */
  def reset(key: String): BoardSettingsDraft =
    copy(fields = fields.updated(key, SettingValue(BoardSettingsDraft.default(key), explicit = false)))

  def setExtra(text: String): BoardSettingsDraft = copy(extra = text)

  def setHead(text: String): BoardSettingsDraft = copy(head = text)

  /** 読込時から変わった項目の数。head.txt とその他の設定はそれぞれ 1 つと数える */
  def changes: Int = {
    val base = original.values.fields
    BoardSettingsForm.fields.count(f => value(f.key) != base.getOrElse(f.key, SettingValue(f.default, explicit = false))) +
      (if (extraLines != original.values.extra) 1 else 0) + (if (head != original.head) 1 else 0)
  }

  /** その他の設定の行。空行は書かない */
  def extraLines: List[String] = extra.split("\n", -1).toList.map(_.stripSuffix("\r")).filter(_.trim.nonEmpty)

  /** 保存の要求 */
  def update: BoardSettingsUpdate =
    BoardSettingsUpdate(BoardSettingsValues(fields, extraLines), head, original.settingsEtag, original.headEtag)

  /** SETTING.TXT だけを保存した後の状態。head.txt は編集中のまま残す */
  def settingsSaved(settingsEtag: Option[String]): BoardSettingsDraft = {
    val values = BoardSettingsValues(fields, extraLines)
    copy(original = original.copy(values = values, settingsEtag = settingsEtag), extra = extraLines.mkString("\n"))
  }
}

object BoardSettingsDraft {
  def apply(view: BoardSettingsView): BoardSettingsDraft = BoardSettingsDraft(view, view.values.fields, view.values.extra.mkString("\n"), view.head)

  /** 項目の既定値 */
  def default(key: String): String = BoardSettingsForm.fields.find(_.key == key).fold("")(_.default)
}
