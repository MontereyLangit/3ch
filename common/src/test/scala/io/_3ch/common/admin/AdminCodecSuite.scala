package io._3ch.common.admin

class AdminCodecSuite extends munit.FunSuite {
  private def roundTrip[A](value: A, encode: A => Json, decode: Json => AdminCodec.Decoded[A]): Unit =
    assertEquals(Json.parse(Json.render(encode(value))).flatMap(decode), Right(value))

  private val values = BoardSettingsForm.read("BBS_TITLE=板\nBBS_FR_LEVEL=\nUNKNOWN=1\n")

  test("各 DTO は JSON と往復する") {
    roundTrip(BoardList(List("a", "b")), AdminCodec.boardList, AdminCodec.boardList)
    roundTrip(BoardSettingsView("sample", values, "<b>案内</b>", Some("e1"), None, BoardRules(List("^sample$<><><>deny"), Nil)),
      AdminCodec.boardSettings, AdminCodec.boardSettings)
    roundTrip(BoardSettingsUpdate(values, "案内", None, Some("e2")), AdminCodec.boardSettingsUpdate, AdminCodec.boardSettingsUpdate)
    roundTrip(BoardSettingsSaved(Some("e3"), None, partial = true), AdminCodec.boardSettingsSaved, AdminCodec.boardSettingsSaved)
    roundTrip(ThreadList("sample", List(ThreadSummary("1800000000", "1800000000.dat<>題 (1)")), List("1700000000")), AdminCodec.threadList, AdminCodec.threadList)
    roundTrip(ThreadDetail("sample", "1800000000", ThreadState.Stopped, Some("other/1800000001"), None,
      List(ResView(1, "名 &lt;", "", "日付", "本文 <br> 二", ResEditFields("名 <", "", "本文\n二"))), "dat", "log", Some("legacy"), "BBS_HEISA=checked<>"),
      AdminCodec.threadDetail, AdminCodec.threadDetail)
    roundTrip(TextFile("<><><>deny", None), AdminCodec.textFile, AdminCodec.textFile)
    roundTrip(Saved(Some("e4")), AdminCodec.saved, AdminCodec.saved)
    roundTrip(AdminError(AdminErrorCode.Invalid, "入力が不正です。", Map("head" -> "理由")), AdminCodec.error, AdminCodec.error)
  }

  test("スレの操作はすべて往復する") {
    List(ThreadAction.Stop, ThreadAction.Resume, ThreadAction.Delete, ThreadAction.Move("other", "1800000001"),
      ThreadAction.EditRes(2, "名", "sage", "本文"), ThreadAction.DeleteRes(3)).foreach { action =>
      roundTrip(action, AdminCodec.threadAction, AdminCodec.threadAction)
    }
  }

  test("板設定の保存は fields に無い項目を未設定にし、知らないキーと ETag の欠落を誤りにする") {
    def decode(text: String) = Json.parse(text).flatMap(AdminCodec.boardSettingsUpdate)
    val update = decode("""{"fields":{"BBS_TITLE":{"value":"板","explicit":true}},"extra":[],"head":"","settingsEtag":null,"headEtag":"e"}""")
    assertEquals(update.map(_.values.fields("BBS_TITLE")), Right(SettingValue("板", explicit = true)))
    assertEquals(update.map(_.values.fields("BBS_SAMBA24")), Right(SettingValue("10", explicit = false)))
    assertEquals(update.map(_.settingsEtag), Right(None))
    assertEquals(decode("""{"fields":{"BBS_UNKNOWN":{"value":"","explicit":true}},"extra":[],"head":"","settingsEtag":null,"headEtag":null}"""), Left("fields.BBS_UNKNOWN"))
    assertEquals(decode("""{"fields":{"BBS_TITLE":{"value":1,"explicit":true}},"extra":[],"head":"","settingsEtag":null,"headEtag":null}"""), Left("fields.BBS_TITLE"))
    assertEquals(decode("""{"fields":{},"extra":[],"head":"","headEtag":null}"""), Left("settingsEtag"))
    assertEquals(decode("""{"fields":{},"extra":[1],"head":"","settingsEtag":null,"headEtag":null}"""), Left("extra"))
  }

  test("レス番号は整数だけを受け付ける") {
    def decode(text: String) = Json.parse(text).flatMap(AdminCodec.threadAction)
    assertEquals(decode("""{"action":"deleteRes","number":1.5}"""), Left("number"))
    assertEquals(decode("""{"action":"deleteRes","number":"1"}"""), Left("number"))
    assertEquals(decode("""{"action":"unknown"}"""), Left("action"))
    assertEquals(decode("""{"action":"editRes","number":1,"name":"","mail":""}"""), Left("message"))
  }
}
