package io._3ch.common.domain.posting

class UserInfoSuite extends munit.FunSuite {
  test("従来の書式で連結し、空の UID と市名の後の区切りなしを維持する") {
    val info = UserInfo("", "192.0.2.1", "1234", "JP", "Tokyo", "Chiyoda", "Win10", "Mozilla/5.0")
    assertEquals(info.text, "UID: IP:192.0.2.1 AS1234-JP,Tokyo,ChiyodaDevice:Win10 UA:Mozilla/5.0")
  }

  test("UID があれば IP の前に入る") {
    assertEquals(UserInfo("u1", "h", "0000", "unknown", "catv?", "", "", "").text, "UID:u1 IP:h AS0000-unknown,catv?,Device: UA:")
  }
}
