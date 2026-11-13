package io._3ch.common.domain.posting

import munit.FunSuite

class PhoebeAdmissionSuite extends FunSuite {
  test("スレ設定の値で Lv を判定し、値が無ければ板の値を使う") {
    val admission = PhoebeAdmission(lv = 2, exempt = false, boardLevel = 1)
    assertEquals(admission.refusal(None), None)
    assertEquals(admission.refusal(Some("2")), None)
    assertEquals(admission.refusal(Some("3")), Some(3))
    assertEquals(admission.refusal(Some("0")), None, "スレ設定が板設定を上書きする")
    assertEquals(admission.copy(boardLevel = 4).refusal(None), Some(4))
  }

  test("不正なスレ設定の値は 0 として通し、免除された投稿者はどの Lv でも通す") {
    assertEquals(PhoebeAdmission(0, exempt = false, boardLevel = 5).refusal(Some("abc")), None)
    assertEquals(PhoebeAdmission(0, exempt = false, boardLevel = 5).refusal(Some("11")), None)
    assertEquals(PhoebeAdmission(0, exempt = true, boardLevel = 5).refusal(Some("10")), None)
  }

  test("拒否のコードは必要 Lv を含み、0〜10 の整数だけ読み戻す") {
    assertEquals(PhoebeAdmission.code(3), "thread-write:level:3")
    (0 to 10).foreach(n => assertEquals(PhoebeAdmission.requiredOf(PhoebeAdmission.code(n)), Some(n)))
    Seq("thread-write:level:", "thread-write:level:11", "thread-write:level:x", "thread-write:level:-1", "thread-write:closed", "")
      .foreach(code => assertEquals(PhoebeAdmission.requiredOf(code), None, code))
  }
}
