package io._3ch.common.domain

import scala.collection.immutable.ListMap

class BbsSettingsSuite extends munit.FunSuite {

  private def parse(text: String, bbs: String = "sample"): BbsSettings = BbsSettings.create(text, BoardId.create(bbs).toOption.get)

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

  test("toString は k=v を改行で連結し（末尾改行あり）、再パースで元に戻る") {
    val s = parse("Z=1\nA=2\nM=\nB=4\nY=5\n")
    val text = s.toString()
    assertEquals(text, "Z=1\nA=2\nM=\nB=4\nY=5\n")
    assertEquals(parse(text).value, s.value)
    assertEquals(parse(text).value.keys.toList, s.value.keys.toList)
  }

  test("型付き読み: get / orElse / checked") {
    val s = parse("A=x\nEMPTY=\nC=checked\nD=Checked\n")
    assertEquals(s.get("A"), Some("x"))
    assertEquals(s.get("NONE"), None)
    assertEquals(s.orElse("A", "d"), "x")
    assertEquals(s.orElse("EMPTY", "d"), "d")
    assertEquals(s.orElse("NONE", "d"), "d")
    assert(s.checked("C"))
    assert(!s.checked("D"))
    assert(!s.checked("NONE"))
  }

  test("型付き読み: number は無ければ既定値、空文字列は 0、数でなければ NaN") {
    val s = parse("N=12\nEMPTY=\nBAD=abc\nHEX=0x10\nEXP=1e2\n")
    assertEquals(s.number("N", 64), 12d)
    assertEquals(s.number("NONE", 64), 64d)
    assertEquals(s.number("EMPTY", 64), 0d)
    assert(s.number("BAD", 64).isNaN)
    assertEquals(s.number("HEX", 64), 16d)
    assertEquals(s.number("EXP", 64), 100d)
  }

  test("型付き読み: int は空なら既定値を parseInt で読む") {
    val s = parse("N=12abc\nEMPTY=\nBAD=abc\n")
    assertEquals(s.int("N", "700"), 12d)
    assertEquals(s.int("EMPTY", "700"), 700d)
    assertEquals(s.int("NONE", "700"), 700d)
    assert(s.int("BAD", "700").isNaN)
  }

  test("merged は既存キーの位置を保って上書きし、新しいキーを末尾に足す（既定値とのマージはしない）") {
    val board = parse("A=1\nB=2\n")
    val thread = BbsSettings.parse("B=x<>C=y<>", "<>")
    assertEquals(board.merged(thread).value.toList, List("A" -> "1", "B" -> "x", "C" -> "y"))
    assertEquals(board.updated("A", "z").value.toList, List("A" -> "z", "B" -> "2"))
  }

  test("toThreadSettings は配列の添字になるキーを数の順で先に並べ、ほかは元の順") {
    val settings = BbsSettings(ListMap("B" -> "1", "10" -> "a", "01" -> "b", "2" -> "c", "4294967295" -> "d", "4294967294" -> "e", "-1" -> "f", "0" -> "g"))
    assertEquals(settings.toThreadSettings, "0=g<>2=c<>10=a<>4294967294=e<>B=1<>01=b<>4294967295=d<>-1=f<>")
    assertEquals(BbsSettings(ListMap.empty).toThreadSettings, "")
  }
}
