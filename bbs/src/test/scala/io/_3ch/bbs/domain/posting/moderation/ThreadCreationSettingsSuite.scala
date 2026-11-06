package io._3ch.bbs.domain.posting.moderation

import munit.FunSuite

class ThreadCreationSettingsSuite extends FunSuite {
  test("件数・有効期間・ASNの不正値を型付きの理由で返す") {
    val cases = Seq(
      Map("BBS_THREAD_TATESUGI" -> "-1") -> ThreadCreationSettingError.NonNegativeIntegerRequired("BBS_THREAD_TATESUGI"),
      Map("BBS_THREAD_TATESUGI" -> "1001") -> ThreadCreationSettingError.AboveMaximum("BBS_THREAD_TATESUGI", 1000),
      Map("BBS_THREAD_TATESUGI" -> "99999999999999999999999999") -> ThreadCreationSettingError.AboveMaximum("BBS_THREAD_TATESUGI", 1000),
      Map("BBS_THREAD_TATESUGI" -> "1/2/3") -> ThreadCreationSettingError.InvalidFormat("BBS_THREAD_TATESUGI"),
      Map("BBS_THREAD_JUNBAN_RESET" -> "315360001") -> ThreadCreationSettingError.AboveMaximum("BBS_THREAD_JUNBAN_RESET", 315360000L),
      Map("BBS_THREAD_JUNBAN_AS_ONLY" -> "AS0") -> ThreadCreationSettingError.PositiveAsnRequired,
      Map("BBS_THREAD_JUNBAN_AS_ONLY" -> "AS4294967296") -> ThreadCreationSettingError.AboveMaximum("BBS_THREAD_JUNBAN_AS_ONLY", 4294967295L),
      Map("BBS_THREAD_JUNBAN_AS_ONLY" -> "ASbad") -> ThreadCreationSettingError.NonNegativeIntegerRequired("BBS_THREAD_JUNBAN_AS_ONLY")
    )
    cases.foreach { (values, reason) => assertEquals(ThreadCreationSettings.parse(values).left.toOption, Some(reason)) }
  }

  test("通常規制の件数、有効期間、広域規制、ASNの順で最初の失敗を返す") {
    val invalid = Map(
      "BBS_THREAD_TATESUGI" -> "-1",
      "BBS_THREAD_TATESUGI_RESET" -> "bad",
      "BBS_THREAD_JUNBAN" -> "1/2/3",
      "BBS_THREAD_JUNBAN_AS_ONLY" -> "0"
    )
    assertEquals(ThreadCreationSettings.parse(invalid).left.toOption, Some(ThreadCreationSettingError.NonNegativeIntegerRequired("BBS_THREAD_TATESUGI")))
    assertEquals(ThreadCreationSettings.parse(invalid - "BBS_THREAD_TATESUGI").left.toOption, Some(ThreadCreationSettingError.NonNegativeIntegerRequired("BBS_THREAD_TATESUGI_RESET")))
    assertEquals(ThreadCreationSettings.parse(invalid -- Seq("BBS_THREAD_TATESUGI", "BBS_THREAD_TATESUGI_RESET")).left.toOption, Some(ThreadCreationSettingError.InvalidFormat("BBS_THREAD_JUNBAN")))
  }
}
