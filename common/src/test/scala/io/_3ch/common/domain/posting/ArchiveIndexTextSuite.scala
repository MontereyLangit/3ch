package io._3ch.common.domain.posting

class ArchiveIndexTextSuite extends munit.FunSuite {
  test("スレ帯の目次は更新時刻を置き換えて先頭に足す") {
    assertEquals(ArchiveIndexText.threadIndex("1700", "1700000000", "題", "名", "識別", "本文", Some("1699\n1699999999<>前<>n<>s<><>m\n")),
      "1700\n1700000000<>題<>名<>識別<><>本文\n1699999999<>前<>n<>s<><>m\n")
    assertEquals(ArchiveIndexText.threadIndex("1700", "1700000000", "題", "名", "識別", "本文", None), "1700\n1700000000<>題<>名<>識別<><>本文\n")
  }

  test("スレ帯一覧は key の上 4 桁を足す") {
    assertEquals(ArchiveIndexText.bandList("1700", "1700000000", Some("1699\n1699<>1699999999\n")), "1700\n1700<>1700000000\n1699<>1699999999\n")
    assertEquals(ArchiveIndexText.bandList("1700", "1700000000", None), "1700\n1700<>1700000000\n")
  }

  test("headline は同じ板・題名なし・板名の誤り・欄の足りない行を落とし、残す行は前後の空白を除いて 5 欄にする") {
    val current = "1\n other<>他板<>1<>2<>本文<>余り \nsample<>同じ板<>1<>2<>m\nbad<><>1<>2<>m\nb@d<>x<>1<>2<>m\nshort<>x<>1\n"
    assertEquals(ArchiveIndexText.headline("9", "sample", Some("看板"), "1700000000", "あ" * 40, Some(current)),
      s"9\nsample<>看板<>9<>1700000000<>${"あ" * 30}\nother<>他板<>1<>2<>本文\n")
  }

  test("headline は板の題名が無ければ undefined と書き、既存は 300 行まで見る") {
    val current = (Seq("0") ++ (1 to 305).map(i => s"b$i<>t<>1<>2<>m")).mkString("\n")
    val lines = ArchiveIndexText.headline("9", "sample", None, "1", "m", Some(current)).split("\n", -1)
    assertEquals(lines(1), "sample<>undefined<>9<>1<>m")
    assertEquals(lines.length, 1 + 1 + 300 + 1)
    assertEquals(lines(301), "b300<>t<>1<>2<>m")
  }
}
