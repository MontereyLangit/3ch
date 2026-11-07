package io._3ch.common.admin

class BoardSettingsFormSuite extends munit.FunSuite {
  import BoardSettingsForm.{read, render}

  private def set(values: BoardSettingsValues, key: String, value: String): BoardSettingsValues =
    values.copy(fields = values.fields.updated(key, SettingValue(value, explicit = true)))

  test("書かれた項目は明示値、無い項目は既定値の未設定として読む") {
    val values = read("BBS_TITLE=テスト板\nBBS_FR_LEVEL=\n")
    assertEquals(values.fields("BBS_TITLE"), SettingValue("テスト板", explicit = true))
    assertEquals(values.fields("BBS_FR_LEVEL"), SettingValue("", explicit = true))
    assertEquals(values.fields("BBS_MAX_MENU_THREAD"), SettingValue("700", explicit = false))
    assertEquals(values.fields("BBS_RENTOU_PASS"), SettingValue("privileged", explicit = false))
    assertEquals(values.fields.keySet, BoardSettingsForm.keys)
  }

  test("キー・値の前後の空白と CR を除き、最初の = より後ろ全体を値にし、同じキーは後勝ち") {
    val values = read(" BBS_TITLE = a=b \r\nBBS_NONAME_NAME=一\nBBS_NONAME_NAME=二\r\n")
    assertEquals(values.fields("BBS_TITLE").value, "a=b")
    assertEquals(values.fields("BBS_NONAME_NAME").value, "二")
    assertEquals(values.extra, Nil)
  }

  test("未知のキーと = の無い行はその他の行として順に残し、空行は捨てる") {
    val values = read("# 説明\nBBS_TITLE=板\n\nUNKNOWN=1\n  \nBBS_ID=checked\r\n")
    assertEquals(values.extra, List("# 説明", "UNKNOWN=1", "BBS_ID=checked"))
  }

  test("変更しなければ行順も含めて元の内容に戻る") {
    val text = "BBS_TITLE=板\n# コメント\nUNKNOWN=1\nBBS_FR_LEVEL=\nBBS_ADD_THREAD=on\nBBS_HEISA=checked\n"
    assertEquals(render(text, read(text)), text)
  }

  test("既定の SETTING.TXT も変更しなければ元に戻る") {
    val text = io._3ch.common.domain.BbsSettings.defaultText.replace("<DIR_NAME>", "sample")
    assertEquals(render(text, read(text)), text)
  }

  test("触っていない未設定の項目は書かず、値を入れた項目は末尾に足す") {
    val text = "BBS_TITLE=板\nUNKNOWN=1\n"
    val values = set(read(text), "BBS_SAMBA24", "30")
    assertEquals(render(text, values), "BBS_TITLE=板\nUNKNOWN=1\nBBS_SAMBA24=30\n")
    assert(!render(text, read(text)).contains("BBS_FR_LEVEL"))
  }

  test("明示した空の値と未設定を区別して書く") {
    val text = "BBS_TITLE=板\n"
    assertEquals(render(text, set(read(text), "BBS_FR_LEVEL", "")), "BBS_TITLE=板\nBBS_FR_LEVEL=\n")
  }

  test("既定に戻した項目は行ごと消える") {
    val text = "BBS_TITLE=板\nBBS_SAMBA24=30\nUNKNOWN=1\n"
    val values = read(text)
    val reset = values.copy(fields = values.fields.updated("BBS_SAMBA24", SettingValue("10", explicit = false)))
    assertEquals(render(text, reset), "BBS_TITLE=板\nUNKNOWN=1\n")
  }

  test("項目は最初の行の位置に 1 度だけ書き、重複した行は消える") {
    val text = "BBS_TITLE=一\nA=1\nBBS_TITLE=二\nB=2\n"
    assertEquals(render(text, set(read(text), "BBS_TITLE", "三")), "BBS_TITLE=三\nA=1\nB=2\n")
  }

  test("その他の行は元のその他の行の位置に順に入れ、余りは末尾、足りなければ詰める") {
    val text = "A=1\nBBS_TITLE=板\nB=2\n"
    val values = read(text)
    assertEquals(render(text, values.copy(extra = List("X=1", "Y=2", "Z=3"))), "X=1\nBBS_TITLE=板\nY=2\nZ=3\n")
    assertEquals(render(text, values.copy(extra = List("X=1"))), "X=1\nBBS_TITLE=板\n")
    assertEquals(render(text, values.copy(extra = List("", "X=1", " "))), "X=1\nBBS_TITLE=板\n")
  }

  test("空のファイルからは明示値だけを書く") {
    assertEquals(render("", read("")), "")
    assertEquals(render("", set(read(""), "BBS_TITLE", "板")), "BBS_TITLE=板\n")
  }

  test("その他の行にフォームの項目と同じキーがあれば重複として返す") {
    assertEquals(BoardSettingsForm.duplicatedKeys(List("UNKNOWN=1", " BBS_TITLE =x", "BBS_TITLE=y", "# BBS_HEISA", "BBS_HEISA")), List("BBS_TITLE"))
    assertEquals(BoardSettingsForm.duplicatedKeys(List("UNKNOWN=1", "UNKNOWN=2")), Nil)
  }
}
