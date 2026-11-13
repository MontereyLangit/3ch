package io._3ch.bbs.domain.posting.moderation

class ThreadCreationNetworkSuite extends munit.FunSuite {
  private def group(country: String, asn: String, region: String, asOnly: Set[String] = Set.empty): String =
    ThreadCreationNetwork.group(Some(country), Some(asn), Some(region), asOnly)

  test("海外は overseas。JP・XX・T1 と 2 文字でない国は海外にしない") {
    assertEquals(group(" us ", "15169", ""), "overseas")
    assertEquals(group("XX", "1", "r"), "")
    assertEquals(group("T1", "1", "r"), "")
    assertEquals(group("USA", "1", "r"), "")
  }

  test("国内は ASN と地域、ASN だけを見る指定なら ASN") {
    assertEquals(group("jp", " 2516 ", " Tokyo "), "AS:2516:JP:Tokyo")
    assertEquals(group("JP", "2516", "Tokyo", Set("2516")), "AS:2516")
    assertEquals(group("JP", "2516", ""), "")
  }

  test("ASN は 1〜4294967295 の整数だけ使う") {
    assertEquals(group("JP", "0", "Tokyo"), "")
    assertEquals(group("JP", "4294967296", "Tokyo"), "")
    assertEquals(group("JP", "x", "Tokyo"), "")
    assertEquals(ThreadCreationNetwork.group(None, None, None, _ => true), "")
  }
}
