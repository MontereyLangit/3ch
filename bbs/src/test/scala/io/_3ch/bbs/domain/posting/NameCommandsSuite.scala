package io._3ch.bbs.domain.posting

import munit.FunSuite

class NameCommandsSuite extends FunSuite {
  test("!id・!no を取り除き、指定の有無を返す") {
    assertEquals(NameCommands.parse("名前!id"), NameCommands("名前", showId = true, noNusi = false))
    assertEquals(NameCommands.parse("名前!no"), NameCommands("名前", showId = false, noNusi = true))
    assertEquals(NameCommands.parse("名前 !id !no"), NameCommands("名前", showId = true, noNusi = true))
  }

  test("同じコマンドが複数あっても全て取り除く") {
    assertEquals(NameCommands.parse("!id a !id b !id"), NameCommands("a  b", showId = true, noNusi = false))
  }

  test("直後が英数字・_ のものは対象外") {
    List("!idol", "!id_x", "!id9", "!none", "!no_x", "!noA").foreach { name =>
      assertEquals(NameCommands.parse(name), NameCommands(name, showId = false, noNusi = false), name)
    }
  }

  test("日本語・記号が続く場合は対象") {
    assertEquals(NameCommands.parse("!idです"), NameCommands("です", showId = true, noNusi = false))
    assertEquals(NameCommands.parse("a!id!no"), NameCommands("a", showId = true, noNusi = true))
  }

  test("コマンドが無ければ名前をそのまま返す（空白も触らない）") {
    assertEquals(NameCommands.parse(" 名前 "), NameCommands(" 名前 ", showId = false, noNusi = false))
    assertEquals(NameCommands.parse(""), NameCommands("", showId = false, noNusi = false))
  }

  test("コマンドだけの名前は空になる") {
    assertEquals(NameCommands.parse(" !id "), NameCommands("", showId = true, noNusi = false))
  }

  test("大文字は対象外") {
    assertEquals(NameCommands.parse("!ID !No"), NameCommands("!ID !No", showId = false, noNusi = false))
  }
}
