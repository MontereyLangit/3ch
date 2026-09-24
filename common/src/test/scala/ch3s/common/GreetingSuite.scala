package ch3s.common

import munit.FunSuite

class GreetingSuite extends FunSuite {

  test("message は名前を埋め込んだ挨拶文を返す") {
    assertEquals(Greeting.message("3ch"), "Hello, 3ch! (ch3s v6s skeleton)")
  }
}
