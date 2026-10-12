package ch3s.bbsclient

import munit.FunSuite

class MainSuite extends FunSuite {

  test("v6ClientPing は固定文字列を返す") {
    assertEquals(Main.v6ClientPing(), "pong-from-client")
  }
}
