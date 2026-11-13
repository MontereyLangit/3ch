package io._3ch.bbs.domain.posting.moderation

import munit.FunSuite

class ModerationRuleSetSuite extends FunSuite {
  private def parse(text: String) = ModerationRuleSet.parse(text)

  test("コメント・空行・空白だけの行を飛ばす") {
    assertEquals(parse("# comment\n\n  \t\n   # indented\r\n^sample$<>IP:192\\.0\\.2\\.1 <>word<>deny").map(_.rules.length), Right(1))
    assertEquals(parse("").map(_.rules), Right(Nil))
  }

  test("動作欄を読み分ける") {
    val rules = parse("<><><>\n<><><>deny\n<><><>md5\n<><><>mark\n<><><>TateDame").map(_.rules.map(_.action))
    assertEquals(rules, Right(List(ModerationAction.Deny, ModerationAction.Deny, ModerationAction.Deny, ModerationAction.Mark, ModerationAction.TateDame)))
  }

  test("NG ワード欄が空でなければ NG ワードのルール") {
    assertEquals(parse("<><>word<>deny\n<>AS1<><>deny").map(_.rules.map(_.isWord)), Right(List(true, false)))
  }

  test("書式の誤りは最初の行番号と種類を返す") {
    assertEquals(parse("# ok\nbad format"), Left(ModerationRuleError(2, ModerationRuleProblem.Format)))
    assertEquals(parse("<><><><>deny"), Left(ModerationRuleError(1, ModerationRuleProblem.Format)))
    assertEquals(parse("<><><>unknown"), Left(ModerationRuleError(1, ModerationRuleProblem.Action)))
    assertEquals(parse("<><><>Deny"), Left(ModerationRuleError(1, ModerationRuleProblem.Action)))
    assertEquals(parse("[<><><>deny"), Left(ModerationRuleError(1, ModerationRuleProblem.Pattern)))
    assertEquals(parse("<><><>deny\r\n<>(<><>deny\nbad"), Left(ModerationRuleError(2, ModerationRuleProblem.Pattern)))
  }

  test("空欄は全対象、正規表現は JavaScript の RegExp で照合する") {
    val rule = parse("^sample$<>AS1234-<>(?<w>禁止)語<>deny").toOption.get.rules.head
    assert(rule.matches("sample", "IP:x AS1234-JP", Seq("a", "禁止語")))
    assert(!rule.matches("sample2", "IP:x AS1234-JP", Seq("禁止語")))
    assert(!rule.matches("sample", "IP:x AS9-JP", Seq("禁止語")))
    assert(!rule.matches("sample", "IP:x AS1234-JP", Seq("禁止")))
    val any = parse("<><><>deny").toOption.get.rules.head
    assert(any.matches("", "", Seq("")))
  }
}
