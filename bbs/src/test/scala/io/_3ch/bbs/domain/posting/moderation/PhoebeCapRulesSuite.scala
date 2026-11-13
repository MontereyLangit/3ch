package io._3ch.bbs.domain.posting.moderation

class PhoebeCapRulesSuite extends munit.FunSuite {
  private val info = "UID:taro IP:192.0.2.1 AS16509-JP,Tokyo,Chiyoda Device:p0123 UA:Mozilla"

  test("当てはまる行の上限のうち最も低いもので抑え、当てはまらなければそのまま") {
    val rules = PhoebeCapRules.parse("# 上限\n\nAS16509-<>3\nDevice:p0<>5\nAS9999-<>0\n").toOption.get
    assertEquals(rules.cap(10, info), 3)
    assertEquals(rules.cap(2, info), 2)
    assertEquals(rules.cap(10, "UID: IP:x AS2516-JP"), 10)
    assertEquals(PhoebeCapRules.parse("<>1").toOption.get.cap(9, info), 1)
    assertEquals(PhoebeCapRules.empty.cap(7, info), 7)
  }

  test("書式・上限・正規表現の誤りは最初の行番号で返す") {
    assertEquals(PhoebeCapRules.parse("AS1<>3\nAS2\n"), Left(ModerationRuleError(2, ModerationRuleProblem.Format)))
    assertEquals(PhoebeCapRules.parse("AS1<>11"), Left(ModerationRuleError(1, ModerationRuleProblem.Action)))
    assertEquals(PhoebeCapRules.parse("AS1<>x"), Left(ModerationRuleError(1, ModerationRuleProblem.Action)))
    assertEquals(PhoebeCapRules.parse("(<>3"), Left(ModerationRuleError(1, ModerationRuleProblem.Pattern)))
  }
}
