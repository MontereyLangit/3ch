package io._3ch.common.domain.identity

class ShikibetsuSuite extends munit.FunSuite {
  test("日付窓は JST の境界で変わり、parseInt と Number の設定解釈を保つ") {
    assertEquals(DateSalt.window("53999", "86400"), 0d)
    assertEquals(DateSalt.window("54000", "86400"), 1d)
    assertEquals(DateSalt.window("54000suffix", "0x15180"), 1d)
    assertEquals(DateSalt.window("-32401", "86400"), -1d)
    assertEquals(DateSalt.window("0", "0"), Double.PositiveInfinity)
    assertEquals(DateSalt.window("0", "-0"), Double.NegativeInfinity)
    assert(DateSalt.window("bad", "86400").isNaN)
    assert(DateSalt.window("0", "bad").isNaN)
    assertEquals(DateSalt.window("0", "Infinity"), 0d)
  }

  test("種の連結順と市の c を維持する") {
    val seeds = ShikibetsuSeeds("Tokyo", "Chiyoda", "1234", "Monazilla", "group", "19676", "salt")
    assertEquals(seeds.regionSeed, "Tokyogroup19676salt")
    assertEquals(seeds.citySeed, "Chiyodacgroup19676salt")
    assertEquals(seeds.asnSeed, "1234group19676salt")
    assertEquals(seeds.userAgentSeed, "Monazillagroup19676salt")
  }

  test("checked は県市 AS を2文字、UA を16文字に切り詰める") {
    assertEquals(ShikibetsuFormat.checked("JP", "abc", "def", "ghi", "0123456789abcdefghijkl"), "JP.ab.de gh-0123456789abcdef")
  }

  test("モード名は完全一致で読み、未知値は非表示にする") {
    import ShikibetsuMode.*
    val expected = List("checked" -> Checked, "raw" -> Raw, "ken" -> Ken, "city" -> City, "as" -> As,
      "d" -> Hidden, "phoebe" -> Hidden, "CHECKED" -> Hidden, "" -> Hidden)
    expected.foreach((value, mode) => assertEquals(ShikibetsuMode.parse(value), mode))
    assertEquals(ShikibetsuFormat.plain(Raw, "JP", "Tokyo", "Chiyoda", "1234"), "JP.Tokyo 1234")
    assertEquals(ShikibetsuFormat.plain(Ken, "JP", "Tokyo", "Chiyoda", "1234"), "JP.Tokyo")
    assertEquals(ShikibetsuFormat.plain(City, "JP", "Tokyo", "Chiyoda", "1234"), "JP.Tokyo.Chiyoda")
    assertEquals(ShikibetsuFormat.plain(As, "JP", "Tokyo", "Chiyoda", "1234"), "1234")
    assertEquals(ShikibetsuFormat.plain(Hidden, "JP", "Tokyo", "Chiyoda", "1234"), "")
  }
}
