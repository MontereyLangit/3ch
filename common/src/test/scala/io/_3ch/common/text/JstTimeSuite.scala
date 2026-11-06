package io._3ch.common.text

class JstTimeSuite extends munit.FunSuite {
  test("UNIX 秒を日本時間の日時にする") {
    assertEquals(JstTime.dateTime(0), "1970/01/01 09:00")
    assertEquals(JstTime.dateTime(1730800320), "2024/11/05 18:52")
    assertEquals(JstTime.dateTime(1735657199), "2024/12/31 23:59")
    assertEquals(JstTime.dateTime(1735657200), "2025/01/01 00:00")
  }
  test("閏日と月末をまたぐ") {
    assertEquals(JstTime.dateTime(1709218800), "2024/03/01 00:00")
    assertEquals(JstTime.dateTime(1709218799), "2024/02/29 23:59")
    assertEquals(JstTime.date(1709218799.9), "2024/02/29")
  }
}
