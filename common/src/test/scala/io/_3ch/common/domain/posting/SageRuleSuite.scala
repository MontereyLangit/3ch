package io._3ch.common.domain.posting

import io._3ch.common.domain.BbsSettings

import scala.collection.immutable.ListMap

class SageRuleSuite extends munit.FunSuite {
  private def settings(entries: (String, String)*) = BbsSettings(ListMap(entries*))
  private val now = "1700000000"
  private def noRoll: Double = fail("乱数を読んだ")

  test("スレ立ては常に上げる") {
    assert(!SageRule.sage(1, "sage", "1", now, settings("BBS_LIVE" -> "checked"), noRoll))
  }

  test("メール欄に sage を含めば sage（大文字は含まない）") {
    assert(SageRule.sage(2, "xsagex", "1699990000", now, settings(), noRoll))
    assert(!SageRule.sage(2, "SAGE", "1699990000", now, settings(), noRoll))
  }

  test("BBS_FORCE_SAGE は key からの秒数を過ぎたら sage、空なら既定の 9999999999 秒") {
    assert(SageRule.sage(2, "", "1699990000", now, settings("BBS_FORCE_SAGE" -> "1"), noRoll))
    assert(!SageRule.sage(2, "", "1699990000", now, settings("BBS_FORCE_SAGE" -> "86400"), noRoll))
    assert(!SageRule.sage(2, "", "1699990000", now, settings("BBS_FORCE_SAGE" -> ""), noRoll))
  }

  test("BBS_LIVE は乱数が 0.1 以上なら sage") {
    val live = settings("BBS_LIVE" -> "checked")
    assert(SageRule.sage(2, "", "1699990000", now, live, 0.1))
    assert(!SageRule.sage(2, "", "1699990000", now, live, 0.05))
    assert(!SageRule.sage(2, "", "1699990000", now, settings("BBS_LIVE" -> "on"), noRoll))
  }
}
