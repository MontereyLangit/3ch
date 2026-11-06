package io._3ch.common.domain.posting

import munit.FunSuite

class PhoebeCommandSuite extends FunSuite {
  test("本文先頭の !バルサン と !SETTING:BBS_PHOEBE:n を読む") {
    assertEquals(PhoebeCommand.parse("!バルサン"), Some(PhoebeCommand.Balsan))
    assertEquals(PhoebeCommand.parse("!バルサン<br>荒らしが来たので"), Some(PhoebeCommand.Balsan))
    assertEquals(PhoebeCommand.parse("!バルサン よろしく"), Some(PhoebeCommand.Balsan))
    assertEquals(PhoebeCommand.parse("!SETTING:BBS_PHOEBE:5"), Some(PhoebeCommand.Setting(5)))
    assertEquals(PhoebeCommand.parse("!SETTING:BBS_PHOEBE:0<br>解除"), Some(PhoebeCommand.Setting(0)))
    assertEquals(PhoebeCommand.parse("!SETTING:BBS_PHOEBE:10"), Some(PhoebeCommand.Setting(10)))
  }

  test("先頭以外・語の途中・範囲外・数字なしはコマンドにしない") {
    Seq("本文 !バルサン", " !バルサン", "!バルサンです", "！バルサン", "!SETTING:BBS_PHOEBE:11", "!SETTING:BBS_PHOEBE:",
      "!SETTING:BBS_PHOEBE:-1", "!SETTING:BBS_PHOEBE:3x", "!SETTING:BBS_PHOEBE:100", "!setting:bbs_phoebe:3", "")
      .foreach(message => assertEquals(PhoebeCommand.parse(message), None, message))
  }

  test("スレ立て人は上げるだけ、staff の !SETTING は下げられ、どちらでもなければ要求にしない") {
    assertEquals(PhoebeCommand.Setting(5).request(owner = true, staff = false, boardLevel = 0), Some(ThreadPhoebeRequest(5, lower = false, 0)))
    assertEquals(PhoebeCommand.Setting(1).request(owner = false, staff = true, boardLevel = 2), Some(ThreadPhoebeRequest(1, lower = true, 2)))
    assertEquals(PhoebeCommand.Balsan.request(owner = false, staff = true, boardLevel = 0), Some(ThreadPhoebeRequest(3, lower = false, 0)))
    assertEquals(PhoebeCommand.Balsan.request(owner = false, staff = false, boardLevel = 0), None)
  }

  test("スレ設定の値が無ければ板の値を現在値にし、上げるときと許された下げだけ書く") {
    val raise = ThreadPhoebeRequest(3, lower = false, boardLevel = 0)
    assertEquals(raise.next(None), Some(3))
    assertEquals(raise.next(Some("3")), None)
    assertEquals(raise.next(Some("5")), None, "既に 3 より大きければ変えない")
    assertEquals(raise.copy(boardLevel = 5).next(None), None, "板の値が大きければスレ設定に書いて下げない")
    assertEquals(raise.next(Some("abc")), Some(3), "不正な値は 0 として扱う")
    val lower = ThreadPhoebeRequest(1, lower = true, boardLevel = 0)
    assertEquals(lower.next(Some("5")), Some(1))
    assertEquals(lower.next(Some("1")), None)
    assertEquals(lower.copy(level = 0).next(None), None)
  }
}
