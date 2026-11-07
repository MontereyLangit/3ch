package io._3ch.admin

import io._3ch.admin.model.BoardSettingsDraft
import io._3ch.common.admin.{BoardRules, BoardSettingsForm, BoardSettingsView, SettingValue}
import munit.FunSuite

class BoardSettingsDraftSuite extends FunSuite {
  private val view = BoardSettingsView("sample", BoardSettingsForm.read("BBS_TITLE=板\nBBS_HEISA=\nUNKNOWN=1\n# memo\n"), "案内", Some("s1"), None, BoardRules(Nil, Nil))
  private val draft = BoardSettingsDraft(view)

  test("読込直後は変更なしで、要求は読込時の値と ETag のまま") {
    assertEquals(draft.changes, 0)
    assertEquals(draft.extra, "UNKNOWN=1\n# memo")
    val update = draft.update
    assertEquals(update.values, view.values)
    assertEquals((update.head, update.settingsEtag, update.headEtag), ("案内", Some("s1"), None))
  }

  test("値を入れた項目は明示値になり、未設定の項目に空を入れても未設定のまま") {
    val edited = draft.set("BBS_SAMBA24", "").set("BBS_FR_SECOND", "5")
    assertEquals(edited.value("BBS_SAMBA24"), SettingValue("10", explicit = false))
    assertEquals(edited.value("BBS_FR_SECOND"), SettingValue("5", explicit = true))
    assertEquals(edited.changes, 1)
  }

  test("明示値は空にしても明示した空として残り、既定に戻すと未設定になる") {
    val cleared = draft.set("BBS_TITLE", "")
    assertEquals(cleared.value("BBS_TITLE"), SettingValue("", explicit = true))
    assertEquals(cleared.changes, 1)
    val reset = cleared.reset("BBS_TITLE")
    assertEquals(reset.value("BBS_TITLE"), SettingValue("", explicit = false))
    assertEquals(draft.reset("BBS_HEISA").value("BBS_HEISA"), SettingValue("", explicit = false))
  }

  test("元の値に戻した項目は数えず、head.txt とその他の設定はそれぞれ 1 件") {
    assertEquals(draft.set("BBS_TITLE", "別").set("BBS_TITLE", "板").changes, 0)
    assertEquals(draft.setHead("新").setExtra("UNKNOWN=2\n\n# memo\n").changes, 2)
    assertEquals(draft.setExtra("UNKNOWN=1\r\n\n# memo\n").changes, 0, "空行と CR は数えない")
    assertEquals(draft.setExtra("UNKNOWN=2").update.values.extra, List("UNKNOWN=2"))
  }

  test("SETTING.TXT だけ保存した後は head.txt の変更だけが残る") {
    val saved = draft.set("BBS_TITLE", "新").setHead("新しい案内").settingsSaved(Some("s2"))
    assertEquals(saved.changes, 1)
    assertEquals(saved.update.settingsEtag, Some("s2"))
    assertEquals(saved.update.headEtag, None)
  }
}
