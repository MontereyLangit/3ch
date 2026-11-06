package io._3ch.common.domain.identity

import munit.FunSuite

class IdentifierSuite extends FunSuite {
  import IdentifierAxis.*

  test("phoebe は 国.県 AS-5軸Lv の形で、県と AS は2文字にする") {
    assertEquals(ShikibetsuFormat.phoebe("JP", "abcd", "efgh", Seq("E+/", "FFF", "000", "ZZZ", "XXX"), 3), "JP.ab ef-E+/FFF000ZZZXXX3")
    assertEquals(ShikibetsuFormat.phoebe("unknown", "ab", "cd", Seq.fill(5)("000"), 0), "unknown.ab cd-0000000000000000")
  }

  test("D は未ログイン 0、1〜9 はその数字、10 以上は *") {
    assertEquals((0 to 11).map(ShikibetsuFormat.levelMark).toList, List("0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "*"))
    assertEquals(ShikibetsuFormat.levelMark(-1), "0")
  }

  test("軸の種は値・軸名・グループ・日付・ソルトの順につなぐ") {
    val seeds = ShikibetsuSeeds("Tokyo", "Chiyoda", "1234", "UA", "group", "19676", "salt")
    assertEquals(seeds.axisSeed(E, "Windows 11"), "Windows 11|Egroup19676salt")
    assertEquals(seeds.axisSeed(X, ""), "|Xgroup19676salt")
  }

  test("端末ハッシュは第1〜2・3〜5・6〜7・8・9軸に分ける") {
    val axes = IdentifierAxes.fromDevice("p0123456789abcdef01").get
    assertEquals(IdentifierAxis.values.toList.map(axes.value), List("0123", "456789", "abcd", "ef", "01"))
    val legacy = IdentifierAxes.fromDevice("0123456789abcdef").get
    assertEquals(IdentifierAxis.values.toList.map(legacy.value), List("0123", "456789", "abcd", "ef", ""))
    assertEquals(IdentifierAxes.fromDevice(""), None)
    assertEquals(IdentifierAxes.fromDevice("p0123"), None)
  }

  test("UA 解析の軸は OS・ブラウザ・端末種別・UA 全体・Client Hints と言語で、取れない値は空") {
    val axes = IdentifierAxes.fromAgent(UserAgentProfile("iOS 18", "Safari 18", "スマートフォン iPhone"), "Mozilla/5.0", "\"Chromium\";v=\"130\"", "ja")
    assertEquals(IdentifierAxis.values.toList.map(axes.value), List("iOS 18", "Safari 18", "スマートフォン iPhone", "Mozilla/5.0", "\"Chromium\";v=\"130\"|ja"))
    val bare = IdentifierAxes.fromAgent(UserAgentProfile(), "Monazilla/1.00", "", "")
    assertEquals(IdentifierAxis.values.toList.map(bare.value), List("", "", "", "Monazilla/1.00", ""))
  }
}
