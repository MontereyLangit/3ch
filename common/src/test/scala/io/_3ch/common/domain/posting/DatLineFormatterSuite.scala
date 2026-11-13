package io._3ch.common.domain.posting

class DatLineFormatterSuite extends munit.FunSuite {
  test("最初の <br> だけ前後に空白を入れる") {
    assertEquals(DatLineFormatter.spaceFirstBreak("a<br>b<br>c"), "a <br> b<br>c")
    assertEquals(DatLineFormatter.spaceFirstBreak("<br>"), " <br> ")
    assertEquals(DatLineFormatter.spaceFirstBreak("改行なし"), "改行なし")
    assertEquals(DatLineFormatter.spaceFirstBreak(""), "")
  }

  test("dat 行は本文の前後に半角空白を置き、題名を末尾に付ける") {
    val date = "2023/11/15(水) 07:13:20"
    assertEquals(DatLineFormatter.datLine("名無し </b>()<b>", date, "本文", "スレタイ"), s"名無し </b>()<b><><>$date<> 本文 <>スレタイ")
    assertEquals(DatLineFormatter.datLine("n", date, "", ""), s"n<><>$date<>  <>")
  }

  test("log 行はレス番号と投稿者情報") {
    assertEquals(DatLineFormatter.logLine("2", "UID: IP:h AS0-JP,,Device: UA:"), "2<>UID: IP:h AS0-JP,,Device: UA:")
  }

  test("log 行の先頭のレス番号だけを確定した番号に置き換える") {
    assertEquals(DatLineFormatter.renumberLog("1<>UID: x", 12), "12<>UID: x")
    assertEquals(DatLineFormatter.renumberLog("007<>a<>1<>b", 3), "3<>a<>1<>b")
    assertEquals(DatLineFormatter.renumberLog("<>a", 3), "<>a")
    assertEquals(DatLineFormatter.renumberLog("1a<>a", 3), "1a<>a")
    assertEquals(DatLineFormatter.renumberLog("１<>a", 3), "１<>a")
  }
}
