package ch3s.common.domain

class BbsSettingsSuite extends munit.FunSuite {

  private def parse(text: String, bbs: String = "sample"): BbsSettings = BbsSettings.create(text, bbs)

  test("<DIR_NAME> は全箇所が板ディレクトリ名に置換される") {
    val s = parse("BBS_TITLE=<DIR_NAME>＠3ch掲示板\nPATH=/<DIR_NAME>/<DIR_NAME>/", "news")
    assertEquals(s.value("BBS_TITLE"), "news＠3ch掲示板")
    assertEquals(s.value("PATH"), "/news/news/")
  }

  test("キーは記載順を保つ") {
    val text = "Z=1\nA=2\nM=3\nBBS_TITLE=t\nB=4\nY=5\n"
    assertEquals(parse(text).value.keys.toList, List("Z", "A", "M", "BBS_TITLE", "B", "Y"))
  }

  test("重複キーは最初の位置のまま後勝ちの値になる") {
    val s = parse("A=1\nB=2\nC=3\nD=4\nE=5\nA=9\n")
    assertEquals(s.value.toList, List("A" -> "9", "B" -> "2", "C" -> "3", "D" -> "4", "E" -> "5"))
  }

  test("空の値は空文字列になり、最初の = より後ろ全体が値になる") {
    val s = parse("EMPTY=\nA=b=c\n K = v \r\n")
    assertEquals(s.value.toList, List("EMPTY" -> "", "A" -> "b=c", "K " -> "v"))
  }

  test("空行は無視される") {
    assertEquals(parse("\nA=1\n\n \r\nB=2\n\n").value.toList, List("A" -> "1", "B" -> "2"))
    assertEquals(parse("").value, Map.empty[String, String])
  }

  test("= を含まない行は無視される") {
    assertEquals(parse("A=1\nNOEQ\nB=2").value.toList, List("A" -> "1", "B" -> "2"))
  }

  test("parse はスレ設定の <> 区切りも同じ規則で読む") {
    val s = BbsSettings.parse("A=1<>B=x=y<>EMPTY=<>NOEQ<><>", "<>")
    assertEquals(s.value.toList, List("A" -> "1", "B" -> "x=y", "EMPTY" -> ""))
  }

  test("convertToText は k=v を改行で連結し、再パースで元に戻る") {
    val s = parse("Z=1\nA=2\nM=\nB=4\nY=5\n")
    val text = s.convertToText()
    assertEquals(text, "Z=1\nA=2\nM=\nB=4\nY=5")
    assertEquals(parse(text).value, s.value)
    assertEquals(parse(text).value.keys.toList, s.value.keys.toList)
  }

  test("create(Map) は渡したマップをそのまま保持する") {
    val s = BbsSettings.create(Map("A" -> "1"))
    assertEquals(s.value, Map("A" -> "1"))
    assertEquals(s.convertToText(), "A=1")
  }
}
