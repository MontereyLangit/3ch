package ch3s.bbsclient

import munit.FunSuite

class ClientEntrySuite extends FunSuite {

  test("v6ClientPing は固定文字列を返す") {
    assertEquals(ClientEntry.v6ClientPing(), "pong-from-client")
  }
}
